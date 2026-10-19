package io.github.usmandurrany.tether.sasl

import io.github.usmandurrany.tether.crypto.Crypto
import io.github.usmandurrany.tether.crypto.pbkdf2
import kotlin.io.encoding.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Minimal SCRAM server for tests, written from RFC 5802 independently of the client. */
internal class ScramTestServer(
    private val algorithm: ScramAlgorithm,
    password: String,
    private val salt: ByteArray = "test-salt-123456".encodeToByteArray(),
    private val iterations: Int = 4096,
    private val expectedGs2Header: String = "n,,",
    private val expectedBindingData: ByteArray = ByteArray(0),
    private val downgradeHash: String? = null,
) {
    private val salted = Crypto.pbkdf2(algorithm.hash, password.encodeToByteArray(), salt, iterations)
    private val clientKey = Crypto.hmac(algorithm.hash, salted, "Client Key".encodeToByteArray())
    private val storedKey = Crypto.hash(algorithm.hash, clientKey)
    private val serverKey = Crypto.hmac(algorithm.hash, salted, "Server Key".encodeToByteArray())
    private lateinit var clientFirstBare: String
    private lateinit var serverFirst: String

    fun serverFirst(clientFirst: ByteArray): ByteArray {
        val text = clientFirst.decodeToString()
        assertEquals(expectedGs2Header, text.substring(0, expectedGs2Header.length), "GS2 header")
        clientFirstBare = text.substring(expectedGs2Header.length)
        val clientNonce = clientFirstBare.substringAfter(",r=")
        serverFirst = "r=${clientNonce}srvNonce,s=${Base64.Default.encode(salt)},i=$iterations" +
            (downgradeHash?.let { ",h=$it" } ?: "")
        return serverFirst.encodeToByteArray()
    }

    /** Verifies the client proof and returns server-final. */
    fun serverFinal(clientFinal: ByteArray): ByteArray {
        val text = clientFinal.decodeToString()
        val withoutProof = text.substringBefore(",p=")
        val cb = Base64.Default.decode(withoutProof.substringAfter("c=").substringBefore(","))
        assertContentEquals(expectedGs2Header.encodeToByteArray() + expectedBindingData, cb, "channel binding")
        val authMessage = "$clientFirstBare,$serverFirst,$withoutProof".encodeToByteArray()
        val signature = Crypto.hmac(algorithm.hash, storedKey, authMessage)
        val proof = Base64.Default.decode(text.substringAfter(",p="))
        val recoveredClientKey = ByteArray(proof.size) { (proof[it].toInt() xor signature[it].toInt()).toByte() }
        assertContentEquals(storedKey, Crypto.hash(algorithm.hash, recoveredClientKey), "client proof")
        return "v=${Base64.Default.encode(Crypto.hmac(algorithm.hash, serverKey, authMessage))}".encodeToByteArray()
    }
}

internal fun ScramMechanism.runAgainst(server: ScramTestServer) {
    val serverFirst = server.serverFirst(initialResponse())
    val serverFinal = server.serverFinal(evaluateChallenge(serverFirst))
    complete(serverFinal)
}
