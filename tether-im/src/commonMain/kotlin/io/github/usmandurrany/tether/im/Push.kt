package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.ClientExtension
import io.github.usmandurrany.tether.core.SessionContext
import io.github.usmandurrany.tether.core.StanzaSender
import io.github.usmandurrany.tether.xml.ElementBuilder
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/**
 * Where the server should send push notifications (XEP-0357 §5): an app
 * server or PubSub service, a node on it, and options passed along with every
 * notification, typically the device token.
 *
 * Servers differ only in what they expect here:
 * - **ejabberd, Prosody, Openfire** (push plugin): [service] is your app
 *   server's JID, [node] the id it gave this device, and [publishOptions] its
 *   secret or token.
 * - **MongooseIM** (`mod_event_pusher_push`): [service] is its push PubSub
 *   service (often `pubsub.<domain>`), [node] a name you pick per device, with
 *   [createNode] true; [publishOptions] usually holds `device_id` (the FCM or
 *   APNs token) and `service` (`fcm` or `apns`).
 */
public data class PushTarget(
    val service: String,
    val node: String,
    val publishOptions: Map<String, String> = emptyMap(),
    /** Create [node] on [service] first, as MongooseIM requires (`<create type='push'/>`). */
    val createNode: Boolean = false,
)

/**
 * A push app server that gives each device its node through an ad-hoc command
 * (XEP-0050): the client sends the device token, and the answer holds the node
 * and any secret for [Push.register]. Use a preset for a known server, or name
 * the [command] and [fields] for another.
 */
public data class PushAppServer(
    /** The app server's address, such as `p2.example.com`. */
    val jid: String,
    /** The ad-hoc command's node, such as `register-push-fcm`. */
    val command: String,
    /** What to fill in: the device token and anything else the command asks for. */
    val fields: Map<String, String>,
) {
    public companion object {
        /**
         * Conversations' open-source app server, p2, and servers compatible with
         * it. [deviceId] is a stable id for this install (Conversations uses the
         * Android ID); the answer's `secret` goes along with every notification.
         */
        public fun p2(jid: String, fcmToken: String, deviceId: String): PushAppServer =
            PushAppServer(jid, "register-push-fcm", mapOf("token" to fcmToken, "android-id" to deviceId))

        /** Openfire's Push Server plugin (FCM). */
        public fun openfire(jid: String, fcmToken: String, deviceId: String): PushAppServer =
            PushAppServer(jid, "register-push-fcm", mapOf("token" to fcmToken, "device-id" to deviceId))

        /**
         * Tigase's Push component. [provider] names the push service and app it
         * was configured with, `fcm-xmpp-api` for FCM in its default setup.
         */
        public fun tigase(jid: String, deviceToken: String, provider: String = "fcm-xmpp-api"): PushAppServer =
            PushAppServer(jid, "register-device", mapOf("provider" to provider, "device-token" to deviceToken))
    }
}

/** An app server's ad-hoc command was refused or gave no node. */
public class PushRegistrationException(message: String, public val condition: String? = null) : Exception(message)

public sealed interface PushState {
    /** Nothing registered. */
    public data object Off : PushState

    /** The account's server does not offer XEP-0357. */
    public data object Unsupported : PushState

    /** Registered; waiting for a session to tell the server. */
    public data class Pending(val target: PushTarget) : PushState

    public data class On(val target: PushTarget) : PushState

    /** The server refused; [condition] says why (for example "item-not-found" for a bad node). */
    public data class Failed(val target: PushTarget, val condition: String?) : PushState
}

/**
 * Push notifications for when the app is closed or in the background
 * (XEP-0357). With [io.github.usmandurrany.tether.core.BackgroundMode.CLOSE]
 * the server sees the account offline the moment the app leaves the
 * foreground, so every new message goes to push.
 *
 * The registration is sent again on every new session, so a changed device
 * token always reaches the server; servers replace an existing registration
 * for the same node. Enabling only happens when the account advertises
 * `urn:xmpp:push:0`.
 */
public class Push(private val client: StanzaSender) : ClientExtension {
    private val _state = MutableStateFlow<PushState>(PushState.Off)
    public val state: StateFlow<PushState> = _state.asStateFlow()


    @Volatile
    private var target: PushTarget? = null

    @Volatile
    private var supported: Boolean? = null

    override suspend fun onSessionStarted(session: SessionContext) {
        val offered = session.capabilities.accountSupports(ImNs.PUSH) || session.capabilities.serverSupports(ImNs.PUSH)
        supported = offered
        val wanted = target ?: return
        if (!offered) {
            _state.value = PushState.Unsupported
            return
        }
        if (session.resumed && _state.value is PushState.On) return
        enable(session, wanted)
    }

    /**
     * Registers this device. Takes effect now when connected, otherwise on the
     * next session. Call again whenever the device token changes.
     */
    public suspend fun register(target: PushTarget) {
        this.target = target
        when (supported) {
            false -> _state.value = PushState.Unsupported
            // Not connected yet: the next session enables it.
            null -> _state.value = PushState.Pending(target)
            // Outside the lock: the request may wait for a session that is still being set up,
            // and enabling twice is harmless.
            true -> {
                _state.value = PushState.Pending(target)
                runCatching { enable(client, target) }
            }
        }
    }

