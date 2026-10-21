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

    private fun client(
        fake: FakeXmppServer,
        policy: ClientPolicy = ClientPolicy(),
        store: KeyValueStore? = null,
        credentials: CredentialVault? = null,
        clientScope: CoroutineScope = scope,
    ): XmppClient {
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
        return XmppClient(negotiator, clientScope, policy, store = store, credentials = credentials)
    }

    private fun message(body: String, id: String? = null) = element("message", Ns.CLIENT) {
        attribute("to", "romeo@localhost")
        attribute("id", id)
        element("body") { text(body) }
    }

    private fun FakeXmppServer.nextFromClient(): Element =
        assertNotNull(fromClient.poll(5, TimeUnit.SECONDS), "server received nothing")

    /** The next message, presence or iq, skipping CSI reports (a resumed session always sends one). */
    private fun FakeXmppServer.nextStanzaFromClient(timeoutMillis: Long = 5_000): Element? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val next = fromClient.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (next.namespace != Ns.CSI) return next
        }
    }

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
    fun anOrderedRequestReturnsAfterTheStanzasThatPrecededItsAnswer() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val client = client(fake)
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        client.install(object : ClientExtension {
            override suspend fun onStanza(stanza: Element): Boolean {
                val body = stanza.child("body")?.text ?: return false
                delay(20) // a slow extension: the answer must still wait for it
                seen += body
                return true
            }
        })
        withTimeout(10_000) { client.connect() }
        seen.clear()
        fake.beforeAccountAnswer = (1..5).map { "<message from='juliet@localhost'><body>r$it</body></message>" }
        val query = element("iq", Ns.CLIENT) {
            attribute("type", "set")
            attribute("to", "juliet@localhost")
            element("query", "urn:xmpp:mam:2")
        }
        withTimeout(10_000) { client.requestInOrder(query) }
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), seen.toList().filter { it != "welcome" })
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
    fun aResumedSessionIsToldTheCurrentActivity() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val client = client(fake, ClientPolicy(reconnect = ReconnectPolicy(backgroundMode = BackgroundMode.STAY_CONNECTED, stableSessionMillis = 0)))
        withTimeout(10_000) { client.connect() }
        client.setForeground(false)
        assertEquals("inactive", fake.nextFromClient().name)
        // The app comes back while the connection is down: the server still has "inactive".
        fake.dropConnection()
        withTimeout(10_000) { while (client.state.value is ConnectionState.Online) delay(5) }
        client.setForeground(true)
        withTimeout(10_000) { while (client.state.value !is ConnectionState.Online) delay(20) }
        assertEquals(1, fake.resumes)
        val names = generateSequence { fake.fromClient.poll(2, TimeUnit.SECONDS) }.map { it.name }.take(5).toList()
        assertTrue("active" in names, "sent after resuming: $names")
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
        val ids = List(2) { fake.nextStanzaFromClient()?.attribute("id") }
        assertEquals(listOf("b", "d"), ids, "the lost one is re-sent first, then the new one, each once")
        withTimeout(10_000) { while (client.state.value !is ConnectionState.Online) delay(20) }
        assertEquals(null, fake.nextStanzaFromClient(300))
    }

    @Test
    fun aRestartedAppResumesTheSavedSessionAndResendsWhatWasLost() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true, silent = true)
        val store = MemoryKeyValueStore()
        val firstRun = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = client(fake, store = store, clientScope = firstRun)
        withTimeout(10_000) { first.connect() }
        first.send(message("one", id = "s1"))
        first.send(message("two", id = "s2"))
        repeat(2) { fake.nextFromClient() }
        first.save()
        // The process dies: no goodbye, the socket just goes.
        firstRun.cancel()
        fake.lostInTransit = 1
        fake.silent = false
        fake.dropConnection()

        val second = client(fake, store = store)
        withTimeout(10_000) { second.connect() }
        assertEquals(1, fake.resumes, "resumed from the saved state, no new login")
        assertEquals("s2", fake.nextStanzaFromClient()?.attribute("id"), "only what the server never got is sent again")
        assertEquals(null, fake.nextStanzaFromClient(300))
    }

    @Test
    fun inTheForegroundStateIsWrittenOnlyWhenItMatters() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        var writes = 0
        val store = object : KeyValueStore by MemoryKeyValueStore() {
            override suspend fun put(key: String, value: String?) { if (key.startsWith("tether.state.")) writes++ }
        }
        val client = client(fake, store = store)
        withTimeout(10_000) { client.connect() }
        delay(800)
        val afterConnect = writes
        assertTrue(afterConnect >= 1, "a new session is written")

        // Typing, receipts and acks while the app is in use stay in memory.
        repeat(20) {
            client.send(element("message", Ns.CLIENT) {
                attribute("to", "romeo@localhost")
                element("composing", "http://jabber.org/protocol/chatstates")
            })
        }
        delay(800)
        assertEquals(afterConnect, writes, "no writes for stanzas without a body")

        // A message the user wrote is written, so a crash before the server confirms it loses nothing.
        client.send(message("hello", id = "m1"))
        delay(800)
        assertEquals(afterConnect + 1, writes)

        // Going to the background writes at once.
        client.setForeground(false)
        delay(200)
        assertEquals(afterConnect + 2, writes)
    }

    @Test
    fun theOutboxSurvivesARestart() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val store = MemoryKeyValueStore()
        val first = client(fake, store = store, clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        first.send(message("queued offline", id = "q1"))
        first.save()
        first.close() // the app is killed before it ever connected
        val second = client(fake, store = store)
        withTimeout(10_000) { second.connect() }
        val ids = generateSequence { fake.fromClient.poll(1, TimeUnit.SECONDS) }.map { it.attribute("id") }.toList()
        assertTrue("q1" in ids, "the queued message was delivered: $ids")
    }

    @Test
    fun loginKeysAreSavedForPasswordlessLogins() = runBlocking<Unit> {
        val fake = FakeXmppServer(streamManagement = true)
        val vault = CredentialVault(MemoryKeyValueStore())
        val client = client(fake, credentials = vault)
        withTimeout(10_000) { client.connect() }
        assertEquals(1, vault.loadKeys(BareJid.parse("juliet@localhost")).size)
    }
}
