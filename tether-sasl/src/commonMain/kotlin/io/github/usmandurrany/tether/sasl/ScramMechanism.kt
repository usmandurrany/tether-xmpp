package io.github.usmandurrany.tether.sasl

import io.github.usmandurrany.tether.crypto.Crypto
import io.github.usmandurrany.tether.crypto.constantTimeEquals
import io.github.usmandurrany.tether.crypto.pbkdf2
import kotlin.io.encoding.Base64

/** Bounds on the server-chosen PBKDF2 iteration count. */
public data class ScramLimits(
    /** RFC 7677 recommends at least 4096. Lower counts make stolen hashes cheap to crack. */
    public val minIterations: Int = 4096,
    /** Upper bound so a hostile server cannot make the client burn CPU for minutes. */
    public val maxIterations: Int = 1_000_000,
)

/**
 * SCRAM client (RFC 5802, RFC 7677), with optional channel binding (`-PLUS`) and
 * XEP-0474 downgrade protection.
 *
 * The server is authenticated too: [complete] fails unless the server's final
 * message carries a valid signature, which only a party knowing the password
 * (or the server key) can produce.
 *
 * Create instances through [SaslMechanismSelector]; it decides channel binding
 * and the GS2 flag according to policy.
 */
public class ScramMechanism internal constructor(
    public val algorithm: ScramAlgorithm,
    private val credentials: SaslCredentials,
    private val channelBinding: ChannelBinding?,
    /** True when the client could bind but the server offered no `-PLUS` mechanism (GS2 flag `y`). */
    private val clientSupportsBinding: Boolean,
    /** What the server advertised, used to check XEP-0474's `h` attribute. */
    private val advertised: SaslOffer?,
    private val limits: ScramLimits,
    private val clientNonce: String = Base64.Default.encode(Crypto.randomBytes(24)),
) : SaslMechanism {

    private enum class State { INITIAL, AWAITING_SERVER_FIRST, AWAITING_SERVER_FINAL, VERIFIED, FAILED }

    override val name: String =
        if (channelBinding != null) algorithm.plusMechanismName else algorithm.mechanismName

    override val usesChannelBinding: Boolean get() = channelBinding != null

    private var state = State.INITIAL
    private val gs2Header = when {
        channelBinding != null -> "p=${channelBinding.type},,"
        clientSupportsBinding -> "y,,"
        else -> "n,,"
    }
    private val clientFirstBare = "n=${escapeSaslName(credentials.username)},r=$clientNonce"
    private var expectedServerSignature: ByteArray? = null
    private var pendingKeys: ScramKeys? = null

    /**
     * Keys derived during a verified exchange. Store them (securely) to log in later
     * without the password. Null until the server has been verified.
     */
    public var derivedKeys: ScramKeys? = null
        private set

    override fun initialResponse(): ByteArray = guarded {
        expectState(State.INITIAL)
        state = State.AWAITING_SERVER_FIRST
        (gs2Header + clientFirstBare).encodeToByteArray()
    }

    override fun evaluateChallenge(challenge: ByteArray): ByteArray = guarded {
        when (state) {
            State.AWAITING_SERVER_FIRST -> handleServerFirst(challenge)
            // Some servers send server-final as a challenge and then an empty <success/>.
            State.AWAITING_SERVER_FINAL -> {
                verifyServerFinal(challenge)
                ByteArray(0)
            }
            else -> fail(SaslFailure.PROTOCOL_ERROR, "Unexpected challenge in state $state")
        }
    }

    override fun complete(additionalData: ByteArray?): Unit = guarded {
        when (state) {
            State.AWAITING_SERVER_FINAL -> {
                if (additionalData == null || additionalData.isEmpty()) {
                    fail(SaslFailure.SERVER_SIGNATURE_MISMATCH, "Server reported success without proving its identity")
                }
                verifyServerFinal(additionalData)
            }
            State.VERIFIED -> Unit
            else -> fail(SaslFailure.PROTOCOL_ERROR, "Success received in state $state")
        }
    }

    private fun handleServerFirst(challenge: ByteArray): ByteArray {
        val message = decode(challenge)
        val attributes = parseAttributes(message)
        if (attributes.first().first == 'm') {
            fail(SaslFailure.MALFORMED_SERVER_MESSAGE, "Server requires an unsupported SCRAM extension")
        }
        if (attributes.size < 3 || attributes[0].first != 'r' || attributes[1].first != 's' || attributes[2].first != 'i') {
            fail(SaslFailure.MALFORMED_SERVER_MESSAGE, "server-first-message must start with r, s, i")
        }
        val nonce = attributes[0].second
        if (!nonce.startsWith(clientNonce) || nonce.length == clientNonce.length) {
            fail(SaslFailure.MALFORMED_SERVER_MESSAGE, "Server nonce does not extend the client nonce")
        }
        val salt = decodeBase64(attributes[1].second)
        if (salt.isEmpty()) fail(SaslFailure.MALFORMED_SERVER_MESSAGE, "Empty salt")
        val iterations = attributes[2].second.toIntOrNull()
            ?: fail(SaslFailure.MALFORMED_SERVER_MESSAGE, "Invalid iteration count")
        if (iterations < limits.minIterations) {
            fail(SaslFailure.WEAK_PARAMETERS, "Iteration count $iterations is below the minimum ${limits.minIterations}")
        }
        if (iterations > limits.maxIterations) {
            fail(SaslFailure.WEAK_PARAMETERS, "Iteration count $iterations is above the maximum ${limits.maxIterations}")
        }
        checkDowngradeProtection(attributes)

        val keys = credentials.scramKeys.firstOrNull { it.matches(algorithm, salt, iterations) }
            ?: deriveKeys(salt, iterations)
        val hash = algorithm.hash
        val clientKey = keys.clientKey
        val storedKey = Crypto.hash(hash, clientKey)

        val cbData = gs2Header.encodeToByteArray() + (channelBinding?.data ?: ByteArray(0))
        val clientFinalWithoutProof = "c=${Base64.Default.encode(cbData)},r=$nonce"
        val authMessage = "$clientFirstBare,$message,$clientFinalWithoutProof".encodeToByteArray()

        val clientSignature = Crypto.hmac(hash, storedKey, authMessage)
        val proof = ByteArray(clientKey.size) { (clientKey[it].toInt() xor clientSignature[it].toInt()).toByte() }
        expectedServerSignature = Crypto.hmac(hash, keys.serverKey, authMessage)
        pendingKeys = keys
        clientKey.fill(0)
        state = State.AWAITING_SERVER_FINAL
        return "$clientFinalWithoutProof,p=${Base64.Default.encode(proof)}".encodeToByteArray()
    }

    private fun deriveKeys(salt: ByteArray, iterations: Int): ScramKeys {
        val password = credentials.password ?: fail(
            SaslFailure.CREDENTIALS_REQUIRED,
            "Stored keys do not match the server's salt or iteration count; the password is needed",
        )
        val hash = algorithm.hash
        val salted = Crypto.pbkdf2(hash, prepareOpaqueString(password).encodeToByteArray(), salt, iterations)
        try {
            return ScramKeys(
                algorithm,
                salt,
                iterations,
                clientKey = Crypto.hmac(hash, salted, "Client Key".encodeToByteArray()),
                serverKey = Crypto.hmac(hash, salted, "Server Key".encodeToByteArray()),
            )
        } finally {
            salted.fill(0)
        }
    }

    /** XEP-0474: the server hashes what it advertised; a mismatch means someone edited the list in transit. */
    private fun checkDowngradeProtection(attributes: List<Pair<Char, String>>) {
        val h = attributes.firstOrNull { it.first == 'h' }?.second ?: return
        val offer = advertised ?: return
        val expected = Crypto.hash(algorithm.hash, downgradeProtectionInput(offer).encodeToByteArray())
        if (!constantTimeEquals(expected, decodeBase64(h))) {
            fail(SaslFailure.DOWNGRADE_DETECTED, "The mechanism list was altered in transit (XEP-0474)")
        }
    }

    private fun verifyServerFinal(data: ByteArray) {
        val attributes = parseAttributes(decode(data))
        val (key, value) = attributes.first()
        if (key == 'e') fail(SaslFailure.SERVER_REJECTED, "Server error: $value")
        if (key != 'v') fail(SaslFailure.MALFORMED_SERVER_MESSAGE, "server-final-message must start with v")
        val expected = expectedServerSignature ?: fail(SaslFailure.PROTOCOL_ERROR, "No pending verification")
        if (!constantTimeEquals(expected, decodeBase64(value))) {
            fail(SaslFailure.SERVER_SIGNATURE_MISMATCH, "Server signature is wrong")
        }
        derivedKeys = pendingKeys
        state = State.VERIFIED
    }

    private fun decode(bytes: ByteArray): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: Exception) {
        fail(SaslFailure.MALFORMED_SERVER_MESSAGE, "Server message is not valid UTF-8")
    }

    private fun decodeBase64(value: String): ByteArray = try {
        Base64.Default.decode(value)
    } catch (e: IllegalArgumentException) {
        fail(SaslFailure.MALFORMED_SERVER_MESSAGE, "Invalid base64")
    }

    /** Splits `k=v,k=v`; values never contain commas in SCRAM. */
    private fun parseAttributes(message: String): List<Pair<Char, String>> {
        if (message.isEmpty()) fail(SaslFailure.MALFORMED_SERVER_MESSAGE, "Empty server message")
        return message.split(',').map { part ->
            if (part.length < 2 || part[1] != '=' || !part[0].isLetter()) {
                fail(SaslFailure.MALFORMED_SERVER_MESSAGE, "Invalid attribute")
            }
            part[0] to part.substring(2)
        }
    }

    private fun expectState(expected: State) {
        if (state != expected) fail(SaslFailure.PROTOCOL_ERROR, "Expected state $expected but was $state")
    }

    /** Any failure is final: the exchange cannot continue. */
    private inline fun <T> guarded(block: () -> T): T {
        if (state == State.FAILED) fail(SaslFailure.PROTOCOL_ERROR, "Exchange already failed")
        try {
            return block()
        } catch (e: Exception) {
            state = State.FAILED
            expectedServerSignature?.fill(0)
            throw e
        }
    }

    private fun fail(failure: SaslFailure, message: String): Nothing = throw SaslException(failure, message)
}

/**
 * XEP-0474 hash input: mechanisms sorted by octet order joined with U+001E, then,
 * if channel-binding types were advertised (XEP-0440), U+001F and the sorted types
 * joined with U+001E.
 */
internal fun downgradeProtectionInput(offer: SaslOffer): String = buildString {
    append(offer.mechanisms.sortedWith(OctetOrder).joinToString("\u001E"))
    offer.channelBindingTypes?.let { types ->
        append('\u001F')
        append(types.sortedWith(OctetOrder).joinToString("\u001E"))
    }
}

/** "i;octet" collation (RFC 4790 §9.3): compare UTF-8 bytes as unsigned values. */
private object OctetOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return x.size - y.size
    }
}
