@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.ClientExtension
import io.github.usmandurrany.tether.core.SessionContext
import io.github.usmandurrany.tether.core.StanzaSender
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** RFC 6121 §4.7.2.1. Null means plainly available. */
public enum class Show(public val wire: String) {
    CHAT("chat"), AWAY("away"), XA("xa"), DND("dnd");

    internal companion object {
        fun parse(value: String?): Show? = entries.firstOrNull { it.wire == value }
    }
}

/** What we tell contacts about ourselves. */
public data class OwnPresence(
    val show: Show? = null,
    val status: String? = null,
    /** -128..127; negative means "never route messages to this device" (RFC 6121 §4.7.2.3). */
    val priority: Int = 0,
) {
    init {
        require(priority in -128..127)
    }
}

/** One online resource of a contact. */
public data class ContactPresence(val resource: String?, val show: Show?, val status: String?, val priority: Int)

/** Subscription traffic (RFC 6121 §3) the app may want to act on. */
public sealed interface SubscriptionEvent {
    public val from: BareJid

    /** They want to see our presence: answer with [Presences.approve] or [Presences.deny]. */
    public data class Requested(override val from: BareJid, val status: String?) : SubscriptionEvent

    /** They approved our request. */
    public data class Approved(override val from: BareJid) : SubscriptionEvent

    /** They denied our request or revoked our subscription. */
    public data class Revoked(override val from: BareJid) : SubscriptionEvent

    /** They stopped seeing our presence. */
    public data class Unsubscribed(override val from: BareJid) : SubscriptionEvent
}

/**
 * Our presence and our contacts' (RFC 6121 §3, §4).
 *
 * On each new session the server knows nothing about us yet: the last
 * [OwnPresence] is sent again, and contact presences are rebuilt from what the
 * server then sends. A resumed session keeps both.
 *
 * Install after [Roster], so the roster is in place before presence goes out.
 */
public class Presences(
    private val client: StanzaSender,
    initial: OwnPresence = OwnPresence(),
    /** False keeps the account invisible to contacts until [set] is called. */
    private val announceOnConnect: Boolean = true,
) : ClientExtension {
    @Volatile
    private var own = initial

    @Volatile
    private var announced = announceOnConnect

    private val _contacts = MutableStateFlow<Map<BareJid, List<ContactPresence>>>(emptyMap())

    /** Online resources per contact. A contact missing here is offline. */
    public val contacts: StateFlow<Map<BareJid, List<ContactPresence>>> = _contacts.asStateFlow()

    private val _wentOffline = MutableStateFlow<Map<BareJid, Long>>(emptyMap())

    /**
     * When each contact last went offline while we watched, in milliseconds
     * since the epoch. For earlier times, ask with [LastActivity].
     */
    public val wentOffline: StateFlow<Map<BareJid, Long>> = _wentOffline.asStateFlow()

    private val _subscriptions = MutableSharedFlow<SubscriptionEvent>(extraBufferCapacity = 64)

    /** Collect before connecting: requests that arrive with no collector are dropped (the server re-sends them on the next login). */
    public val subscriptions: SharedFlow<SubscriptionEvent> = _subscriptions.asSharedFlow()

    /** False until this process has seen a session: a resumed one then carries no presence we know of. */
    @Volatile
    private var started = false

    override suspend fun onSessionStarted(session: SessionContext) {
        val firstInProcess = !started
        started = true
        if (session.resumed && !firstInProcess) return // Both sides kept their presence state.
        _contacts.value = emptyMap()
        if (session.resumed) {
            // An app restart resumed the saved session: the server still has our presence,
            // but this process never saw the contacts'. Going unavailable and back makes the
            // next presence initial again (RFC 6121 §4.2), so the server probes the contacts.
            if (!announced) return
            session.send(element("presence", ImNs.CLIENT) { attribute("type", "unavailable") })
        }
        if (announced) session.send(presenceStanza(own))
    }

    override suspend fun onStanza(stanza: Element): Boolean {
        if (stanza.name != "presence") return false
        val from = bareOf(stanza.attribute("from")) ?: return false
        val resource = resourceOf(stanza.attribute("from"))
        when (stanza.attribute("type")) {
            null -> {
                val presence = ContactPresence(
                    resource = resource,
                    show = Show.parse(stanza.child("show", ImNs.CLIENT)?.text),
                    status = stanza.child("status", ImNs.CLIENT)?.text,
                    priority = stanza.child("priority", ImNs.CLIENT)?.text?.trim()?.toIntOrNull()?.coerceIn(-128, 127) ?: 0,
                )
                _contacts.update { all ->
                    val others = all[from].orEmpty().filter { it.resource != resource }
                    all + (from to (others + presence))
                }
            }
            "unavailable" -> {
                var offline = false
                _contacts.update { all ->
                    val left = if (resource == null) emptyList() else all[from].orEmpty().filter { it.resource != resource }
                    offline = left.isEmpty() && all.containsKey(from)
                    if (left.isEmpty()) all - from else all + (from to left)
                }
                // Their last device left: that is their "last seen".
                if (offline) _wentOffline.update { it + (from to Clock.System.now().toEpochMilliseconds()) }
            }
            "subscribe" -> _subscriptions.emit(SubscriptionEvent.Requested(from, stanza.child("status", ImNs.CLIENT)?.text))
            "subscribed" -> _subscriptions.emit(SubscriptionEvent.Approved(from))
            "unsubscribed" -> _subscriptions.emit(SubscriptionEvent.Revoked(from))
            "unsubscribe" -> _subscriptions.emit(SubscriptionEvent.Unsubscribed(from))
            else -> return false // "error", "probe": not for the app
        }
        return true
    }

    /** Changes what contacts see, now and on every later session. */
    public suspend fun set(presence: OwnPresence) {
        own = presence
        announced = true
        client.send(presenceStanza(presence))
    }

    /** Goes offline for contacts while staying connected. */
    public suspend fun hide() {
        announced = false
        client.send(element("presence", ImNs.CLIENT) { attribute("type", "unavailable") })
    }

    /** Asks to see [jid]'s presence. */
    public suspend fun request(jid: BareJid): Unit = subscription(jid, "subscribe")

    /** Lets [jid] see our presence. */
    public suspend fun approve(jid: BareJid): Unit = subscription(jid, "subscribed")

    /** Refuses or revokes [jid]'s view of our presence. */
    public suspend fun deny(jid: BareJid): Unit = subscription(jid, "unsubscribed")

    /** Stops seeing [jid]'s presence. */
    public suspend fun cancel(jid: BareJid): Unit = subscription(jid, "unsubscribe")

    private suspend fun subscription(jid: BareJid, type: String) {
        client.send(
            element("presence", ImNs.CLIENT) {
                attribute("to", jid.toString())
                attribute("type", type)
            },
        )
    }

    private fun presenceStanza(presence: OwnPresence): Element = element("presence", ImNs.CLIENT) {
        presence.show?.let { element("show") { text(it.wire) } }
        presence.status?.let { element("status") { text(it) } }
        if (presence.priority != 0) element("priority") { text(presence.priority.toString()) }
    }
}
