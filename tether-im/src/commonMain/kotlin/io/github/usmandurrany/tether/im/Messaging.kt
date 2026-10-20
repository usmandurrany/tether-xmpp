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
import kotlin.concurrent.Volatile

/** XEP-0085. */
public enum class ChatState(public val wire: String) {
    ACTIVE("active"), COMPOSING("composing"), PAUSED("paused"), INACTIVE("inactive"), GONE("gone");

    internal companion object {
        fun of(message: Element): ChatState? =
            message.elements.firstOrNull { it.namespace == ImNs.CHAT_STATES }?.let { e -> entries.firstOrNull { it.wire == e.name } }
    }
}

public enum class Direction {
    /** Someone sent it to us. */
    INCOMING,

    /** We sent it, from another device (a carbon) or from the archive. */
    OUTGOING,
}

/** A chat message as the app sees it. */
public data class ChatMessage(
    /** The sender's id for the message (also used for receipts and corrections). */
    val id: String?,
    /** The archive id the server gave it (XEP-0359 stanza-id), for de-duplication and paging. */
    val serverId: String?,
    /** The other side of the conversation. */
    val peer: BareJid,
    val direction: Direction,
    val body: String?,
    val thread: String?,
    /** "chat", "normal", "groupchat" or "headline". */
    val type: String,
    /** XEP-0203 timestamp (ISO 8601) when the message is not live: offline storage, archive. */
    val delayedAt: String?,
    /** Copied to this device from another one of ours (XEP-0280). */
    val carbon: Boolean,
    val chatState: ChatState?,
    val stanza: Element,
    /** The sender asked for read markers (XEP-0333): answer with [Messaging.markDisplayed] once it is shown. */
    val markable: Boolean = false,
) {
    /** The app's own elements in the message, such as a picture's details; read them with a [PayloadType]. */
    val payloads: List<Element> get() = Payloads.of(stanza)
}

/** What happens to messages. */
public sealed interface MessageEvent {
    /** A message with a body arrived (live, carbon, or offline storage). */
    public data class Received(val message: ChatMessage) : MessageEvent

    /** The server confirmed it got our message (XEP-0198): one tick. */
    public data class SentToServer(val id: String) : MessageEvent

    /** The recipient's device confirmed delivery (XEP-0184, or a XEP-0333 `received` marker): two ticks. */
    public data class Delivered(val peer: BareJid, val id: String) : MessageEvent

    /**
     * The contact has read our message [id] and every earlier one in the chat
     * (XEP-0333 `displayed`): blue ticks.
     */
    public data class Displayed(val peer: BareJid, val id: String) : MessageEvent

    /**
     * We read the contact's message [id], and every earlier one, on another of
     * our devices (a carbon of our own XEP-0333 marker): clear the unread count.
     */
    public data class DisplayedElsewhere(val peer: BareJid, val id: String) : MessageEvent

    /** The server bounced our message, for example because the address does not exist. */
    public data class Failed(val id: String, val peer: BareJid?, val condition: String?) : MessageEvent

    /** A contact started or stopped typing (XEP-0085). */
    public data class ChatStateChanged(val peer: BareJid, val state: ChatState) : MessageEvent

    /**
     * A message was deleted for everyone (XEP-0424). With [outgoing] false, the
     * contact [peer] deleted their message [id]; with it true, we deleted ours
     * to [peer] on another device. Only the message's own sender can delete it:
     * apply it only to messages in that direction with [peer].
     */
    public data class Retracted(val peer: BareJid, val id: String, val outgoing: Boolean) : MessageEvent
}

