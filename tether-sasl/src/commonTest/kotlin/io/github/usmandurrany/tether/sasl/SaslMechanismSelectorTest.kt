package io.github.usmandurrany.tether.sasl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SaslMechanismSelectorTest {

    private val creds = SaslCredentials("juliet", "secret")
    private val exporter = ChannelBinding(ChannelBinding.TLS_EXPORTER, ByteArray(32) { 1 })
    private val endpoint = ChannelBinding(ChannelBinding.TLS_SERVER_END_POINT, ByteArray(32) { 2 })
    private val verified = SaslSecurityContext(tlsVerified = true, channelBindings = listOf(exporter, endpoint))
    private val all = listOf("PLAIN", "SCRAM-SHA-1", "SCRAM-SHA-1-PLUS", "SCRAM-SHA-256", "SCRAM-SHA-256-PLUS", "SCRAM-SHA-512", "SCRAM-SHA-512-PLUS")

    private fun pick(offer: SaslOffer, security: SaslSecurityContext = verified, config: SaslConfig = SaslConfig(), credentials: SaslCredentials = creds) =
        SaslMechanismSelector.select(offer, credentials, security, config)

    private fun failure(offer: SaslOffer, security: SaslSecurityContext = verified, config: SaslConfig = SaslConfig()): SaslFailure =
        assertFailsWith<SaslException> { pick(offer, security, config) }.failure

    @Test
    fun prefersStrongestBoundMechanism() {
        assertEquals("SCRAM-SHA-512-PLUS", pick(SaslOffer(all, listOf("tls-exporter"))).name)
        assertEquals("SCRAM-SHA-256-PLUS", pick(SaslOffer(listOf("SCRAM-SHA-256", "SCRAM-SHA-256-PLUS"))).name)
    }

    @Test
    fun usesSharedChannelBindingType() {
        val m = pick(SaslOffer(listOf("SCRAM-SHA-256-PLUS", "SCRAM-SHA-256"), listOf("tls-server-end-point")))
        assertEquals("SCRAM-SHA-256-PLUS", m.name)
        m.runAs(ScramTestServer(ScramAlgorithm.SHA256, "secret", expectedGs2Header = "p=tls-server-end-point,,", expectedBindingData = endpoint.data))
    }

    @Test
    fun fallsBackToFlagNWhenNoBindingTypeIsShared() {
        val m = pick(SaslOffer(listOf("SCRAM-SHA-256-PLUS", "SCRAM-SHA-256"), listOf("tls-unique")))
        assertEquals("SCRAM-SHA-256", m.name)
        m.runAs(ScramTestServer(ScramAlgorithm.SHA256, "secret", expectedGs2Header = "n,,"))
    }

    @Test
    fun sendsFlagYWhenServerOffersNoPlus() {
        val m = pick(SaslOffer(listOf("SCRAM-SHA-256")))
        m.runAs(ScramTestServer(ScramAlgorithm.SHA256, "secret", expectedGs2Header = "y,,"))
        val noBinding = pick(SaslOffer(listOf("SCRAM-SHA-256")), SaslSecurityContext(tlsVerified = true))
        noBinding.runAs(ScramTestServer(ScramAlgorithm.SHA256, "secret", expectedGs2Header = "n,,"))
    }

    @Test
    fun refusesWithoutVerifiedTls() =
        assertEquals(SaslFailure.INSECURE_TRANSPORT, failure(SaslOffer(all), SaslSecurityContext(tlsVerified = false)))

    @Test
    fun plainOnlyWhenEnabled() {
        assertEquals(SaslFailure.NO_ACCEPTABLE_MECHANISM, failure(SaslOffer(listOf("PLAIN"))))
        val m = pick(SaslOffer(listOf("PLAIN")), config = SaslConfig(allowPlain = true))
        assertEquals("PLAIN", m.name)
        assertEquals("\u0000juliet\u0000secret", m.initialResponse().decodeToString())
        // SCRAM still wins when both are offered...
        assertEquals("SCRAM-SHA-256", pick(SaslOffer(listOf("PLAIN", "SCRAM-SHA-256")), config = SaslConfig(allowPlain = true)).name)
        // ...unless the app reorders explicitly (e.g. an LDAP-backed server with a broken SCRAM).
        val plainFirst = SaslConfig(allowPlain = true, mechanismOrder = listOf("PLAIN", "SCRAM-SHA-256"))
        assertEquals("PLAIN", pick(SaslOffer(listOf("PLAIN", "SCRAM-SHA-256")), config = plainFirst).name)
    }

    @Test
    fun plainNeedsThePassword() {
        val keysOnly = SaslCredentials("juliet", null, listOf(ScramKeys(ScramAlgorithm.SHA256, ByteArray(16), 4096, ByteArray(32), ByteArray(32))))
        assertFailsWith<SaslException> { pick(SaslOffer(listOf("PLAIN")), config = SaslConfig(allowPlain = true), credentials = keysOnly) }
        assertEquals("SCRAM-SHA-256", pick(SaslOffer(listOf("PLAIN", "SCRAM-SHA-256")), config = SaslConfig(allowPlain = true), credentials = keysOnly).name)
    }

    @Test
    fun scramSha1OnlyWhenEnabled() {
        assertEquals(SaslFailure.NO_ACCEPTABLE_MECHANISM, failure(SaslOffer(listOf("SCRAM-SHA-1", "SCRAM-SHA-1-PLUS"))))
        assertEquals("SCRAM-SHA-1-PLUS", pick(SaslOffer(listOf("SCRAM-SHA-1", "SCRAM-SHA-1-PLUS")), config = SaslConfig(allowScramSha1 = true)).name)
    }

    @Test
    fun pinnedChannelBindingCannotBeStripped() {
        val pinned = SaslSecurityContext(tlsVerified = true, channelBindings = listOf(exporter), channelBindingPinned = true)
        assertEquals(SaslFailure.DOWNGRADE_DETECTED, failure(SaslOffer(listOf("SCRAM-SHA-256")), pinned))
        assertTrue(pick(SaslOffer(listOf("SCRAM-SHA-256-PLUS", "SCRAM-SHA-256")), pinned).usesChannelBinding)
    }

    @Test
    fun ignoresUnknownMechanisms() {
        assertEquals(SaslFailure.NO_ACCEPTABLE_MECHANISM, failure(SaslOffer(listOf("DIGEST-MD5", "X-OAUTH2", "ANONYMOUS"))))
    }

    @Test
    fun opaqueStringPreparation() {
        assertEquals("a b", prepareOpaqueString("a b"))
        assertEquals("é", prepareOpaqueString("é").let { if (it.length == 1) it else "é" }) // NFC where the platform has it
        assertFailsWith<IllegalArgumentException> { prepareOpaqueString("a\u0007b") }
        assertFailsWith<IllegalArgumentException> { prepareOpaqueString("") }
    }

    private fun SaslMechanism.runAs(server: ScramTestServer) = (this as ScramMechanism).runAgainst(server)
}
