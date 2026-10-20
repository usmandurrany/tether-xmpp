package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.StreamNegotiator
import io.github.usmandurrany.tether.core.XmppClient
import io.github.usmandurrany.tether.core.XmppConfig
import io.github.usmandurrany.tether.transport.Endpoint
import io.github.usmandurrany.tether.transport.EndpointResolver
import io.github.usmandurrany.tether.transport.XmppTransport
import io.github.usmandurrany.tether.transport.XmppTransportFactory
import io.github.usmandurrany.tether.xml.Element
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TetherChatTest {
    private val romeo = BareJid.parse("romeo@example.net")

    /** A chat whose client never connects: what it sends waits in the outbox, where the test reads it. */
    private class Harness(val chat: TetherChat, val client: XmppClient) {
        suspend fun sent(): List<Element> = client.pendingOutbox()

        suspend fun incoming(xmlText: String) {
            chat.im.messaging.onStanza(xml(xmlText))
        }
    }

    private suspend fun TestScope.harness(features: ChatFeatures = ChatFeatures(), scope: CoroutineScope = backgroundScope): Harness {
        val negotiator = StreamNegotiator(
            XmppConfig(BareJid.parse("juliet@example.com"), password = "x"),
            object : XmppTransportFactory {
                override suspend fun connect(endpoint: Endpoint, serverName: String): XmppTransport = error("offline test")
            },
            EndpointResolver({ emptyList() }),
        )
        val client = XmppClient(negotiator, scope)
        val chat = TetherChat(client, features)
        chat.im.messaging.onSessionStarted(FakeSession(FakeSender()))
        runCurrent()
        return Harness(chat, client)
    }

    @Test
    fun sentMessagesMoveForwardThroughTheirTicks() = runTest {
        val h = harness()
        val m = h.chat.send(romeo, "hi")
        assertEquals(MessageStatus.SENDING, m.status)
        runCurrent()
        val stanza = h.sent().single()
        assertEquals(m.id, stanza.attribute("id"))
        assertNotNull(stanza.child("markable", ImNs.MARKERS))

        // Out of order after a resume: the receipt comes before the server's ack.
        h.incoming("<message from='romeo@example.net/o'><received xmlns='urn:xmpp:receipts' id='${m.id}'/></message>")
        h.chat.im.messaging.onConfirmed(listOf(stanza))
        runCurrent()
        assertEquals(MessageStatus.DELIVERED, h.chat.messages(romeo).value.single().status)

        h.incoming("<message from='romeo@example.net/o' type='chat'><displayed xmlns='urn:xmpp:chat-markers:0' id='${m.id}'/></message>")
        runCurrent()
        assertEquals(MessageStatus.READ, h.chat.messages(romeo).value.single().status)
    }

    @Test
    fun aReadMarkerCoversEarlierMessagesToo() = runTest {
        val h = harness()
        val first = h.chat.send(romeo, "one")
        h.chat.send(romeo, "two")
        val third = h.chat.send(romeo, "three")
        h.incoming("<message from='romeo@example.net/o' type='chat'><displayed xmlns='urn:xmpp:chat-markers:0' id='${third.id}'/></message>")
        runCurrent()
        assertTrue(h.chat.messages(romeo).value.all { it.status == MessageStatus.READ })
        assertEquals(first.id, h.chat.messages(romeo).value.first().id)
    }

    @Test
    fun aMarkerFromSomeoneElseChangesNothing() = runTest {
        val h = harness()
        val m = h.chat.send(romeo, "hi")
        h.incoming("<message from='mallory@evil.example/x' type='chat'><displayed xmlns='urn:xmpp:chat-markers:0' id='${m.id}'/></message>")
        runCurrent()
        assertEquals(MessageStatus.SENDING, h.chat.messages(romeo).value.single().status)
    }

    @Test
    fun anOpenChatIsMarkedReadAndTheContactIsTold() = runTest {
        val h = harness()
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>one</body><markable xmlns='urn:xmpp:chat-markers:0'/></message>")
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r2'><body>two</body><markable xmlns='urn:xmpp:chat-markers:0'/></message>")
        runCurrent()
        assertEquals(2, h.chat.unreadCount.value)
        assertEquals(2, h.chat.conversations.value.single().unreadCount)

        h.chat.openChat(romeo)
        runCurrent()
        assertEquals(0, h.chat.unreadCount.value)
        val marker = h.sent().single { it.child("displayed", ImNs.MARKERS) != null }
        assertEquals("r2", marker.child("displayed", ImNs.MARKERS)?.attribute("id"), "one marker, for the newest")

        // A new message while the chat is open is read at once.
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r3'><body>three</body><markable xmlns='urn:xmpp:chat-markers:0'/></message>")
        runCurrent()
        assertEquals(0, h.chat.unreadCount.value)
        assertEquals("r3", h.sent().last().child("displayed", ImNs.MARKERS)?.attribute("id"))
    }

    @Test
    fun nothingIsMarkedReadInTheBackgroundOrWhenClosed() = runTest {
        val h = harness()
        h.chat.openChat(romeo)
        h.client.setForeground(false)
        runCurrent()
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>one</body><markable xmlns='urn:xmpp:chat-markers:0'/></message>")
        runCurrent()
        assertEquals(1, h.chat.unreadCount.value)
        h.client.setForeground(true)
        runCurrent()
        assertEquals(0, h.chat.unreadCount.value)

        h.chat.closeChat(romeo)
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r2'><body>two</body></message>")
        runCurrent()
        assertEquals(1, h.chat.unreadCount.value)
    }

    @Test
    fun readMarkersCanBeKeptPrivate() = runTest {
        val h = harness(ChatFeatures(sendReadMarkers = false))
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>one</body><markable xmlns='urn:xmpp:chat-markers:0'/></message>")
        h.chat.openChat(romeo)
        runCurrent()
        assertEquals(0, h.chat.unreadCount.value, "still read here")
        assertTrue(h.sent().none { it.child("displayed", ImNs.MARKERS) != null })
    }

    @Test
    fun messagesThatDidNotAskAreNotMarked() = runTest {
        val h = harness()
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>one</body></message>")
        h.chat.openChat(romeo)
        runCurrent()
        assertEquals(0, h.chat.unreadCount.value)
        assertTrue(h.sent().none { it.child("displayed", ImNs.MARKERS) != null })
    }

    @Test
    fun readOnAnotherDeviceClearsUnreadWithoutAMarker() = runTest {
        val h = harness()
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>one</body><markable xmlns='urn:xmpp:chat-markers:0'/></message>")
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r2'><body>two</body><markable xmlns='urn:xmpp:chat-markers:0'/></message>")
        h.incoming(
            "<message from='juliet@example.com' to='juliet@example.com/phone'><sent xmlns='urn:xmpp:carbons:2'>" +
                "<forwarded xmlns='urn:xmpp:forward:0'><message xmlns='jabber:client' to='romeo@example.net' type='chat'><displayed xmlns='urn:xmpp:chat-markers:0' id='r1'/></message></forwarded></sent></message>",
        )
        runCurrent()
        assertEquals(listOf(MessageStatus.READ, MessageStatus.UNREAD), h.chat.messages(romeo).value.map { it.status })
    }

    @Test
    fun theSameMessageTwiceShowsOnce() = runTest {
        val h = harness()
        val live = "<message from='romeo@example.net/o' type='chat' id='r1'><body>hi</body><stanza-id xmlns='urn:xmpp:sid:0' by='juliet@example.com' id='a1'/></message>"
        h.incoming(live)
        h.incoming(live)
        runCurrent()
        assertEquals(1, h.chat.messages(romeo).value.size)
        assertEquals("a1", h.chat.messages(romeo).value.single().serverId)
    }

    @Test
    fun timestampsComeFromTheServerWhenItKeptTheMessage() = runTest {
        val h = harness()
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>late</body><delay xmlns='urn:xmpp:delay' stamp='2026-10-01T10:00:00Z'/></message>")
        runCurrent()
        assertEquals(1790848800000L, h.chat.messages(romeo).value.single().timestamp)
    }

    @Test
    fun typingGoesOutOnceAndPausesWhenIdle() = runTest {
        val h = harness()
        h.chat.typing(romeo)
        runCurrent()
        h.chat.typing(romeo)
        runCurrent()
        suspend fun states() = h.sent().mapNotNull { s -> s.elements.firstOrNull { it.namespace == ImNs.CHAT_STATES }?.name }
        assertEquals(listOf("composing"), states())
        advanceTimeBy(TetherChat.TYPING_PAUSE_MILLIS + 1)
        runCurrent()
        assertEquals(listOf("composing", "paused"), states())
    }

    @Test
    fun sendingEndsTyping() = runTest {
        val h = harness()
        h.chat.typing(romeo)
        h.chat.send(romeo, "done")
        advanceTimeBy(TetherChat.TYPING_PAUSE_MILLIS + 1)
        runCurrent()
        val states = h.sent().mapNotNull { s -> s.elements.firstOrNull { it.namespace == ImNs.CHAT_STATES }?.name }
        assertEquals(listOf("composing", "active"), states, "the message carries <active/>, no pause after it")
    }

    @Test
    fun aContactsTypingClearsOnAMessageOrAfterAWhile() = runTest {
        val h = harness()
        h.incoming("<message from='romeo@example.net/o' type='chat'><composing xmlns='http://jabber.org/protocol/chatstates'/></message>")
        runCurrent()
        assertEquals(setOf(romeo), h.chat.typingContacts.value)
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>hi</body></message>")
        runCurrent()
        assertTrue(h.chat.typingContacts.value.isEmpty())

        h.incoming("<message from='romeo@example.net/o' type='chat'><composing xmlns='http://jabber.org/protocol/chatstates'/></message>")
        runCurrent()
        assertTrue(h.chat.conversations.value.single().typing)
        advanceTimeBy(TetherChat.TYPING_TIMEOUT_MILLIS + 1)
        runCurrent()
        assertTrue(h.chat.typingContacts.value.isEmpty())
    }

    @Test
    fun switchedOffFeaturesStayOff() = runTest {
        val h = harness(ChatFeatures(readMarkers = false, deliveryReceipts = false, typingIndicators = false))
        h.chat.typing(romeo)
        h.chat.send(romeo, "hi")
        runCurrent()
        val m = h.sent().single()
        assertNull(m.child("markable", ImNs.MARKERS))
        assertNull(m.child("request", ImNs.RECEIPTS))
        assertNull(m.child("active", ImNs.CHAT_STATES))
        h.incoming("<message from='romeo@example.net/o' type='chat'><displayed xmlns='urn:xmpp:chat-markers:0' id='${m.attribute("id")}'/></message>")
        runCurrent()
        assertEquals(MessageStatus.SENDING, h.chat.messages(romeo).value.single().status)
    }

    @Test
    fun aBounceFailsTheMessageAndRetrySendsItAgain() = runTest {
        val h = harness()
        val m = h.chat.send(romeo, "hi")
        h.incoming("<message from='romeo@example.net' type='error' id='${m.id}'><error type='cancel'><item-not-found xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></error></message>")
        runCurrent()
        val failed = h.chat.messages(romeo).value.single()
        assertEquals(MessageStatus.FAILED, failed.status)
        assertEquals("item-not-found", failed.error)
        val again = assertNotNull(h.chat.retry(failed))
        runCurrent()
        assertEquals(listOf(again.id), h.chat.messages(romeo).value.map { it.id })
        assertFalse(again.id == m.id)
    }

    @Test
    fun historyMarkersSetReadState() = runTest {
        val h = harness()
        val m = h.chat.send(romeo, "hi")
        h.chat.importPage(
            HistoryPage(
                messages = emptyList(),
                first = null,
                last = null,
                complete = true,
                markers = listOf(ArchivedMarker(romeo, m.id, displayed = true, outgoing = false)),
            ),
            unread = false,
        )
        runCurrent()
        assertEquals(MessageStatus.READ, h.chat.messages(romeo).value.single().status)
    }

    private val linkType = payloadType<String>("link", { put("url", it) }, { string("url") }, namespace = "urn:example:link:0")

    @Test
    fun payloadsGoOutWithTheMessageAndComeBackOnTheOtherSide() = runTest {
        val h = harness()
        val m = h.chat.send(romeo, "Look", linkType.write("https://example.org/a"))
        assertEquals("https://example.org/a", m.payload(linkType))
        runCurrent()
        assertEquals("https://example.org/a", h.sent().single().child("link", "urn:example:link:0")?.child("url")?.text)

        h.incoming(
            "<message from='romeo@example.net/o' type='chat' id='r1'><body>Back</body>" +
                "<link xmlns='urn:example:link:0'><url>https://example.org/b</url></link></message>",
        )
        runCurrent()
        val theirs = h.chat.allMessages.value[romeo].orEmpty().single { !it.outgoing }
        assertEquals("https://example.org/b", theirs.payload(linkType))
    }

    @Test
    fun aRetriedMessageKeepsItsPayloads() = runTest {
        val h = harness()
        val m = h.chat.send(romeo, "Look", linkType.write("https://example.org/a"))
        runCurrent()
        h.incoming("<message from='romeo@example.net' type='error' id='${m.id}'><error type='cancel'><item-not-found xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></error></message>")
        runCurrent()
        val failed = h.chat.allMessages.value[romeo].orEmpty().single()
        assertEquals(MessageStatus.FAILED, failed.status)
        val again = assertNotNull(h.chat.retry(failed))
        assertEquals("https://example.org/a", again.payload(linkType))
    }

    @Test
    fun simpleDataGoesOutAndComesBack() = runTest {
        val h = harness()
        val m = h.chat.send(romeo, "Sunset", MessageData("photo", "url" to "https://img.example/1.jpg", "size" to 48213))
        assertEquals(48213L, m.data("photo")?.long("size"))
        runCurrent()
        assertEquals("https://img.example/1.jpg", h.sent().single().child("photo", MessageData.NAMESPACE)?.child("url")?.text)

        h.incoming(
            "<message from='romeo@example.net/o' type='chat' id='r1'><body>Mine</body>" +
                "<photo xmlns='urn:tether:data:0'><url>https://img.example/2.jpg</url><width>800</width></photo></message>",
        )
        runCurrent()
        val theirs = h.chat.allMessages.value[romeo].orEmpty().single { !it.outgoing }
        assertEquals(800, theirs.data("photo")?.int("width"))
    }

    @Test
    fun deleteForEveryoneEmptiesOurMessageAndTellsTheContact() = runTest {
        val h = harness()
        val m = h.chat.send(romeo, "oops", MessageData("photo", "url" to "u"))
        runCurrent()
        assertTrue(h.chat.deleteForEveryone(m))
        runCurrent()
        val shown = h.chat.allMessages.value[romeo].orEmpty().single()
        assertTrue(shown.deleted)
        assertEquals("", shown.body)
        assertTrue(shown.payloads.isEmpty())
        assertEquals(m.id, h.sent().last().child("retract", ImNs.RETRACT)?.attribute("id"))
    }

    @Test
    fun onlyTheSenderCanDeleteTheirMessage() = runTest {
        val h = harness()
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>one</body></message>")
        runCurrent()
        val theirs = h.chat.allMessages.value[romeo].orEmpty().single()
        assertFalse(h.chat.deleteForEveryone(theirs), "not ours")
        // Someone else's retraction of Romeo's message does nothing.
        h.incoming("<message from='mallory@evil.example/x' type='chat'><retract xmlns='urn:xmpp:message-retract:1' id='r1'/><body>f</body></message>")
        runCurrent()
        assertFalse(h.chat.allMessages.value[romeo].orEmpty().single().deleted)
        // Romeo's own does.
        h.incoming("<message from='romeo@example.net/o' type='chat'><retract xmlns='urn:xmpp:message-retract:1' id='r1'/><body>f</body></message>")
        runCurrent()
        val after = h.chat.allMessages.value[romeo].orEmpty().single()
        assertTrue(after.deleted)
        assertEquals(MessageStatus.READ, after.status, "a deleted message is not unread")
        assertEquals(0, h.chat.unreadCount.value)
    }

    @Test
    fun aDeletionThatArrivesFirstStillApplies() = runTest {
        val h = harness()
        h.chat.importPage(
            HistoryPage(emptyList(), null, null, true, retractions = listOf(ArchivedRetraction(romeo, "r1", outgoing = false))),
            unread = false,
        )
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>late copy</body></message>")
        runCurrent()
        assertTrue(h.chat.allMessages.value[romeo].orEmpty().single().deleted)
    }

    @Test
    fun deletionsCanBeSwitchedOff() = runTest {
        val h = harness(ChatFeatures(deleteForEveryone = false))
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>one</body></message>")
        h.incoming("<message from='romeo@example.net/o' type='chat'><retract xmlns='urn:xmpp:message-retract:1' id='r1'/><body>f</body></message>")
        runCurrent()
        val list = h.chat.allMessages.value[romeo].orEmpty()
        assertEquals(listOf("one"), list.map { it.body }, "kept, and no fallback text shown")
        assertFalse(h.chat.deleteForEveryone(h.chat.send(romeo, "x")))
    }

    @Test
    fun deleteForMeHidesItAndTheArchiveCannotBringItBack() = runTest {
        val h = harness()
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>one</body></message>")
        runCurrent()
        h.chat.deleteForMe(h.chat.allMessages.value[romeo].orEmpty().single())
        runCurrent()
        assertTrue(h.chat.allMessages.value[romeo].orEmpty().isEmpty())
        assertEquals(0, h.chat.unreadCount.value)
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>one</body></message>")
        runCurrent()
        assertTrue(h.chat.allMessages.value[romeo].orEmpty().isEmpty())
        assertTrue(h.chat.conversations.value.isEmpty())
    }

    @Test
    fun aContactWhoGoesOfflineShowsWhenTheyWereLastSeen() = runTest {
        val h = harness()
        h.incoming("<message from='romeo@example.net/o' type='chat' id='r1'><body>hi</body></message>")
        h.chat.im.presences.onStanza(xml("<presence from='romeo@example.net/o'/>"))
        runCurrent()
        assertNull(h.chat.conversations.value.single().lastSeen, "online: no last seen")
        h.chat.im.presences.onStanza(xml("<presence from='romeo@example.net/o' type='unavailable'/>"))
        runCurrent()
        assertNotNull(h.chat.conversations.value.single().lastSeen)
        assertFalse(h.chat.conversations.value.single().online)
    }
}
