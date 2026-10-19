package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.xml.Element
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HistoryTest {
    private val romeo = BareJid.parse("romeo@example.net")

    /** Plays the archive: result messages through the extension, then the final answer. */
    private fun archive(history: () -> History, from: String = "juliet@example.com"): suspend (Element) -> Element = { iq: Element ->
        val query = iq.child("query", ImNs.MAM)!!
        val qid = query.attribute("queryid")
        val h = history()
        listOf(
            "<message from='$from'><result xmlns='urn:xmpp:mam:2' queryid='$qid' id='a1'><forwarded xmlns='urn:xmpp:forward:0'><delay xmlns='urn:xmpp:delay' stamp='2026-10-01T10:00:00Z'/>" +
                "<message xmlns='jabber:client' from='romeo@example.net/o' to='juliet@example.com/phone' type='chat' id='x'><body>old one</body><request xmlns='urn:xmpp:receipts'/></message></forwarded></result></message>",
            "<message from='$from'><result xmlns='urn:xmpp:mam:2' queryid='$qid' id='a2'><forwarded xmlns='urn:xmpp:forward:0'>" +
                "<message xmlns='jabber:client' from='juliet@example.com/laptop' to='romeo@example.net' type='chat'><body>my reply</body></message></forwarded></result></message>",
        ).forEach { h.onStanza(xml(it)) }
        xml("<iq type='result' id='${iq.attribute("id")}'><fin xmlns='urn:xmpp:mam:2' complete='true'><set xmlns='http://jabber.org/protocol/rsm'><first>a1</first><last>a2</last></set></fin></iq>")
    }

    @Test
    fun fetchesAPageInOrderWithDirections() = runTest {
        lateinit var history: History
        val sender = FakeSender()
        history = History(sender)
        sender.answer = archive({ history })
        history.onSessionStarted(FakeSession(sender, accountFeatures = setOf(ImNs.MAM)))
        assertTrue(history.available.value)
        val page = history.fetch(with = romeo, max = 10)
        assertEquals(listOf("old one", "my reply"), page.messages.map { it.body })
        assertEquals(listOf(Direction.INCOMING, Direction.OUTGOING), page.messages.map { it.direction })
        assertEquals(listOf("a1", "a2"), page.messages.map { it.serverId })
        assertEquals("2026-10-01T10:00:00Z", page.messages[0].delayedAt)
        assertEquals("a1", page.first)
        assertTrue(page.complete)
        assertTrue(sender.sent.isEmpty(), "archived messages never trigger receipts")
        val form = sender.requests.single().child("query", ImNs.MAM)!!.child("x", ImNs.DATA_FORMS)!!
        assertEquals("romeo@example.net", form.children("field", ImNs.DATA_FORMS).first { it.attribute("var") == "with" }.child("value", ImNs.DATA_FORMS)?.text)
    }

    @Test
    fun resultsFromAnotherArchiveAreIgnored() = runTest {
        lateinit var history: History
        val sender = FakeSender()
        history = History(sender)
        sender.answer = archive({ history }, from = "mallory@evil.example")
        history.onSessionStarted(FakeSession(sender, accountFeatures = setOf(ImNs.MAM)))
        assertTrue(history.fetch().messages.isEmpty())
    }

    @Test
    fun noArchiveIsAnError() = runTest {
        val sender = FakeSender { FakeSender.error(it, "feature-not-implemented") }
        val history = History(sender)
        history.onSessionStarted(FakeSession(sender))
        assertEquals(false, history.available.value)
        assertEquals("feature-not-implemented", assertFailsWith<XmppStanzaException> { history.fetch() }.condition)
    }
}
