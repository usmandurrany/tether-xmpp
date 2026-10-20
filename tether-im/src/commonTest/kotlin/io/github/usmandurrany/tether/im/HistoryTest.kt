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

    @Test
    fun archivedReceiptsAndMarkersComeWithThePage() = runTest {
        lateinit var history: History
        val sender = FakeSender()
        history = History(sender)
        sender.answer = { iq ->
            val qid = iq.child("query", ImNs.MAM)!!.attribute("queryid")
            listOf(
                "<message xmlns='jabber:client' from='romeo@example.net/o' to='juliet@example.com/phone' type='chat'><displayed xmlns='urn:xmpp:chat-markers:0' id='mine-1'/></message>",
                "<message xmlns='jabber:client' from='romeo@example.net/o' to='juliet@example.com/phone'><received xmlns='urn:xmpp:receipts' id='mine-2'/></message>",
                "<message xmlns='jabber:client' from='juliet@example.com/laptop' to='romeo@example.net' type='chat'><displayed xmlns='urn:xmpp:chat-markers:0' id='theirs-1'/></message>",
            ).forEachIndexed { i, inner ->
                history.onStanza(xml("<message from='juliet@example.com'><result xmlns='urn:xmpp:mam:2' queryid='$qid' id='m$i'><forwarded xmlns='urn:xmpp:forward:0'>$inner</forwarded></result></message>"))
            }
            xml("<iq type='result' id='${iq.attribute("id")}'><fin xmlns='urn:xmpp:mam:2' complete='true'/></iq>")
        }
        history.onSessionStarted(FakeSession(sender, accountFeatures = setOf(ImNs.MAM)))
        val page = history.fetch(with = romeo)
        assertTrue(page.messages.isEmpty())
        assertEquals(
            listOf(
                ArchivedMarker(romeo, "mine-1", displayed = true, outgoing = false),
                ArchivedMarker(romeo, "mine-2", displayed = false, outgoing = false),
                ArchivedMarker(romeo, "theirs-1", displayed = true, outgoing = true),
            ),
            page.markers,
        )
    }

    @Test
    fun archivedRetractionsAndTombstonesComeWithThePage() = runTest {
        lateinit var history: History
        val sender = FakeSender()
        history = History(sender)
        sender.answer = { iq ->
            val qid = iq.child("query", ImNs.MAM)!!.attribute("queryid")
            listOf(
                // Romeo deleted his message r1; the retraction carries a fallback body.
                "<message xmlns='jabber:client' from='romeo@example.net/o' to='juliet@example.com/phone' type='chat'><retract xmlns='urn:xmpp:message-retract:1' id='r1'/><fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:message-retract:1'/><body>fallback</body></message>",
                // The server replaced our deleted message m1 with a tombstone.
                "<message xmlns='jabber:client' from='juliet@example.com/laptop' to='romeo@example.net' type='chat' id='m1'><retracted xmlns='urn:xmpp:message-retract:1' stamp='2026-10-20T10:00:00Z'/></message>",
                "<message xmlns='jabber:client' from='romeo@example.net/o' to='juliet@example.com/phone' type='chat' id='r2'><body>kept</body></message>",
            ).forEachIndexed { i, inner ->
                history.onStanza(xml("<message from='juliet@example.com'><result xmlns='urn:xmpp:mam:2' queryid='$qid' id='a$i'><forwarded xmlns='urn:xmpp:forward:0'>$inner</forwarded></result></message>"))
            }
            xml("<iq type='result' id='${iq.attribute("id")}'><fin xmlns='urn:xmpp:mam:2' complete='true'/></iq>")
        }
        history.onSessionStarted(FakeSession(sender, accountFeatures = setOf(ImNs.MAM)))
        val page = history.fetch(with = romeo)
        assertEquals(listOf("kept"), page.messages.map { it.body })
        assertEquals(
            listOf(ArchivedRetraction(romeo, "r1", outgoing = false), ArchivedRetraction(romeo, "m1", outgoing = true)),
            page.retractions,
        )
    }
}