/**
 * One-to-one chat: sending with receipts and stable ids, receiving with
 * carbons unwrapped, delivery receipts, errors, and typing states.
 *
 * - Every sent message carries an `origin-id` (XEP-0359) equal to its id, so
 *   it can be recognised after a re-send.
 * - Receipts (XEP-0184) are requested on every chat message and answered for
 *   incoming ones, except for carbons and archived copies, which another device
 *   already answered or will answer.
 * - A carbon (XEP-0280) is only trusted when it comes from our own account:
 *   anyone else sending a "carbon" could otherwise inject messages that appear
 *   to come from us or from a contact.
 * - Sent messages are `markable` (XEP-0333), so the contact's client can say
 *   when they were read; those markers arrive as [MessageEvent.Displayed].
 *   Markers are only sent when the app calls [markDisplayed]: only the app
 *   knows when a message is on screen.
 *
 * Collect [events] before connecting: events with no collector are dropped.
 * Install [Carbons] too, to see messages sent and received on other devices.
 */
public class Messaging(
    private val client: StanzaSender,
    /** Answer receipt requests automatically. */
    private val sendReceipts: Boolean = true,
    /** Default for [send]: ask the recipient's device to confirm delivery (XEP-0184). */
    private val requestReceipts: Boolean = true,
    /** Default for [send]: let the recipient say when they read it (XEP-0333). */
    private val markable: Boolean = true,
    /** Add `<active/>` (XEP-0085) to sent messages. */
    private val chatStates: Boolean = true,
) : ClientExtension {
    @Volatile
    private var own: BareJid? = null

    private val _events = MutableSharedFlow<MessageEvent>(extraBufferCapacity = 256)
    public val events: SharedFlow<MessageEvent> = _events.asSharedFlow()

    override suspend fun onSessionStarted(session: SessionContext) {
        own = session.jid.bare
    }

    /**
     * Sends a chat message and returns its id. Returns once it is on its way;
     * [MessageEvent.SentToServer] and [MessageEvent.Delivered] follow.
     *
     * [payloads] go in the same stanza, after the body. [body] is what clients
     * that do not know them show, so make it a useful fallback: a caption, or
     * the link itself.
     */
    public suspend fun send(
        to: BareJid,
        body: String,
        id: String = newId(),
        thread: String? = null,
        requestReceipt: Boolean = requestReceipts,
        markable: Boolean = this.markable,
        /** The app's own elements, sent beside the body; see [PayloadType]. */
        payloads: List<Element> = emptyList(),
    ): String {
        Payloads.requireSendable(payloads)
        client.send(
            element("message", ImNs.CLIENT) {
                attribute("to", to.toString())
                attribute("type", "chat")
                attribute("id", id)
                element("body") { text(body) }
                thread?.let { element("thread") { text(it) } }
                element("origin-id", ImNs.SID) { attribute("id", id) }
                if (requestReceipt) element("request", ImNs.RECEIPTS)
                if (markable) element("markable", ImNs.MARKERS)
                if (chatStates) element("active", ImNs.CHAT_STATES)
                payloads.forEach(::child)
                // Servers may skip archiving a message with an empty body; data alone is still worth keeping.
                if (body.isEmpty() && payloads.isNotEmpty()) element("store", ImNs.HINTS)
            },
        )
        return id
    }

    /**
     * Tells [to] that we have read their message [id] and everything before it
     * (XEP-0333 `displayed`). Only for messages that were [ChatMessage.markable];
     * other clients ignore markers for the rest. Kept by the server for a
     * contact who is offline, and copied to our other devices as a carbon.
     */
    public suspend fun markDisplayed(to: BareJid, id: String, thread: String? = null) {
        client.send(
            element("message", ImNs.CLIENT) {
                attribute("to", to.toString())
                attribute("type", "chat")
                element("displayed", ImNs.MARKERS) { attribute("id", id) }
                thread?.let { element("thread") { text(it) } }
                element("store", ImNs.HINTS)
            },
        )
    }

    /**
     * Deletes our message [id] for everyone (XEP-0424): the contact's apps that
     * support it remove it, and the server may remove it from the archive.
     * Apps that do not support it show a short note instead. [id] is the id
     * the message was sent with.
     */
    public suspend fun retract(to: BareJid, id: String) {
        client.send(
            element("message", ImNs.CLIENT) {
                attribute("to", to.toString())
                attribute("type", "chat")
                attribute("id", newId())
                element("retract", ImNs.RETRACT) { attribute("id", id) }
                element("fallback", ImNs.FALLBACK) { attribute("for", ImNs.RETRACT) }
                element("body") { text(RETRACT_FALLBACK) }
                element("store", ImNs.HINTS)
            },
        )
    }

    /** Tells [to] that we are typing, paused, and so on. Never stored or archived. */
    public suspend fun sendChatState(to: BareJid, state: ChatState) {
        client.send(
            element("message", ImNs.CLIENT) {
                attribute("to", to.toString())
                attribute("type", "chat")
                element(state.wire, ImNs.CHAT_STATES)
                element("no-store", ImNs.HINTS)
            },
        )
    }

    override fun onConfirmed(stanzas: List<Element>) {
        for (s in stanzas) {
            if (s.name == "message" && s.child("body", ImNs.CLIENT) != null) {
                s.attribute("id")?.let { _events.tryEmit(MessageEvent.SentToServer(it)) }
            }
        }
    }

    override suspend fun onStanza(stanza: Element): Boolean {
        if (stanza.name != "message") return false
        val me = own
        // A carbon wraps the real message; unwrap it only when it comes from our own account.
        Carbons.unwrap(stanza, me)?.let { (copy, sent) ->
            handle(copy, carbon = true, outgoing = sent, delay = null)
            return true
        }
        if (stanza.child("received", ImNs.CARBONS) != null || stanza.child("sent", ImNs.CARBONS) != null) {
            return true // A carbon from someone else: forged, dropped.
        }
        return handle(stanza, carbon = false, outgoing = false, delay = delayOf(stanza))
    }

    private suspend fun handle(message: Element, carbon: Boolean, outgoing: Boolean, delay: String?): Boolean {
        val type = message.attribute("type") ?: "normal"
        val peerAddress = if (outgoing) message.attribute("to") else message.attribute("from")
        val peer = bareOf(peerAddress) ?: own ?: return false
        val id = message.attribute("id")

        if (type == "error") {
            if (id != null) _events.emit(MessageEvent.Failed(id, bareOf(message.attribute("from")), errorCondition(message)))
            return true
        }
        message.child("received", ImNs.RECEIPTS)?.attribute("id")?.let { receipt ->
            // Our other device's receipt to a contact is not a delivery of our message.
            if (!outgoing) _events.emit(MessageEvent.Delivered(peer, receipt))
            return true
        }
        if (type != "groupchat") {
            // Before the body check: a retraction carries a fallback body for older clients.
            retractedIdOf(message)?.let { retracted ->
                _events.emit(MessageEvent.Retracted(peer, retracted, outgoing))
                return true
            }
        }
        val state = ChatState.of(message)
        if (type != "groupchat") {
            markerOf(message)?.let { (marker, markedId) ->
                when {
                    // Our other device read their message: we have read it too.
                    outgoing -> if (marker != "received") _events.emit(MessageEvent.DisplayedElsewhere(peer, markedId))
                    marker == "received" -> _events.emit(MessageEvent.Delivered(peer, markedId))
                    else -> _events.emit(MessageEvent.Displayed(peer, markedId))
                }
                // A marker can share its stanza with a typing state.
                if (state != null && !outgoing) _events.emit(MessageEvent.ChatStateChanged(peer, state))
                return true
            }
        }
        if (message.child("body", ImNs.CLIENT) == null) {
            if (state != null && !outgoing) {
                _events.emit(MessageEvent.ChatStateChanged(peer, state))
                return true
            }
            return false // Not a chat message; leave it for other extensions or the app.
        }
        val chat = chatMessageOf(message, peer, outgoing, carbon, delay, serverId = null, own = own) ?: return false
        _events.emit(MessageEvent.Received(chat))
        val wantsReceipt = message.child("request", ImNs.RECEIPTS) != null
        if (sendReceipts && wantsReceipt && !outgoing && !carbon && id != null && type != "groupchat") {
            client.send(
                element("message", ImNs.CLIENT) {
                    attribute("to", message.attribute("from"))
                    attribute("id", newId())
                    element("received", ImNs.RECEIPTS) { attribute("id", id) }
                    element("store", ImNs.HINTS) // a receipt is worth keeping until the sender comes online
                },
            )
        }
        return true
    }

    internal companion object {
        fun delayOf(message: Element): String? = message.child("delay", ImNs.DELAY)?.attribute("stamp")

        /** The XEP-0333 marker ("received", "displayed" or "acknowledged") and the id it marks. */
        fun markerOf(message: Element): Pair<String, String>? {
            val marker = message.elements.firstOrNull { it.namespace == ImNs.MARKERS && it.name in MARKERS } ?: return null
            val id = marker.attribute("id") ?: return null
            return marker.name to id
        }

        private val MARKERS = setOf("received", "displayed", "acknowledged")

        /** The id of the message a XEP-0424 retraction deletes, or null when [message] is not one. */
        fun retractedIdOf(message: Element): String? = message.child("retract", ImNs.RETRACT)?.attribute("id")

        /** The body older clients show for a retraction (the text XEP-0424 suggests). */
        const val RETRACT_FALLBACK: String =
            "This person attempted to retract a previous message, but it's unsupported by your client."
    }
}

