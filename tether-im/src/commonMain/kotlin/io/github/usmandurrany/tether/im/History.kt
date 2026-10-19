package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.ClientExtension
import io.github.usmandurrany.tether.core.SessionContext
import io.github.usmandurrany.tether.core.StanzaSender
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/** One page of the archive, oldest first. Pass [first] or [last] to page further. */
public data class HistoryPage(
    val messages: List<ChatMessage>,
    /** Archive id of the oldest message here: pass as `before` for older ones. */
    val first: String?,
    /** Archive id of the newest message here: pass as `after` for newer ones. */
    val last: String?,
    /** No more messages in the direction asked for. */
    val complete: Boolean,
    /** Delivery receipts and read markers archived in the same range, oldest first. */
    val markers: List<ArchivedMarker> = emptyList(),
)

/** A delivery receipt (XEP-0184) or chat marker (XEP-0333) found in the archive. */
public data class ArchivedMarker(
    val peer: BareJid,
    /** The id of the message it marks. */
    val id: String,
    /** True when the contact read it; false for a delivery receipt or a `received` marker. */
    val displayed: Boolean,
    /** We sent it (about the contact's message); false when the contact sent it (about ours). */
    val outgoing: Boolean,
)

/**
 * The server-side message archive (XEP-0313 MAM). [available] tells whether
 * the account has one; [fetch] fails with [XmppStanzaException] otherwise.
 *
 * Archived messages are returned in the page, not as live events, so
 * catching up never sends receipts or typing notifications. Only results from
 * our own account's archive are accepted.
 */
public class History(private val client: StanzaSender) : ClientExtension {
    private val _available = MutableStateFlow(false)
    public val available: StateFlow<Boolean> = _available.asStateFlow()

    @Volatile
    private var own: BareJid? = null

    private val mutex = Mutex()
    private val collecting = HashMap<String, Collected>()

    private class Collected {
        val messages = ArrayList<ChatMessage>()
        val markers = ArrayList<ArchivedMarker>()
    }

    override suspend fun onSessionStarted(session: SessionContext) {
        own = session.jid.bare
        _available.value = session.capabilities.accountSupports(ImNs.MAM)
    }

    /**
     * Fetches up to [max] messages. With neither [before] nor [after], returns
     * the newest page. [with] limits it to one conversation.
     */
    public suspend fun fetch(
        with: BareJid? = null,
        before: String? = null,
        after: String? = null,
        max: Int = 50,
    ): HistoryPage {
        require(before == null || after == null) { "Page in one direction at a time" }
        require(max in 1..1000)
        val queryId = newId()
        mutex.withLock { collecting[queryId] = Collected() }
        try {
            val answer = client.requestInOrder(
                iq("set") {
                    element("query", ImNs.MAM) {
                        attribute("queryid", queryId)
                        element("x", ImNs.DATA_FORMS) {
                            attribute("type", "submit")
                            field("FORM_TYPE", ImNs.MAM, hidden = true)
                            with?.let { field("with", it.toString()) }
                        }
                        element("set", ImNs.RSM) {
                            element("max") { text(max.toString()) }
                            when {
                                after != null -> element("after") { text(after) }
                                // An empty <before/> asks for the last page (XEP-0059 §2.5).
                                else -> element("before") { before?.let { text(it) } }
                            }
                        }
                    }
                },
            ).requireResult()
            val fin = answer.child("fin", ImNs.MAM)
            val set = fin?.child("set", ImNs.RSM)
            val collected = mutex.withLock { collecting[queryId] }
            return HistoryPage(
                messages = collected?.messages?.toList().orEmpty(),
                markers = collected?.markers?.toList().orEmpty(),
                first = set?.child("first", ImNs.RSM)?.text,
                last = set?.child("last", ImNs.RSM)?.text,
                complete = fin?.attribute("complete") == "true",
            )
        } finally {
            mutex.withLock { collecting.remove(queryId) }
        }
    }

    override suspend fun onStanza(stanza: Element): Boolean {
        if (stanza.name != "message") return false
        val result = stanza.child("result", ImNs.MAM) ?: return false
        val queryId = result.attribute("queryid") ?: return true
        val me = own
        // Results for our queries come from our own archive; anything else is forged.
        if (!fromOwnAccount(stanza, me)) return true
        val forwarded = result.child("forwarded", ImNs.FORWARD) ?: return true
        val inner = forwarded.child("message", ImNs.CLIENT) ?: return true
        val outgoing = me != null && bareOf(inner.attribute("from")) == me
        val peer = bareOf(if (outgoing) inner.attribute("to") else inner.attribute("from")) ?: return true
        if (inner.child("body", ImNs.CLIENT) == null) {
            markerOf(inner, peer, outgoing)?.let { marker -> mutex.withLock { collecting[queryId]?.markers?.add(marker) } }
            return true
        }
        val message = chatMessageOf(
            inner,
            peer,
            outgoing,
            carbon = false,
            delay = forwarded.child("delay", ImNs.DELAY)?.attribute("stamp"),
            serverId = result.attribute("id"),
            own = me,
        ) ?: return true
        mutex.withLock { collecting[queryId]?.messages?.add(message) }
        return true
    }

    private fun markerOf(message: Element, peer: BareJid, outgoing: Boolean): ArchivedMarker? {
        if (message.attribute("type") == "groupchat" || message.attribute("type") == "error") return null
        message.child("received", ImNs.RECEIPTS)?.attribute("id")?.let { return ArchivedMarker(peer, it, displayed = false, outgoing) }
        val (name, id) = Messaging.markerOf(message) ?: return null
        return ArchivedMarker(peer, id, displayed = name != "received", outgoing)
    }

    private fun io.github.usmandurrany.tether.xml.ElementBuilder.field(name: String, value: String, hidden: Boolean = false) {
        element("field") {
            attribute("var", name)
            if (hidden) attribute("type", "hidden")
            element("value") { text(value) }
        }
    }
}
