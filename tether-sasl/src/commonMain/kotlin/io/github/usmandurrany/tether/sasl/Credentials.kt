package io.github.usmandurrany.tether.sasl

import io.github.usmandurrany.tether.crypto.HashAlgorithm

public enum class ScramAlgorithm(public val mechanismName: String, internal val hash: HashAlgorithm) {
    SHA1("SCRAM-SHA-1", HashAlgorithm.SHA1),
    SHA256("SCRAM-SHA-256", HashAlgorithm.SHA256),
    SHA512("SCRAM-SHA-512", HashAlgorithm.SHA512),
    ;

    public val plusMechanismName: String get() = "$mechanismName-PLUS"
}

/**
 * Keys derived from the password for one SCRAM algorithm (RFC 5802 §3). They let
 * the client log in again without keeping the password. They are still secrets:
 * anyone holding them can authenticate, so store them in secure storage.
 */
public class ScramKeys(
    public val algorithm: ScramAlgorithm,
    salt: ByteArray,
    public val iterations: Int,
    clientKey: ByteArray,
    serverKey: ByteArray,
) {
    private val saltBytes = salt.copyOf()
    private val clientKeyBytes = clientKey.copyOf()
    private val serverKeyBytes = serverKey.copyOf()

    public val salt: ByteArray get() = saltBytes.copyOf()
    public val clientKey: ByteArray get() = clientKeyBytes.copyOf()
    public val serverKey: ByteArray get() = serverKeyBytes.copyOf()

    internal fun matches(algorithm: ScramAlgorithm, salt: ByteArray, iterations: Int): Boolean =
        this.algorithm == algorithm && this.iterations == iterations && saltBytes.contentEquals(salt)

    override fun toString(): String = "ScramKeys($algorithm, iterations=$iterations)"
}

/**
 * What the client can authenticate with: the password, previously derived
 * [ScramKeys], or both. The password is preferred only when the stored keys no
 * longer match what the server sends.
 */
public class SaslCredentials(
    public val username: String,
    public val password: String? = null,
    public val scramKeys: List<ScramKeys> = emptyList(),
) {
    init {
        require(username.isNotEmpty()) { "username must not be empty" }
        require(password != null || scramKeys.isNotEmpty()) { "either a password or stored SCRAM keys is required" }
    }

    internal fun canUse(algorithm: ScramAlgorithm): Boolean =
        password != null || scramKeys.any { it.algorithm == algorithm }

    override fun toString(): String = "SaslCredentials(username=$username, password=${if (password != null) "***" else "none"})"
}
