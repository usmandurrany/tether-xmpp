package io.github.usmandurrany.tether.demo

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.im.ChatMessage
import io.github.usmandurrany.tether.im.ChatState
import io.github.usmandurrany.tether.im.Direction
import io.github.usmandurrany.tether.im.MessageEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/** Delivery state of a message, as shown by its ticks. */
enum class Delivery {
    /** Queued in the SDK's outbox or on its way: no tick yet. */
    SENDING,

    /** The server confirmed it (XEP-0198): one tick. */
    SENT,

    /** The contact's device confirmed it (XEP-0184): two ticks. */
    DELIVERED,

    /** The server bounced it. */
    FAILED,

    /** An incoming message. */
    RECEIVED,
}

data class UiMessage(
    /** The sender's message id. */
    val id: String?,
    /** The archive id, for de-duplicating live and archived copies. */
    val serverId: String?,
    val peer: BareJid,
    val outgoing: Boolean,
    val body: String,
    val timeMillis: Long,
    val delivery: Delivery,
    /** Copied from another of our devices (XEP-0280). */
    val carbon: Boolean = false,
    /** From offline storage or the archive rather than live. */
    val delayed: Boolean = false,
    val error: String? = null,
)

/**
 * Keeps the messages of this run in memory, fed from [MessageEvent]s and
 * archive pages. A real app would store them in a database; the SDK leaves
 * storage of messages to the app.
 */
class Conversations {
    private val _threads = MutableStateFlow<Map<BareJid, List<UiMessage>>>(emptyMap())

    /** Messages per contact, oldest first. */
    val threads: StateFlow<Map<BareJid, List<UiMessage>>> = _threads.asStateFlow()

    private val _typing = MutableStateFlow<Map<BareJid, ChatState>>(emptyMap())

    /** The last chat state (XEP-0085) each contact sent. */
    val typing: StateFlow<Map<BareJid, ChatState>> = _typing.asStateFlow()

    private val _unread = MutableStateFlow<Map<BareJid, Int>>(emptyMap())
    val unread: StateFlow<Map<BareJid, Int>> = _unread.asStateFlow()

    /** The conversation on screen; its messages are not counted as unread. */
    @Volatile
    var openChat: BareJid? = null
        set(value) {
            field = value
            if (value != null) _unread.update { it - value }
        }

    fun addOutgoing(peer: BareJid, id: String, body: String) {
        append(UiMessage(id, null, peer, outgoing = true, body, System.currentTimeMillis(), Delivery.SENDING))
    }

    fun onEvent(event: MessageEvent) {
        when (event) {
            is MessageEvent.Received -> {
                val message = event.message.toUi() ?: return
                if (append(message) && !message.outgoing && openChat != message.peer) {
                    _unread.update { it + (message.peer to (it[message.peer] ?: 0) + 1) }
                }
                // A message ends "typing…".
                if (!message.outgoing) _typing.update { it - message.peer }
            }
            is MessageEvent.SentToServer -> setDelivery(event.id) { if (it == Delivery.SENDING) Delivery.SENT else it }
            is MessageEvent.Delivered -> setDelivery(event.id) { Delivery.DELIVERED }
            is MessageEvent.Failed -> setDelivery(event.id, error = event.condition ?: "error") { Delivery.FAILED }
            is MessageEvent.ChatStateChanged -> _typing.update { it + (event.peer to event.state) }
        }
    }

    /** Adds an archive page, skipping messages already shown. */
    fun addHistory(peer: BareJid, page: List<ChatMessage>) {
        val older = page.mapNotNull { it.toUi() }
        _threads.update { all ->
            val current = all[peer].orEmpty()
            val fresh = older.filter { candidate -> current.none { it.sameAs(candidate) } }
            all + (peer to (fresh + current).sortedBy { it.timeMillis })
        }
    }

    fun clear() {
        _threads.value = emptyMap()
        _typing.value = emptyMap()
        _unread.value = emptyMap()
    }

    /** Returns false when the message was already there (a carbon or archive copy of a shown one). */
    private fun append(message: UiMessage): Boolean {
        var added = false
        _threads.update { all ->
            val current = all[message.peer].orEmpty()
            if (current.any { it.sameAs(message) }) {
                all
            } else {
                added = true
                all + (message.peer to current + message)
            }
        }
        return added
    }

    private fun setDelivery(id: String, error: String? = null, change: (Delivery) -> Delivery) {
        _threads.update { all ->
            all.mapValues { (_, messages) ->
                messages.map { if (it.outgoing && it.id == id) it.copy(delivery = change(it.delivery), error = error ?: it.error) else it }
            }
        }
    }

    private fun UiMessage.sameAs(other: UiMessage): Boolean =
        (serverId != null && serverId == other.serverId) ||
            (id != null && id == other.id && outgoing == other.outgoing)

    private fun ChatMessage.toUi(): UiMessage? {
        val text = body ?: return null
        val outgoing = direction == Direction.OUTGOING
        val time = delayedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis()
        return UiMessage(
            id = id,
            serverId = serverId,
            peer = peer,
            outgoing = outgoing,
            body = text,
            timeMillis = time,
            delivery = if (outgoing) Delivery.SENT else Delivery.RECEIVED,
            carbon = carbon,
            delayed = delayedAt != null,
        )
    }
}
