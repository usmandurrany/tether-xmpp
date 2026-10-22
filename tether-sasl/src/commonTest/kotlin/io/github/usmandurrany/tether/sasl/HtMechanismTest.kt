package io.github.usmandurrany.tether.sasl

import io.github.usmandurrany.tether.crypto.Crypto
import io.github.usmandurrany.tether.crypto.HashAlgorithm
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HtMechanismTest {
    private val endpoint = ChannelBinding(ChannelBinding.TLS_SERVER_END_POINT, ByteArray(32) { it.toByte() })
    private val exporter = ChannelBinding(ChannelBinding.TLS_EXPORTER, ByteArray(32) { (it * 3).toByte() })
    private fun security(vararg bindings: ChannelBinding, pinned: Boolean = false) =
        SaslSecurityContext(tlsVerified = true, channelBindings = bindings.toList(), channelBindingPinned = pinned)

    private fun hmac(key: String, label: String, cb: ByteArray, hash: HashAlgorithm = HashAlgorithm.SHA256) =
        Crypto.hmac(hash, key.encodeToByteArray(), label.encodeToByteArray() + cb)

    @Test
    fun initialResponseIsUsernameNulAndInitiatorHmac() {
        val token = FastToken("HT-SHA-256-NONE", "s3cret", null)
        val ht = HtMechanism.forToken("juliet", token, listOf("HT-SHA-256-NONE"), security())!!
        val expected = "juliet".encodeToByteArray() + byteArrayOf(0) + hmac("s3cret", "Initiator", ByteArray(0))
        assertContentEquals(expected, ht.initialResponse())
        assertFalse(ht.usesChannelBinding)
        ht.complete(hmac("s3cret", "Responder", ByteArray(0)))
    }

    @Test
    fun channelBindingDataIsAppended() {
        val token = FastToken("HT-SHA-256-ENDP", "s3cret", null)
        val ht = HtMechanism.forToken("juliet", token, listOf("HT-SHA-256-ENDP"), security(exporter, endpoint))!!
        val response = ht.initialResponse()
        assertContentEquals(hmac("s3cret", "Initiator", endpoint.data), response.copyOfRange(7, response.size))
        assertTrue(ht.usesChannelBinding)
        ht.complete(hmac("s3cret", "Responder", endpoint.data))
    }

    @Test
    fun sha512Works() {
        val token = FastToken("HT-SHA-512-EXPR", "s3cret", null)
        val ht = HtMechanism.forToken("juliet", token, listOf("HT-SHA-512-EXPR"), security(exporter))!!
        val response = ht.initialResponse()
        assertEquals(7 + 64, response.size)
        ht.complete(hmac("s3cret", "Responder", exporter.data, HashAlgorithm.SHA512))
    }

    @Test
    fun aServerThatCannotProveTheTokenIsRejected() {
        val ht = HtMechanism.forToken("juliet", FastToken("HT-SHA-256-NONE", "s3cret", null), listOf("HT-SHA-256-NONE"), security())!!
        ht.initialResponse()
        val e = assertFailsWith<SaslException> { ht.complete(hmac("other", "Responder", ByteArray(0))) }
        assertEquals(SaslFailure.SERVER_SIGNATURE_MISMATCH, e.failure)
        assertFailsWith<SaslException> { ht.complete(null) }
    }

    @Test
    fun tokenNeedsItsMechanismAndBinding() {
        val bound = FastToken("HT-SHA-256-EXPR", "t", null)
        assertNull(HtMechanism.forToken("juliet", bound, listOf("HT-SHA-256-NONE"), security(exporter)), "mechanism no longer offered")
        assertNull(HtMechanism.forToken("juliet", bound, listOf("HT-SHA-256-EXPR"), security(endpoint)), "binding unavailable")
        val unbound = FastToken("HT-SHA-256-NONE", "t", null)
        assertNull(HtMechanism.forToken("juliet", unbound, listOf("HT-SHA-256-NONE"), security(pinned = true)), "pinned accounts need binding")
        assertNull(
            HtMechanism.forToken("juliet", unbound, listOf("HT-SHA-256-NONE"), SaslSecurityContext(tlsVerified = false)),
            "never without verified TLS",
        )
    }

    @Test
    fun picksTheStrongestMechanismForANewToken() {
        val offered = listOf("HT-SHA-256-NONE", "HT-SHA-256-ENDP", "HT-SHA-256-EXPR", "HT-SHA-512-ENDP", "HT-SHA3-512-NONE", "HT-SHA-256-UNIQ")
        assertEquals("HT-SHA-256-EXPR", HtMechanism.pickForNewToken(offered, security(exporter, endpoint)))
        assertEquals("HT-SHA-512-ENDP", HtMechanism.pickForNewToken(offered, security(endpoint)))
        assertEquals("HT-SHA-256-NONE", HtMechanism.pickForNewToken(offered, security()))
        assertNull(HtMechanism.pickForNewToken(offered, security(pinned = true)))
        assertNull(HtMechanism.pickForNewToken(listOf("HT-SHA3-512-NONE"), security()))
        assertNull(HtMechanism.pickForNewToken(emptyList(), security()))
    }

    @Test
    fun tokensOnlyForKnownMechanisms() {
        assertFailsWith<IllegalArgumentException> { FastToken("SCRAM-SHA-256", "t", null) }
        assertFailsWith<IllegalArgumentException> { FastToken("HT-SHA-256-NONE", "", null) }
    }
}
