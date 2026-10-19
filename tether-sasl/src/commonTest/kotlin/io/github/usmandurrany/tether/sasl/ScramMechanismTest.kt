package io.github.usmandurrany.tether.sasl

import io.github.usmandurrany.tether.crypto.Crypto
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScramMechanismTest {

    private fun scram(
        algorithm: ScramAlgorithm,
        credentials: SaslCredentials,
        nonce: String,
        binding: ChannelBinding? = null,
        flagY: Boolean = false,
        offer: SaslOffer? = null,
    ) = ScramMechanism(algorithm, credentials, binding, flagY, offer, ScramLimits(), clientNonce = nonce)

    // RFC 5802 §5
    @Test
    fun rfc5802Sha1Vector() {
        val m = scram(ScramAlgorithm.SHA1, SaslCredentials("user", "pencil"), "fyko+d2lbbFgONRv9qkxdawL")
        assertEquals("n,,n=user,r=fyko+d2lbbFgONRv9qkxdawL", m.initialResponse().decodeToString())
        val final = m.evaluateChallenge("r=fyko+d2lbbFgONRv9qkxdawL3rfcNHYJY1ZVvWVs7j,s=QSXCR+Q6sek8bf92,i=4096".encodeToByteArray())
        assertEquals(
            "c=biws,r=fyko+d2lbbFgONRv9qkxdawL3rfcNHYJY1ZVvWVs7j,p=v0X8v3Bz2T0CJGbJQyF0X+HI4Ts=",
            final.decodeToString(),
        )
        assertNull(m.derivedKeys, "keys are only released after the server is verified")
        m.complete("v=rmF9pqV8S7suAoZWja4dJRkFsKQ=".encodeToByteArray())
        assertNotNull(m.derivedKeys)
    }

    // RFC 7677 §3
    @Test
    fun rfc7677Sha256Vector() {
        val m = scram(ScramAlgorithm.SHA256, SaslCredentials("user", "pencil"), "rOprNGfwEbeRWgbNEkqO")
        assertEquals("n,,n=user,r=rOprNGfwEbeRWgbNEkqO", m.initialResponse().decodeToString())
        val final = m.evaluateChallenge(
            "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF\$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096".encodeToByteArray(),
        )
        assertEquals(
            "c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF\$k0,p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=",
            final.decodeToString(),
        )
        m.complete("v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=".encodeToByteArray())
    }

    @Test
    fun storedKeysReplaceThePassword() {
        val first = scram(ScramAlgorithm.SHA1, SaslCredentials("user", "pencil"), "fyko+d2lbbFgONRv9qkxdawL")
        first.initialResponse()
        first.evaluateChallenge("r=fyko+d2lbbFgONRv9qkxdawL3rfcNHYJY1ZVvWVs7j,s=QSXCR+Q6sek8bf92,i=4096".encodeToByteArray())
        first.complete("v=rmF9pqV8S7suAoZWja4dJRkFsKQ=".encodeToByteArray())

        val keysOnly = SaslCredentials("user", password = null, scramKeys = listOf(first.derivedKeys!!))
        val second = scram(ScramAlgorithm.SHA1, keysOnly, "fyko+d2lbbFgONRv9qkxdawL")
        second.initialResponse()
        val final = second.evaluateChallenge("r=fyko+d2lbbFgONRv9qkxdawL3rfcNHYJY1ZVvWVs7j,s=QSXCR+Q6sek8bf92,i=4096".encodeToByteArray())
        assertEquals(
            "c=biws,r=fyko+d2lbbFgONRv9qkxdawL3rfcNHYJY1ZVvWVs7j,p=v0X8v3Bz2T0CJGbJQyF0X+HI4Ts=",
            final.decodeToString(),
        )

        // If the server changes salt or iterations, stored keys cannot be used.
        val third = scram(ScramAlgorithm.SHA1, keysOnly, "abc")
        third.initialResponse()
        assertFailure(SaslFailure.CREDENTIALS_REQUIRED) {
            third.evaluateChallenge("r=abcdef,s=QSXCR+Q6sek8bf92,i=8192".encodeToByteArray())
        }
    }

    @Test
    fun sha512RoundTripAgainstIndependentServer() {
        val m = scram(ScramAlgorithm.SHA512, SaslCredentials("juliet", "r0m30myr0m30"), "clientnonce512")
        m.runAgainst(ScramTestServer(ScramAlgorithm.SHA512, "r0m30myr0m30"))
        assertNotNull(m.derivedKeys)
        assertEquals("SCRAM-SHA-512", m.name)
    }

    @Test
    fun channelBindingIsIncludedInTheProof() {
        val cb = ChannelBinding(ChannelBinding.TLS_EXPORTER, Crypto.randomBytes(32))
        val m = scram(ScramAlgorithm.SHA256, SaslCredentials("juliet", "secret"), "n0nce", binding = cb)
        assertEquals("SCRAM-SHA-256-PLUS", m.name)
        m.runAgainst(
            ScramTestServer(ScramAlgorithm.SHA256, "secret", expectedGs2Header = "p=tls-exporter,,", expectedBindingData = cb.data),
        )
    }

    @Test
    fun gs2FlagYWhenClientCouldBind() {
        val m = scram(ScramAlgorithm.SHA256, SaslCredentials("juliet", "secret"), "n0nce", flagY = true)
        m.runAgainst(ScramTestServer(ScramAlgorithm.SHA256, "secret", expectedGs2Header = "y,,"))
    }

    @Test
    fun rejectsWrongServerSignature() {
        val m = scram(ScramAlgorithm.SHA256, SaslCredentials("juliet", "secret"), "n0nce")
        val server = ScramTestServer(ScramAlgorithm.SHA256, "secret")
        m.evaluateChallenge(server.serverFirst(m.initialResponse()))
        val forged = "v=" + Base64.Default.encode(ByteArray(32))
        assertFailure(SaslFailure.SERVER_SIGNATURE_MISMATCH) { m.complete(forged.encodeToByteArray()) }
        assertNull(m.derivedKeys)
        // A failed exchange cannot be resumed.
        assertFailure(SaslFailure.PROTOCOL_ERROR) { m.complete(null) }
    }

    @Test
    fun rejectsSuccessWithoutServerProof() {
        val m = scram(ScramAlgorithm.SHA256, SaslCredentials("juliet", "secret"), "n0nce")
        val server = ScramTestServer(ScramAlgorithm.SHA256, "secret")
        m.evaluateChallenge(server.serverFirst(m.initialResponse()))
        assertFailure(SaslFailure.SERVER_SIGNATURE_MISMATCH) { m.complete(null) }
    }

    @Test
    fun acceptsServerFinalSentAsChallenge() {
        val m = scram(ScramAlgorithm.SHA256, SaslCredentials("juliet", "secret"), "n0nce")
        val server = ScramTestServer(ScramAlgorithm.SHA256, "secret")
        val serverFinal = server.serverFinal(m.evaluateChallenge(server.serverFirst(m.initialResponse())))
        assertEquals(0, m.evaluateChallenge(serverFinal).size)
        m.complete(null)
        assertNotNull(m.derivedKeys)
    }

    @Test
    fun rejectsMalformedOrWeakServerFirst() {
        fun attempt(serverFirst: String): SaslException {
            val m = scram(ScramAlgorithm.SHA256, SaslCredentials("u", "p"), "abc")
            m.initialResponse()
            return assertFailsWith<SaslException> { m.evaluateChallenge(serverFirst.encodeToByteArray()) }
        }
        assertEquals(SaslFailure.MALFORMED_SERVER_MESSAGE, attempt("r=xyz123,s=c2FsdA==,i=4096").failure) // nonce not extended
        assertEquals(SaslFailure.MALFORMED_SERVER_MESSAGE, attempt("r=abc,s=c2FsdA==,i=4096").failure) // nonce not longer
        assertEquals(SaslFailure.MALFORMED_SERVER_MESSAGE, attempt("m=ext,r=abcd,s=c2FsdA==,i=4096").failure)
        assertEquals(SaslFailure.MALFORMED_SERVER_MESSAGE, attempt("s=c2FsdA==,r=abcd,i=4096").failure)
        assertEquals(SaslFailure.MALFORMED_SERVER_MESSAGE, attempt("r=abcd,s=!!!,i=4096").failure)
        assertEquals(SaslFailure.MALFORMED_SERVER_MESSAGE, attempt("r=abcd,s=c2FsdA==,i=many").failure)
        assertEquals(SaslFailure.WEAK_PARAMETERS, attempt("r=abcd,s=c2FsdA==,i=1000").failure)
        assertEquals(SaslFailure.WEAK_PARAMETERS, attempt("r=abcd,s=c2FsdA==,i=50000000").failure)
    }

    @Test
    fun reportsServerErrors() {
        val m = scram(ScramAlgorithm.SHA256, SaslCredentials("juliet", "secret"), "n0nce")
        val server = ScramTestServer(ScramAlgorithm.SHA256, "secret")
        m.evaluateChallenge(server.serverFirst(m.initialResponse()))
        assertFailure(SaslFailure.SERVER_REJECTED) { m.complete("e=invalid-proof".encodeToByteArray()) }
    }

    @Test
    fun escapesUsername() {
        val m = scram(ScramAlgorithm.SHA256, SaslCredentials("a=b,c", "p"), "n")
        assertEquals("n,,n=a=3Db=2Cc,r=n", m.initialResponse().decodeToString())
    }

    @Test
    fun rejectsCallsOutOfOrder() {
        val m = scram(ScramAlgorithm.SHA256, SaslCredentials("u", "p"), "n")
        assertFailure(SaslFailure.PROTOCOL_ERROR) { m.evaluateChallenge("r=nx,s=c2FsdA==,i=4096".encodeToByteArray()) }
    }

    // XEP-0474

    @Test
    fun downgradeProtectionInputMatchesXep0474() {
        val offer = SaslOffer(
            mechanisms = listOf("SCRAM-SHA-1-PLUS", "SCRAM-SHA-1"),
            channelBindingTypes = listOf("tls-server-end-point", "tls-exporter"),
        )
        assertEquals("SCRAM-SHA-1\u001ESCRAM-SHA-1-PLUS\u001Ftls-exporter\u001Etls-server-end-point", downgradeProtectionInput(offer))
        assertEquals("A\u001EB", downgradeProtectionInput(SaslOffer(listOf("B", "A"))))
    }

    @Test
    fun acceptsMatchingDowngradeHashAndRejectsAlteredList() {
        val real = SaslOffer(listOf("SCRAM-SHA-256", "SCRAM-SHA-256-PLUS"), listOf("tls-exporter"))
        val realHash = Base64.Default.encode(
            Crypto.hash(ScramAlgorithm.SHA256.hash, downgradeProtectionInput(real).encodeToByteArray()),
        )
        // Client saw the same list the server hashed: fine.
        scram(ScramAlgorithm.SHA256, SaslCredentials("u", "p"), "n", offer = real)
            .runAgainst(ScramTestServer(ScramAlgorithm.SHA256, "p", downgradeHash = realHash))

        // An attacker stripped -PLUS from what the client saw.
        val stripped = SaslOffer(listOf("SCRAM-SHA-256"), listOf("tls-exporter"))
        val m = scram(ScramAlgorithm.SHA256, SaslCredentials("u", "p"), "n", offer = stripped)
        val server = ScramTestServer(ScramAlgorithm.SHA256, "p", downgradeHash = realHash)
        assertFailure(SaslFailure.DOWNGRADE_DETECTED) { m.evaluateChallenge(server.serverFirst(m.initialResponse())) }
    }
}

internal fun assertFailure(expected: SaslFailure, block: () -> Unit) {
    val e = assertFailsWith<SaslException> { block() }
    assertEquals(expected, e.failure, e.message)
}
