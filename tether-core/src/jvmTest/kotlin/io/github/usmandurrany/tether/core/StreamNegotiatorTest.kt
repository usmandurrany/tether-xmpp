package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.sasl.FastToken
import io.github.usmandurrany.tether.sasl.SaslConfig
import io.github.usmandurrany.tether.transport.ConscryptTlsProvider
import io.github.usmandurrany.tether.transport.EndpointResolver
import io.github.usmandurrany.tether.transport.JvmSocketTransportFactory
import io.github.usmandurrany.tether.transport.PlatformTlsProvider
import io.github.usmandurrany.tether.transport.SrvLookup
import io.github.usmandurrany.tether.transport.TlsConfig
import io.github.usmandurrany.tether.transport.TlsProvider
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
    private val agent = UserAgent(device = "Test phone")

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
        fastToken: FastToken? = null,
        userAgent: UserAgent = agent,
    ) = XmppConfig(
        jid = BareJid("juliet", "localhost"),
        password = password.ifEmpty { null },
        fastToken = fastToken,
        userAgent = userAgent,
        resource = "phone",
        host = "localhost",
        port = port,
        tls = TlsConfig(pinnedCertificateSha256 = if (pin) setOf(TestCertificate.sha256Hex) else emptySet()),
        sasl = sasl,
        channelBindingPinned = channelBindingPinned,
        responseTimeoutMillis = 5_000,
    )

    private fun negotiate(
        fake: FakeXmppServer,
        config: XmppConfig = config(fake.port),
        tlsProvider: TlsProvider = PlatformTlsProvider,
    ): NegotiatedSession {
        server = fake
        val logger = object : TrafficLogger {
            override fun sent(xml: String) { log += "C: $xml" }
            override fun received(xml: String) { log += "S: $xml" }
        }
        return negotiateWith(StreamNegotiator(config, JvmSocketTransportFactory(config.tls, tlsProvider), EndpointResolver(SrvLookup { emptyList() }), logger))
    }

    private fun negotiator(fake: FakeXmppServer, config: XmppConfig = config(fake.port)): StreamNegotiator {
        server = fake
        val logger = object : TrafficLogger {
            override fun sent(xml: String) { log += "C: $xml" }
            override fun received(xml: String) { log += "S: $xml" }
        }
        return StreamNegotiator(config, JvmSocketTransportFactory(config.tls), EndpointResolver(SrvLookup { emptyList() }), logger)
    }

    private fun negotiateWith(negotiator: StreamNegotiator): NegotiatedSession =
        runBlocking { negotiator.negotiate() }.also { it.transport.close() }

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
        assertTrue(session.capabilities.hasStreamFeature("csi", "urn:xmpp:csi:0"))
        assertFalse(session.capabilities.hasStreamFeature("sm", "urn:xmpp:sm:3"))
        assertNull(session.streamManagement, "SM is never enabled unless advertised")
        assertTrue(session.capabilities.serverSupports("urn:xmpp:carbons:2"))
        assertFalse(session.capabilities.account.available)
        assertTrue(fake.failures.isEmpty(), "server saw: ${fake.failures}")
    }

    @Test
    fun conscryptBindsToTheTlsExporter() {
        val fake = FakeXmppServer(conscrypt = true)
        val session = negotiate(fake, tlsProvider = ConscryptTlsProvider())
        assertEquals("TLSv1.3", session.tls.protocol)
        assertEquals("SCRAM-SHA-256-PLUS", session.saslMechanism)
        assertEquals("p=tls-exporter,,", fake.usedGs2Header, "RFC 9266 binding is preferred over tls-server-end-point")
        assertTrue(session.channelBound)
        assertTrue(fake.failures.isEmpty(), "server saw: ${fake.failures}")
    }

    @Test
    fun theJdkFallsBackToTlsServerEndPointWhenTheServerOffersBoth() {
        val fake = FakeXmppServer(conscrypt = true)
        negotiate(fake)
        assertEquals("p=tls-server-end-point,,", fake.usedGs2Header)
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

    // SASL2 (XEP-0388) and FAST (XEP-0484)

    private fun fastServer(vararg mechanisms: String = arrayOf("HT-SHA-256-NONE", "HT-SHA-256-ENDP")) =
        FakeXmppServer(sasl2 = true, fastMechanisms = mechanisms.toList())

    @Test
    fun sasl2LoginAsksForAFastToken() {
        val fake = fastServer()
        val negotiator = negotiator(fake)
        val saved = ArrayList<FastToken?>()
        negotiator.onFastTokenChanged = { saved += it }
        val session = negotiateWith(negotiator)

        assertEquals("SCRAM-SHA-256-PLUS", session.saslMechanism)
        assertEquals(FullJid.parse("juliet@localhost/phone"), session.jid)
        assertNotNull(session.scramKeys)
        val token = assertNotNull(negotiator.fastToken)
        assertEquals("HT-SHA-256-ENDP", token.mechanism, "channel-bound token preferred")
        assertEquals("2030-01-01T00:00:00Z", token.expiry)
        assertEquals(listOf<FastToken?>(token), saved)
        val auth = fake.authenticates.single()
        assertEquals(agent.id, auth.child("user-agent")?.attribute("id"))
        assertEquals("Test phone", auth.child("user-agent")?.child("device")?.text)
        assertEquals("juliet@localhost", fake.lastStreamFrom)
        assertTrue(fake.failures.isEmpty(), "server saw: ${fake.failures}")
    }

    @Test
    fun theNextLoginUsesTheTokenInOneRoundTrip() {
        val fake = fastServer()
        val negotiator = negotiator(fake)
        negotiateWith(negotiator)
        val session = negotiateWith(negotiator)

        assertEquals("HT-SHA-256-ENDP", session.saslMechanism)
        assertTrue(session.channelBound)
        assertNull(session.scramKeys)
        assertEquals(1, fake.fastLogins)
        assertNotNull(fake.authenticates.last().child("fast", "urn:xmpp:fast:0"))
        assertEquals(FullJid.parse("juliet@localhost/phone"), session.jid)
        assertTrue(fake.failures.isEmpty(), "server saw: ${fake.failures}")
    }

    @Test
    fun aSavedTokenLogsInWithoutThePassword() {
        val fake = fastServer()
        val first = negotiator(fake)
        negotiateWith(first)
        val token = first.fastToken!!

        val session = negotiateWith(negotiator(fake, config(fake.port, password = "", fastToken = token)))
        assertEquals("HT-SHA-256-ENDP", session.saslMechanism)
        assertEquals(1, fake.fastLogins)
    }

    @Test
    fun aTokenOnlyWorksForTheInstallationItWasIssuedTo() {
        val fake = fastServer()
        val first = negotiator(fake)
        negotiateWith(first)
        val other = negotiator(fake, config(fake.port, fastToken = first.fastToken, userAgent = UserAgent()))
        assertEquals("SCRAM-SHA-256-PLUS", negotiateWith(other).saslMechanism, "refused, so the password is used")
        assertEquals(0, fake.fastLogins)
    }

    @Test
    fun aRefusedTokenFallsBackToThePasswordAndGetsANewOne() {
        val fake = fastServer()
        val negotiator = negotiator(fake)
        negotiateWith(negotiator)
        val old = negotiator.fastToken!!
        val saved = ArrayList<FastToken?>()
        negotiator.onFastTokenChanged = { saved += it }
        fake.rejectFast = true

        val session = negotiateWith(negotiator)
        assertEquals("SCRAM-SHA-256-PLUS", session.saslMechanism)
        assertEquals(2, fake.connections, "fell back on the same connection")
        val new = assertNotNull(negotiator.fastToken)
        assertTrue(new.token != old.token)
        assertEquals(listOf(null, new), saved)
        assertTrue(fake.failures.isEmpty(), "server saw: ${fake.failures}")
    }

    @Test
    fun aRefusedTokenWithoutPasswordAsksForThePassword() {
        val fake = fastServer()
        val first = negotiator(fake)
        negotiateWith(first)
        fake.rejectFast = true
        val negotiator = negotiator(fake, config(fake.port, password = "", fastToken = first.fastToken))
        val saved = ArrayList<FastToken?>()
        negotiator.onFastTokenChanged = { saved += it }

        val e = assertFailsWith<XmppNegotiationException> { negotiateWith(negotiator) }
        assertEquals(NegotiationFailure.AUTHENTICATION, e.failure)
        assertNull(negotiator.fastToken)
        assertEquals(listOf<FastToken?>(null), saved, "the dead token is forgotten")
    }

    @Test
    fun aRotatedTokenReplacesTheOldOne() {
        val fake = fastServer()
        val negotiator = negotiator(fake)
        negotiateWith(negotiator)
        val old = negotiator.fastToken!!
        fake.rotateTokens = true
        negotiateWith(negotiator)
        val rotated = negotiator.fastToken!!
        assertTrue(rotated.token != old.token)
        assertEquals("HT-SHA-256-ENDP", rotated.mechanism)
        assertEquals("HT-SHA-256-ENDP", negotiateWith(negotiator).saslMechanism)
        assertEquals(2, fake.fastLogins)
    }

    @Test
    fun withoutChannelBindingVariantsTheUnboundTokenIsUsed() {
        val fake = fastServer("HT-SHA-256-NONE")
        val negotiator = negotiator(fake)
        negotiateWith(negotiator)
        assertEquals("HT-SHA-256-NONE", negotiator.fastToken?.mechanism)
        assertEquals("HT-SHA-256-NONE", negotiateWith(negotiator).saslMechanism)
    }

    @Test
    fun pinnedChannelBindingNeverUsesAnUnboundToken() {
        val fake = fastServer("HT-SHA-256-NONE")
        val negotiator = negotiator(fake, config(fake.port, channelBindingPinned = true))
        assertEquals("SCRAM-SHA-256-PLUS", negotiateWith(negotiator).saslMechanism)
        assertNull(negotiator.fastToken)
        assertNull(fake.authenticates.single().child("request-token", "urn:xmpp:fast:0"))
    }

    @Test
    fun sasl2WithoutFastStillLogsIn() {
        val fake = FakeXmppServer(sasl2 = true)
        val negotiator = negotiator(fake)
        val session = negotiateWith(negotiator)
        assertEquals("SCRAM-SHA-256-PLUS", session.saslMechanism)
        assertNull(negotiator.fastToken)
        assertNull(fake.authenticates.single().child("request-token", "urn:xmpp:fast:0"))
        assertTrue(fake.failures.isEmpty(), "server saw: ${fake.failures}")
    }

    @Test
    fun sasl2CanBeTurnedOff() {
        val fake = fastServer()
        val negotiator = negotiator(fake, config(fake.port, sasl = SaslConfig(useSasl2 = false)))
        assertEquals("SCRAM-SHA-256-PLUS", negotiateWith(negotiator).saslMechanism)
        assertTrue(fake.authenticates.isEmpty())
        assertNull(negotiator.fastToken)
    }

    @Test
    fun fastCanBeTurnedOff() {
        val fake = fastServer()
        val negotiator = negotiator(fake, config(fake.port, sasl = SaslConfig(useFast = false)))
        negotiateWith(negotiator)
        assertEquals(1, fake.authenticates.size)
        assertNull(negotiator.fastToken)
    }

    @Test
    fun sasl2RejectionIsReported() {
        val e = failure(FakeXmppServer(sasl2 = true, rejectLogin = true))
        assertEquals(NegotiationFailure.AUTHENTICATION, e.failure)
        assertTrue(e.message!!.contains("not-authorized"), e.message)
    }

    @Test
    fun tokensAndSasl2PayloadsNeverReachTheLog() {
        val fake = fastServer()
        val negotiator = negotiator(fake)
        negotiateWith(negotiator)
        negotiateWith(negotiator)
        val token = negotiator.fastToken!!.token
        assertTrue(log.any { it.contains("<authenticate") })
        assertFalse(log.any { it.contains(token) || it.contains("secret") }, log.joinToString("\n"))
        assertTrue(log.filter { it.contains("urn:xmpp:sasl:2") && !it.contains("features") }.all { it.contains("[redacted]") })
    }
}