/** A [ChatMessage] for a message with a body; null without one. */
internal fun chatMessageOf(
    message: Element,
    peer: BareJid,
    outgoing: Boolean,
    carbon: Boolean,
    delay: String?,
    serverId: String?,
    own: BareJid?,
): ChatMessage? {
    val body = message.child("body", ImNs.CLIENT)?.text ?: return null
    return ChatMessage(
        id = message.child("origin-id", ImNs.SID)?.attribute("id") ?: message.attribute("id"),
        serverId = serverId ?: message.children("stanza-id", ImNs.SID)
            .firstOrNull { it.attribute("by") == own?.toString() }?.attribute("id"),
        peer = peer,
        direction = if (outgoing) Direction.OUTGOING else Direction.INCOMING,
        body = body,
        thread = message.child("thread", ImNs.CLIENT)?.text,
        type = message.attribute("type") ?: "normal",
        delayedAt = delay,
        carbon = carbon,
        chatState = ChatState.of(message),
        stanza = message,
        markable = message.child("markable", ImNs.MARKERS) != null,
    )
}

/**
 * Message Carbons (XEP-0280): copies of our conversations from our other
 * devices. Enabled on each new session when the server offers it; [Messaging]
 * unwraps the copies.
 */
public class Carbons : ClientExtension {
    private val _enabled = MutableStateFlow(false)
    public val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var started = false

    override suspend fun onSessionStarted(session: SessionContext) {
        val firstInProcess = !started
        started = true
        if (session.resumed) {
            // Still enabled on the server's side. After an app restart that resumed the
            // saved session, the earlier run enabled it whenever the server offers it.
            if (firstInProcess) _enabled.value = session.capabilities.serverSupports(ImNs.CARBONS)
            return
        }
        _enabled.value = false
        if (!session.capabilities.serverSupports(ImNs.CARBONS)) return
        val answer = session.request(iq("set") { element("enable", ImNs.CARBONS) })
        _enabled.value = answer.attribute("type") == "result"
    }

    internal companion object {
        /**
         * The forwarded message and whether we sent it, when [stanza] is a carbon
         * from our own account; null otherwise.
         */
        fun unwrap(stanza: Element, own: BareJid?): Pair<Element, Boolean>? {
            val wrapper = stanza.child("received", ImNs.CARBONS) ?: stanza.child("sent", ImNs.CARBONS) ?: return null
            if (own == null || stanza.attribute("from") != own.toString()) return null
            val inner = wrapper.child("forwarded", ImNs.FORWARD)?.child("message", ImNs.CLIENT) ?: return null
            return inner to (wrapper.name == "sent")
        }
    }
}
