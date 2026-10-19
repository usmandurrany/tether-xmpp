package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.sasl.SaslConfig
import io.github.usmandurrany.tether.transport.EndpointResolver
import io.github.usmandurrany.tether.transport.JvmSocketTransportFactory
import io.github.usmandurrany.tether.transport.SrvLookup
import io.github.usmandurrany.tether.transport.TlsConfig
import io.github.usmandurrany.tether.transport.TlsVerificationException
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamNegotiatorTest {
    private var server: FakeXmppServer? = null
    private val log = ArrayList<String>()

    @AfterTest
    fun tearDown() {
        server?.close()
    }

    private fun config(
        port: Int,
        password: String = "secret",
        pin: Boolean = true,
        channelBindingPinned: Boolean = false,
        sasl: SaslConfig = SaslConfig(),
    ) = XmppConfig(
        jid = BareJid("juliet", "localhost"),
        password = password,
        resource = "phone",
        host = "localhost",
        port = port,
        tls = TlsConfig(pinnedCertificateSha256 = if (pin) setOf(TestCertificate.sha256Hex) else emptySet()),
        sasl = sasl,
        channelBindingPinned = channelBindingPinned,
        responseTimeoutMillis = 5_000,
    )

    private fun negotiate(fake: FakeXmppServer, config: XmppConfig = config(fake.port)): NegotiatedSession {
        server = fake
        val logger = object : TrafficLogger {
            override fun sent(xml: String) { log += "C: $xml" }
            override fun received(xml: String) { log += "S: $xml" }
        }
        return runBlocking {
            StreamNegotiator(config, JvmSocketTransportFactory(config.tls), EndpointResolver(SrvLookup { emptyList() }), logger).negotiate()
        }.also { it.transport.close() }
    }

    private fun failure(fake: FakeXmppServer, config: XmppConfig = config(fake.port)): XmppNegotiationException =
        assertFailsWith<XmppNegotiationException> { negotiate(fake, config) }

    @Test
    fun fullNegotiationWithStartTlsAndChannelBinding() {
        val fake = FakeXmppServer()
        val session = negotiate(fake)
        assertEquals(FullJid.parse("juliet@localhost/phone"), session.jid)
        assertEquals("SCRAM-SHA-256-PLUS", session.saslMechanism)
        assertEquals("p=tls-server-end-point,,", fake.usedGs2Header)
        assertTrue(session.channelBound)
        assertNotNull(session.scramKeys, "keys for password-less re-login")
        assertTrue(session.tls.pinned)
        assertEquals(TestCertificate.sha256Hex, session.tls.peerCertificateSha256)
        assertNotNull(session.featuresBeforeTls?.child("starttls", "urn:ietf:params:xml:ns:xmpp-tls"))
        // Capabilities come only from what the server advertised.
        assertTrue(session.capabilities.hasStreamFeature("sm", "urn:xmpp:sm:3"))
        assertTrue(session.capabilities.serverSupports("urn:xmpp:carbons:2"))
        assertFalse(session.capabilities.account.available)
        assertTrue(fake.failures.isEmpty(), "server saw: ${fake.failures}")
    }

    @Test
    fun saslPayloadsNeverReachTheLog() {
        negotiate(FakeXmppServer())
        val sasl = log.filter { it.contains("<auth") || it.contains("<response") || it.contains("<challenge") || it.contains("<success") }
        assertEquals(4, sasl.size, log.joinToString("\n"))
        assertTrue(sasl.all { it.contains("[redacted]") })
        assertFalse(log.any { it.contains("secret") })
    }

    @Test
    fun usesFlagYWithoutPlusMechanisms() {
        val fake = FakeXmppServer(mechanisms = listOf("SCRAM-SHA-256"), advertiseChannelBinding = false)
        assertEquals("SCRAM-SHA-256", negotiate(fake).saslMechanism)
        assertEquals("y,,", fake.usedGs2Header)
    }

    @Test
    fun refusesServersWithoutStartTls() {
        val e = failure(FakeXmppServer(offerStartTls = false))
        assertEquals(NegotiationFailure.TLS_UNAVAILABLE, e.failure)
        assertFalse(e.failure.retryable)
    }

    @Test
    fun rejectsUntrustedCertificateAndReportsItsFingerprint() {
        val fake = FakeXmppServer()
        val e = failure(fake, config(fake.port, pin = false))
        assertEquals(NegotiationFailure.TLS_VERIFICATION, e.failure)
        val tls = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<TlsVerificationException>().first()
        assertEquals(TestCertificate.sha256Hex, tls.peerCertificateSha256)
    }

    @Test
    fun rejectsWrongPin() {
        val fake = FakeXmppServer()
        val wrongPin = config(fake.port).let {
            XmppConfig(it.jid, it.password, host = it.host, port = it.port, tls = TlsConfig(setOf("0".repeat(64))), responseTimeoutMillis = 5_000)
        }
        assertEquals(NegotiationFailure.TLS_VERIFICATION, failure(fake, wrongPin).failure)
    }

    @Test
    fun wrongPasswordFailsAuthentication() {
        val fake = FakeXmppServer(password = "other")
        assertEquals(NegotiationFailure.AUTHENTICATION, failure(fake).failure)
    }

    @Test
    fun serverRejectionIsReported() {
        val e = failure(FakeXmppServer(rejectLogin = true))
        assertEquals(NegotiationFailure.AUTHENTICATION, e.failure)
        assertTrue(e.message!!.contains("not-authorized"))
    }

    @Test
    fun pinnedChannelBindingStopsDowngrade() {
        val fake = FakeXmppServer(mechanisms = listOf("SCRAM-SHA-256"), advertiseChannelBinding = false)
        val e = failure(fake, config(fake.port, channelBindingPinned = true))
        assertEquals(NegotiationFailure.AUTHENTICATION, e.failure)
        assertTrue(e.message!!.contains("DOWNGRADE_DETECTED"))
    }

    @Test
    fun streamErrorsAreSurfacedWithTheirCondition() {
        val e = failure(FakeXmppServer(streamErrorAfterAuth = "system-shutdown"))
        assertEquals(NegotiationFailure.STREAM_ERROR, e.failure)
        assertEquals("system-shutdown", e.streamError)
    }

    @Test
    fun establishesLegacySessionWhenRequired() {
        assertEquals("juliet@localhost/phone", negotiate(FakeXmppServer(requireSession = true)).jid.toString())
    }

    @Test
    fun rejectsBindToAnotherAccount() {
        assertEquals(NegotiationFailure.PROTOCOL, failure(FakeXmppServer(bindJid = "mallory@localhost/x")).failure)
    }

    @Test
    fun unreachableEndpointIsRetryable() {
        val port = java.net.ServerSocket(0).use { it.localPort } // closed again: nothing listens
        val e = assertFailsWith<XmppNegotiationException> {
            runBlocking {
                val c = config(port)
                StreamNegotiator(c, JvmSocketTransportFactory(c.tls), EndpointResolver(SrvLookup { emptyList() })).negotiate()
            }
        }
        assertEquals(NegotiationFailure.UNREACHABLE, e.failure)
        assertTrue(e.failure.retryable)
        assertNull(server)
    }

    @Test
    fun silentServerTimesOutInsteadOfHanging() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { silent ->
            val accepted = kotlin.concurrent.thread(isDaemon = true) { runCatching { silent.accept().use { Thread.sleep(10_000) } } }
            val c = XmppConfig(BareJid("juliet", "localhost"), "secret", host = "localhost", port = silent.localPort, responseTimeoutMillis = 1_000)
            val started = System.nanoTime()
            val e = assertFailsWith<XmppNegotiationException> {
                runBlocking { StreamNegotiator(c, JvmSocketTransportFactory(c.tls), EndpointResolver(SrvLookup { emptyList() })).negotiate() }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(NegotiationFailure.CONNECTION_LOST, e.failure)
            assertTrue(elapsedMs < 4_000, "took $elapsedMs ms")
            accepted.interrupt()
        }
    }
}
