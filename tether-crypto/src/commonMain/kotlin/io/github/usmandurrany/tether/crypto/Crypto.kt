package io.github.usmandurrany.tether.crypto

/** Hash functions the SDK needs. Block sizes are for HMAC (RFC 2104). */
public enum class HashAlgorithm(public val outputBytes: Int, internal val blockBytes: Int) {
    SHA1(20, 64),
    SHA256(32, 64),
    SHA512(64, 128),
}

/**
 * Platform crypto primitives. JVM and Android use JCA. [PureHashes] and
 * [hmacWith] stay in common code for future targets without a synchronous
 * crypto provider.
 */
public expect object Crypto {
    public fun hash(algorithm: HashAlgorithm, data: ByteArray): ByteArray

    public fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray

    /** Cryptographically secure random bytes. */
    public fun randomBytes(count: Int): ByteArray
}

/**
 * PBKDF2 with HMAC, producing one hash-length block. This is `Hi()` from
 * RFC 5802 §2.2, which is all SCRAM needs.
 */
public fun Crypto.pbkdf2(algorithm: HashAlgorithm, password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
    require(iterations >= 1) { "iterations must be at least 1" }
    var u = hmac(algorithm, password, salt + byteArrayOf(0, 0, 0, 1))
    val result = u.copyOf()
    repeat(iterations - 1) {
        u = hmac(algorithm, password, u)
        for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
    }
    u.fill(0)
    return result
}

/** Compares in time that depends only on the lengths, not on where the inputs differ. */
public fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

/** Generic HMAC over [hash] (RFC 2104), for platforms without a native implementation. */
internal fun hmacWith(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray, hash: (ByteArray) -> ByteArray): ByteArray {
    val block = algorithm.blockBytes
    val k = (if (key.size > block) hash(key) else key).copyOf(block)
    val inner = ByteArray(block) { (k[it].toInt() xor 0x36).toByte() }
    val outer = ByteArray(block) { (k[it].toInt() xor 0x5C).toByte() }
    k.fill(0)
    return hash(outer + hash(inner + data))
}
