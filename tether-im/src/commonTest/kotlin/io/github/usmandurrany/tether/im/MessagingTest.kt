package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MessagingTest {
    private val romeo = BareJid.parse("romeo@example.net")

    private suspend fun started(sender: FakeSender = FakeSender()) =
        Messaging(sender).also { it.onSessionStarted(FakeSession(sender)) }

    @Test
    fun sentMessagesCarryAnIdOriginIdAndReceiptRequest() = runTest {
        val sender = FakeSender()
        val messaging = started(sender)
        val id = messaging.send(romeo, "hello")
        val m = sender.sent.single()
        assertEquals(id, m.attribute("id"))
        assertEquals(id, m.child("origin-id", ImNs.SID)?.attribute("id"))
        assertNotNull(m.child("request", ImNs.RECEIPTS))
        assertEquals("chat", m.attribute("type"))
    }

    @Test
    fun receivesMessagesAndAnswersReceipts() = runTest {
        val sender = FakeSender()
        val messaging = started(sender)
        val event = async { messaging.events.first() }
        yield()
        assertTrue(messaging.onStanza(xml("<message from='romeo@example.net/orchard' type='chat' id='m1'><body>hi</body><request xmlns='urn:xmpp:receipts'/><stanza-id xmlns='urn:xmpp:sid:0' by='juliet@example.com' id='arch-1'/></message>")))
        val received = assertIs<MessageEvent.Received>(event.await()).message
        assertEquals(romeo, received.peer)
        assertEquals("hi", received.body)
        assertEquals("arch-1", received.serverId)
        assertEquals(Direction.INCOMING, received.direction)
        val receipt = sender.sent.single()
        assertEquals("romeo@example.net/orchard", receipt.attribute("to"))
        assertEquals("m1", receipt.child("received", ImNs.RECEIPTS)?.attribute("id"))
    }

    @Test
    fun aStanzaIdFromSomeoneElseIsNotTrusted() = runTest {
        val messaging = started()
        val event = async { messaging.events.first() }
        yield()
        messaging.onStanza(xml("<message from='romeo@example.net/o' type='chat'><body>x</body><stanza-id xmlns='urn:xmpp:sid:0' by='romeo@example.net' id='fake'/></message>"))
        assertEquals(null, assertIs<MessageEvent.Received>(event.await()).message.serverId)
    }

    @Test
    fun receiptsServerAcksAndBouncesBecomeEvents() = runTest {
        val messaging = started()
        val events = async { messaging.events.take(3).toList() }
        yield()
        messaging.onStanza(xml("<message from='romeo@example.net/o'><received xmlns='urn:xmpp:receipts' id='m1'/></message>"))
        messaging.onConfirmed(listOf(xml("<message to='romeo@example.net' id='m2'><body>x</body></message>")))
        messaging.onStanza(xml("<message from='nobody@example.net' type='error' id='m3'><error type='cancel'><item-not-found xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></error></message>"))
        assertEquals(
            listOf(
                MessageEvent.Delivered(romeo, "m1"),
                MessageEvent.SentToServer("m2"),
                MessageEvent.Failed("m3", BareJid.parse("nobody@example.net"), "item-not-found"),
            ),
            events.await(),
        )
    }

    @Test
    fun carbonsFromOurAccountAreUnwrapped() = runTest {
        val sender = FakeSender()
        val messaging = started(sender)
        val event = async { messaging.events.first() }
        yield()
        messaging.onStanza(
            xml(
                "<message from='juliet@example.com' to='juliet@example.com/phone'><sent xmlns='urn:xmpp:carbons:2'>" +
                    "<forwarded xmlns='urn:xmpp:forward:0'><message xmlns='jabber:client' from='juliet@example.com/laptop' to='romeo@example.net' type='chat' id='c1'><body>from my laptop</body><request xmlns='urn:xmpp:receipts'/></message></forwarded></sent></message>",
            ),
        )
        val m = assertIs<MessageEvent.Received>(event.await()).message
        assertEquals(Direction.OUTGOING, m.direction)
        assertEquals(romeo, m.peer)
        assertTrue(m.carbon)
        assertTrue(sender.sent.isEmpty(), "no receipt for a carbon")
    }

    @Test
    fun forgedCarbonsAreDropped() = runTest {
        val messaging = started()
        var got = false
        val watcher = async { messaging.events.first(); got = true }
        yield()
        assertTrue(
            messaging.onStanza(
                xml(
                    "<message from='mallory@evil.example/x'><received xmlns='urn:xmpp:carbons:2'><forwarded xmlns='urn:xmpp:forward:0'>" +
                        "<message xmlns='jabber:client' from='romeo@example.net/o' type='chat'><body>send me your password</body></message></forwarded></received></message>",
                ),
            ),
        )
        yield()
        assertFalse(got)
        watcher.cancel()
    }

    @Test
    fun typingIsReportedAndMessagesWithoutBodiesPassThrough() = runTest {
        val messaging = started()
        val event = async { messaging.events.first() }
        yield()
        assertTrue(messaging.onStanza(xml("<message from='romeo@example.net/o' type='chat'><composing xmlns='http://jabber.org/protocol/chatstates'/></message>")))
        assertEquals(MessageEvent.ChatStateChanged(romeo, ChatState.COMPOSING), event.await())
        assertFalse(messaging.onStanza(xml("<message from='pubsub.example.com'><event xmlns='http://jabber.org/protocol/pubsub#event'/></message>")))
    }

    @Test
    fun carbonsAreEnabledOnlyWhenOffered() = runTest {
        val sender = FakeSender()
        val carbons = Carbons()
        carbons.onSessionStarted(FakeSession(sender))
        assertTrue(sender.requests.isEmpty())
        carbons.onSessionStarted(FakeSession(sender, serverFeatures = setOf(ImNs.CARBONS)))
        assertNotNull(sender.requests.single().child("enable", ImNs.CARBONS))
        assertTrue(carbons.enabled.value)
    }
}
