package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.transport.EndpointResolver
import io.github.usmandurrany.tether.transport.JvmSocketTransportFactory
import io.github.usmandurrany.tether.transport.SrvLookup
import io.github.usmandurrany.tether.transport.TlsConfig
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live sessions and XEP-0198 against the scripted server, over real TLS sockets. */
class XmppSessionTest {
    private var server: FakeXmppServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server?.close()
    }

    private fun opener(fake: FakeXmppServer): XmppSessionOpener {
        server = fake
        val config = XmppConfig(
            jid = BareJid("juliet", "localhost"),
            password = "secret",
            resource = "phone",
            host = "localhost",
            port = fake.port,
            tls = TlsConfig(pinnedCertificateSha256 = setOf(TestCertificate.sha256Hex)),
            responseTimeoutMillis = 5_000,
        )
        val negotiator = StreamNegotiator(config, JvmSocketTransportFactory(config.tls), EndpointResolver(SrvLookup { emptyList() }))
        return XmppSessionOpener(negotiator)
    }

    /** Opens a session and starts its read loop, as the supervisor does. */
    private suspend fun XmppSessionOpener.live(): XmppSession = open().also { s -> scope.launch { s.awaitClosed() } }

    private fun message(body: String, id: String? = null) = element("message", Ns.CLIENT) {
        attribute("to", "romeo@localhost")
        attribute("id", id)
        element("body") { text(body) }
    }

    private fun FakeXmppServer.nextFromClient(): Element =
        assertNotNull(fromClient.poll(5, TimeUnit.SECONDS), "server received nothing")

    @Test
    fun streamManagementIsEnabledAndAcksClearTheQueue() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val session = opener(fake).live()
        val sm = assertNotNull(session.streamManagement)
        assertTrue(sm.canResume)
        assertFalse(session.resumed)

        repeat(3) { session.send(message("hi $it")) }
        repeat(3) { assertEquals("hi $it", fake.nextFromClient().child("body")?.text) }
        assertTrue(withTimeout(5_000) { session.probe() })
        assertEquals(3, sm.acknowledged)
        assertTrue(sm.pending.isEmpty())
        assertTrue(fake.failures.isEmpty(), "server saw: ${fake.failures}")
    }

    @Test
    fun everySentStanzaGetsAnId() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val session = opener(fake).live()
        session.send(message("no id"))
        assertNotNull(fake.nextFromClient().attribute("id"))
    }

    @Test
    fun answersTheServersAckRequestWithItsCount() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val session = opener(fake).live()
        // The welcome message came during negotiation, before SM was on: not counted.
        assertEquals("welcome", withTimeout(5_000) { session.incoming.first() }.child("body")?.text)
        fake.push("<message from='romeo@localhost'><body>one</body></message>")
        fake.push("<message from='romeo@localhost'><body>two</body></message>")
        fake.push("<r xmlns='urn:xmpp:sm:3'/>")
        assertEquals(2L, fake.clientAcks.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun resumesAfterADroppedConnectionAndResendsWhatWasLost() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true, silent = true)
        val opener = opener(fake)
        val first = opener.live()
        repeat(3) { first.send(message("m$it", id = "id-$it")) }
        repeat(3) { fake.nextFromClient() }
        assertEquals(3, first.streamManagement!!.pending.size, "nothing confirmed yet")

        // The link dies, and the last two messages never reached the server.
        fake.lostInTransit = 2
        fake.silent = false
        fake.dropConnection()
        withTimeout(5_000) { first.awaitClosed() }

        val second = opener.live()
        assertTrue(second.resumed)
        assertEquals(1, fake.resumes)
        assertEquals(first.jid, second.jid)
        // Exactly the lost ones are sent again, with their original ids.
        assertEquals(listOf("id-1", "id-2"), List(2) { fake.nextFromClient().attribute("id") })
        assertTrue(withTimeout(5_000) { second.probe() })
        assertTrue(second.streamManagement!!.pending.isEmpty())
        assertNull(fake.fromClient.poll(300, TimeUnit.MILLISECONDS), "no duplicates of confirmed stanzas")
    }

    @Test
    fun failedResumptionBindsFreshAndStillDeliversUnconfirmedStanzas() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true, silent = true)
        val opener = opener(fake)
        val first = opener.live()
        repeat(2) { first.send(message("m$it", id = "id-$it")) }
        repeat(2) { fake.nextFromClient() }

        fake.allowResume = false
        fake.lostInTransit = 1
        fake.silent = false
        fake.dropConnection()
        withTimeout(5_000) { first.awaitClosed() }

        val second = opener.live()
        assertFalse(second.resumed)
        assertEquals(0, fake.resumes)
        assertNotNull(second.streamManagement, "SM is enabled again on the fresh session")
        assertEquals("id-1", fake.nextFromClient().attribute("id"))
        assertNull(fake.fromClient.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun cancellingTheReadLoopStillLetsTheNextOpenResumeQuickly() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val opener = opener(fake)
        val first = opener.open()
        val loop = scope.launch { first.awaitClosed() }
        kotlinx.coroutines.delay(200)
        // What the supervisor does when a probe fails: cancel the watcher, close the session.
        loop.cancel()
        first.close()
        val started = System.nanoTime()
        val second = opener.live()
        assertTrue(second.resumed)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "the opener must not wait out its 5 s guard")
    }

    @Test
    fun withoutStreamManagementTheProbeIsAPing() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = false)
        val session = opener(fake).live()
        assertNull(session.streamManagement)
        assertTrue(withTimeout(5_000) { session.probe() })
    }

    @Test
    fun aSilentServerNeverPassesTheProbe() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val session = opener(fake).live()
        fake.silent = true
        assertNull(withTimeoutOrNull(1_000) { session.probe() })
    }

    @Test
    fun answersServerPings() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        opener(fake).live()
        fake.push("<iq type='get' id='ping-1' from='localhost'><ping xmlns='urn:xmpp:ping'/></iq>")
        val answer = fake.nextFromClient()
        assertEquals("result", answer.attribute("type"))
        assertEquals("ping-1", answer.attribute("id"))
    }

    @Test
    fun requestReturnsTheMatchingAnswer() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val session = opener(fake).live()
        val answer = session.request(
            element("iq", Ns.CLIENT) {
                attribute("type", "get")
                attribute("to", "juliet@localhost")
                element("query", Ns.DISCO_INFO)
            },
        )
        assertEquals("error", answer.attribute("type"))
    }

    @Test
    fun supervisorReconnectsByResumingAfterADrop() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val opener = opener(fake)
        val supervisor = ConnectionSupervisor(scope, opener, ReconnectPolicy(stableSessionMillis = 0))
        assertIs<ConnectResult.Connected>(withTimeout(10_000) { supervisor.connect() })
        fake.dropConnection()
        withTimeout(10_000) {
            while (fake.resumes < 1 || supervisor.state.value !is ConnectionState.Online) kotlinx.coroutines.delay(20)
        }
        assertEquals(2, fake.connections)
        supervisor.disconnect()
        assertTrue(fake.failures.isEmpty(), "server saw: ${fake.failures}")
    }
}
