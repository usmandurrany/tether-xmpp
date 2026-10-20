@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.StanzaSender
import kotlin.time.Clock

/**
 * When a contact was last online (XEP-0012 Last Activity).
 *
 * The contact's server answers for them, and only to people allowed to see
 * their presence, so this needs a presence subscription. Servers that do not
 * keep it (or a contact who keeps it private) answer with an error, and
 * [lastSeen] returns null.
 */
public class LastActivity(private val client: StanzaSender) {
    /**
     * Milliseconds since the epoch when [jid] was last online, or null when
     * the server does not say. For a contact online right now the answer is
     * about now.
     */
    public suspend fun lastSeen(jid: BareJid): Long? {
        val answer = client.request(iq("get", to = jid.toString()) { element("query", ImNs.LAST) }, timeoutMillis = 15_000)
        if (answer.attribute("type") != "result") return null
        if (!fromAddress(answer, jid)) return null
        val seconds = answer.child("query", ImNs.LAST)?.attribute("seconds")?.trim()?.toLongOrNull() ?: return null
        if (seconds < 0) return null
        return Clock.System.now().toEpochMilliseconds() - seconds * 1000
    }

    /** The answer must come from the contact's address, not someone else's. */
    private fun fromAddress(answer: io.github.usmandurrany.tether.xml.Element, jid: BareJid): Boolean =
        bareOf(answer.attribute("from")) == jid
}
