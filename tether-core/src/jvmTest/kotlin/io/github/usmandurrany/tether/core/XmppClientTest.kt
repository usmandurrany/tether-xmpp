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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The client against the scripted server, over real TLS sockets. */
class XmppClientTest {
    private var server: FakeXmppServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server?.close()
    }

    private fun client(fake: FakeXmppServer, policy: ClientPolicy = ClientPolicy()): XmppClient {
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
        return XmppClient(negotiator, scope, policy)
    }

    private fun message(body: String, id: String? = null) = element("message", Ns.CLIENT) {
        attribute("to", "romeo@localhost")
        attribute("id", id)
        element("body") { text(body) }
    }

    private fun FakeXmppServer.nextFromClient(): Element =
        assertNotNull(fromClient.poll(5, TimeUnit.SECONDS), "server received nothing")

    @Test
    fun stanzasSentWhileOfflineGoOutOnTheNextSession() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val client = client(fake)
        client.send(message("written offline", id = "m1"))
        assertEquals(1, client.pendingOutbox().size)
        assertIs<ConnectResult.Connected>(withTimeout(10_000) { client.connect() })
        assertEquals("m1", fake.nextFromClient().attribute("id"))
        assertTrue(client.pendingOutbox().isEmpty())
    }

    @Test
    fun extensionsSetTheSessionUpBeforeItIsReportedOnline() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val client = client(fake)
        val seen = ArrayList<String>()
        client.install(object : ClientExtension {
            override suspend fun onSessionStarted(session: SessionContext) {
                seen += "state=${client.state.value::class.simpleName}"
                val answer = session.request(
                    element("iq", Ns.CLIENT) {
                        attribute("type", "get")
                        attribute("to", "juliet@localhost")
                        element("query", Ns.DISCO_INFO)
                    },
                )
                seen += "answer=${answer.attribute("type")} resumed=${session.resumed}"
            }
        })
        client.install(object : ClientExtension {
            override suspend fun onSessionStarted(session: SessionContext) {
                seen += "second"
            }
        })
        withTimeout(10_000) { client.connect() }
        assertEquals(listOf("state=Connecting", "answer=error resumed=false", "second"), seen)
    }

    @Test
    fun theServersConfirmationsAreReported() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val client = client(fake)
        withTimeout(10_000) { client.connect() }
        val confirmed = async { withTimeout(5_000) { client.confirmed.first() } }
        delay(100) // let the collector subscribe
        client.send(message("hi", id = "acked-1"))
        fake.nextFromClient()
        withTimeout(5_000) { client.session.value!!.probe() }
        assertEquals("acked-1", confirmed.await().attribute("id"))
    }

    @Test
    fun extensionsCanConsumeStanzasBeforeTheApp() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val client = client(fake)
        client.install(object : ClientExtension {
            override suspend fun onStanza(stanza: Element): Boolean = stanza.child("body")?.text == "for the extension"
        })
        val next = async { withTimeout(10_000) { client.stanzas.first { it.child("body")?.text != "welcome" } } }
        delay(100)
        withTimeout(10_000) { client.connect() }
        fake.push("<message from='romeo@localhost'><body>for the extension</body></message>")
        fake.push("<message from='romeo@localhost'><body>for the app</body></message>")
        assertEquals("for the app", next.await().child("body")?.text)
    }

    @Test
    fun reportsActivityWithCsiWhenTheServerOffersIt() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val client = client(fake, ClientPolicy(reconnect = ReconnectPolicy(backgroundMode = BackgroundMode.STAY_CONNECTED)))
        withTimeout(10_000) { client.connect() }
        client.setForeground(false)
        assertEquals("inactive", fake.nextFromClient().name)
        client.setForeground(true)
        assertEquals("active", fake.nextFromClient().name)
    }

    @Test
    fun aDroppedConnectionLosesNothingSentAroundIt() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true, silent = true)
        val client = client(fake, ClientPolicy(reconnect = ReconnectPolicy(stableSessionMillis = 0)))
        withTimeout(10_000) { client.connect() }
        client.send(message("before", id = "b"))
        assertEquals("b", fake.nextFromClient().attribute("id"))
        fake.lostInTransit = 1
        fake.silent = false
        fake.dropConnection()
        client.send(message("during", id = "d")) // the session may or may not have noticed yet
        val ids = List(2) { fake.nextFromClient().attribute("id") }
        assertEquals(listOf("b", "d"), ids, "the lost one is re-sent first, then the new one, each once")
        withTimeout(10_000) { while (client.state.value !is ConnectionState.Online) delay(20) }
        assertEquals(null, fake.fromClient.poll(300, TimeUnit.MILLISECONDS))
    }
}
