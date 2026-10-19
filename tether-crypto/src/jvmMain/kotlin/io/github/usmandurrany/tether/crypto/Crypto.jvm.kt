package io.github.usmandurrany.tether.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

public actual object Crypto {
    private val random = SecureRandom()

    public actual fun hash(algorithm: HashAlgorithm, data: ByteArray): ByteArray =
        MessageDigest.getInstance(algorithm.jcaDigest).digest(data)

    public actual fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray {
        // JCA rejects empty keys; HMAC defines them as all-zero, so use the generic code for that case.
        if (key.isEmpty()) return hmacWith(algorithm, key, data) { hash(algorithm, it) }
        val mac = Mac.getInstance(algorithm.jcaMac)
        mac.init(SecretKeySpec(key, algorithm.jcaMac))
        return mac.doFinal(data)
    }

    public actual fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    private val HashAlgorithm.jcaDigest: String
        get() = when (this) {
            HashAlgorithm.SHA1 -> "SHA-1"
            HashAlgorithm.SHA256 -> "SHA-256"
            HashAlgorithm.SHA512 -> "SHA-512"
        }

    private val HashAlgorithm.jcaMac: String
        get() = when (this) {
            HashAlgorithm.SHA1 -> "HmacSHA1"
            HashAlgorithm.SHA256 -> "HmacSHA256"
            HashAlgorithm.SHA512 -> "HmacSHA512"
        }
}
