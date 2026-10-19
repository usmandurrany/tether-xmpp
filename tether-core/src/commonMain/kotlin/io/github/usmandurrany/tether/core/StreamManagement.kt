package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.xml.Element

/**
 * XEP-0198 counters and the unacknowledged-stanza queue for one SM session.
 * A resumed stream keeps the same instance, so nothing sent is lost across
 * a reconnect.
 *
 * Counters are unsigned 32-bit values that wrap (XEP-0198 §4); they are kept as
 * Long here and compared modulo 2^32.
 *
 * Not thread-safe: the session serialises access.
 */
public class StreamManagement internal constructor(
    /** Server-assigned id, needed to resume. Null when the server did not allow resumption. */
    public val resumptionId: String?,
    /** Where to reconnect to resume (XEP-0198 `location`), if the server named one. */
    public val location: String?,
    /** How long the server keeps the session resumable, in seconds, if it said. */
    public val maxResumeSeconds: Int?,
) {
    /** Stanzas we have received and handled (the `h` we report). */
    public var inbound: Long = 0
        private set

    /** Stanzas the server has confirmed. */
    public var acknowledged: Long = 0
        private set

    private val unacked = ArrayDeque<Element>()

    public val canResume: Boolean get() = resumptionId != null

    /** Stanzas sent but not yet confirmed, oldest first. */
    public val pending: List<Element> get() = unacked.toList()

    internal val sent: Long get() = (acknowledged + unacked.size) and MASK

    internal val unackedCount: Int get() = unacked.size

    /** Sets the counters, for restoring persisted state. Only valid while nothing is queued. */
    internal fun restoreCounters(inbound: Long, acknowledged: Long) {
        check(unacked.isEmpty()) { "Restore before sending" }
        this.inbound = inbound and MASK
        this.acknowledged = acknowledged and MASK
    }

    internal fun onSent(stanza: Element) {
        unacked.addLast(stanza)
    }

    internal fun onInboundStanza() {
        inbound = (inbound + 1) and MASK
    }

    /**
     * Applies `<a h='…'/>` (or the `h` of `<resumed/>`/`<failed/>`).
     * Returns false when the server confirms more than we sent, which XEP-0198 §4
     * treats as a fatal protocol error.
     */
    internal fun onAck(h: Long): Boolean {
        val newlyAcked = (h - acknowledged) and MASK
        if (newlyAcked > unacked.size) return false
        repeat(newlyAcked.toInt()) { unacked.removeFirst() }
        acknowledged = h and MASK
        return true
    }

    /**
     * Takes every unconfirmed stanza out of the queue so it can be sent again,
     * either on the resumed stream (which counts it anew) or on a fresh session.
     */
    internal fun drainPending(): List<Element> {
        val out = unacked.toList()
        unacked.clear()
        return out
    }

    internal companion object {
        const val MASK = 0xFFFF_FFFFL

        fun parseH(value: String?): Long? = value?.toLongOrNull()?.takeIf { it in 0..MASK }

        /** Message, presence and IQ in the client namespace are the stanzas SM counts. */
        fun isStanza(element: Element): Boolean =
            element.namespace == Ns.CLIENT && (element.name == "message" || element.name == "presence" || element.name == "iq")
    }
}