    /**
     * Gets a node for this device from [appServer], then registers it as
     * [register] does. Needs a connection: the command goes to the app server
     * through the account's server. Call again whenever the device token
     * changes. Throws [PushRegistrationException] if the app server refuses.
     */
    public suspend fun register(appServer: PushAppServer): PushTarget {
        val target = provision(appServer)
        register(target)
        return target
    }

    /**
     * Runs [appServer]'s command and reads the node from the answer. Some
     * servers take the filled-in form with `execute`; others answer it with a
     * blank form first, which is then submitted in the same command session.
     */
    private suspend fun provision(appServer: PushAppServer): PushTarget {
        var answer = client.request(command(appServer, action = "execute", sessionId = null))
        var command = answer.child("command", ImNs.COMMANDS)
        if (answer.attribute("type") == "result" && command?.attribute("status") == "executing") {
            answer = client.request(command(appServer, action = "complete", sessionId = command.attribute("sessionid")))
            command = answer.child("command", ImNs.COMMANDS)
        }
        if (answer.attribute("type") != "result") {
            val condition = errorCondition(answer)
            throw PushRegistrationException("${appServer.jid} refused ${appServer.command}: ${condition ?: "unknown error"}", condition)
        }
        val values = command?.child("x", ImNs.DATA_FORMS)?.children("field", ImNs.DATA_FORMS).orEmpty()
            .mapNotNull { field -> field.attribute("var")?.let { it to field.child("value", ImNs.DATA_FORMS)?.text.orEmpty() } }
            .toMap()
        val node = values["node"]?.ifEmpty { null }
            ?: throw PushRegistrationException("${appServer.jid} gave no node for ${appServer.command}")
        // Everything else in the answer (p2's secret, say) is sent back with each notification.
        val options = values.filterKeys { it != "node" && it != "jid" && it != "FORM_TYPE" }.filterValues { it.isNotEmpty() }
        return PushTarget(service = values["jid"]?.ifEmpty { null } ?: appServer.jid, node = node, publishOptions = options)
    }

    private fun command(appServer: PushAppServer, action: String, sessionId: String?) =
        iq("set", to = appServer.jid) {
            element("command", ImNs.COMMANDS) {
                attribute("node", appServer.command)
                attribute("action", action)
                attribute("sessionid", sessionId)
                element("x", ImNs.DATA_FORMS) {
                    attribute("type", "submit")
                    appServer.fields.forEach { (name, value) ->
                        element("field") {
                            attribute("var", name)
                            element("value") { text(value) }
                        }
                    }
                }
            }
        }

    /** Stops notifications for this device, for example on logout. */
    public suspend fun unregister() {
        val old = target ?: return
        target = null
        _state.value = PushState.Off
        if (supported != true) return
        runCatching {
            client.request(
                iq("set") {
                    element("disable", ImNs.PUSH) {
                        attribute("jid", old.service)
                        attribute("node", old.node)
                    }
                },
            )
        }
    }

    /**
     * No lock here: a registration made while a session is being set up waits
     * for that session, and the setup itself may be enabling too. Enabling twice
     * is harmless; only the state of the current target is reported.
     */
    private suspend fun enable(via: StanzaSender, wanted: PushTarget) {
        if (target != wanted) return // replaced or unregistered meanwhile
        if (wanted.createNode) {
            val created = via.request(
                iq("set", to = wanted.service) {
                    element("pubsub", PUBSUB) {
                        element("create") {
                            attribute("node", wanted.node)
                            attribute("type", "push")
                        }
                    }
                },
            )
            // "conflict" means it is already there, which is what we want.
            if (created.attribute("type") != "result" && errorCondition(created) != "conflict") {
                report(wanted, PushState.Failed(wanted, errorCondition(created)))
                return
            }
        }
        val answer = via.request(
            iq("set") {
                element("enable", ImNs.PUSH) {
                    attribute("jid", wanted.service)
                    attribute("node", wanted.node)
                    if (wanted.publishOptions.isNotEmpty()) publishOptions(wanted.publishOptions)
                }
            },
        )
        report(wanted, if (answer.attribute("type") == "result") PushState.On(wanted) else PushState.Failed(wanted, errorCondition(answer)))
    }

    private fun report(forTarget: PushTarget, state: PushState) {
        if (target == forTarget) _state.value = state
    }

    private fun ElementBuilder.publishOptions(options: Map<String, String>) {
        element("x", ImNs.DATA_FORMS) {
            attribute("type", "submit")
            element("field") {
                attribute("var", "FORM_TYPE")
                element("value") { text("http://jabber.org/protocol/pubsub#publish-options") }
            }
            options.forEach { (name, value) ->
                element("field") {
                    attribute("var", name)
                    element("value") { text(value) }
                }
            }
        }
    }

    private companion object {
        const val PUBSUB = "http://jabber.org/protocol/pubsub"
    }
}
