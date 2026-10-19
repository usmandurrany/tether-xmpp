package io.github.usmandurrany.tether.sasl

/**
 * One SASL authentication exchange (RFC 4422), independent of how it is carried:
 * the same object works inside classic SASL (RFC 6120 §6) and SASL2 (XEP-0388).
 *
 * The caller drives it: send [initialResponse], pass each server challenge to
 * [evaluateChallenge] and send the result back, then pass the success payload to
 * [complete]. Any [SaslException] means authentication must be aborted. A
 * mechanism object is single-use.
 */
public interface SaslMechanism {
    /** The mechanism name as sent on the wire, e.g. `SCRAM-SHA-256-PLUS`. */
    public val name: String

    /** True when the exchange is bound to the TLS channel (a `-PLUS` mechanism). */
    public val usesChannelBinding: Boolean

    /** Client's first message. */
    public fun initialResponse(): ByteArray

    /** Response to a server challenge. */
    public fun evaluateChallenge(challenge: ByteArray): ByteArray

    /**
     * Called on `<success/>` with its additional data, if any. Mechanisms that
     * authenticate the server (SCRAM) throw unless the server proved itself.
     */
    public fun complete(additionalData: ByteArray?)
}

public enum class SaslFailure {
    /** No mechanism offered by the server is acceptable under the configured policy. */
    NO_ACCEPTABLE_MECHANISM,
    /** Authentication was attempted without a verified TLS connection. */
    INSECURE_TRANSPORT,
    /** Signs of an attacker stripping stronger options (channel binding, mechanisms). */
    DOWNGRADE_DETECTED,
    /** The server could not prove it knows the credentials: possibly an impostor. */
    SERVER_SIGNATURE_MISMATCH,
    /** The server's message does not follow the mechanism's syntax. */
    MALFORMED_SERVER_MESSAGE,
    /** The server asked for parameters below the configured minimum (or absurdly high). */
    WEAK_PARAMETERS,
    /** Stored keys no longer match the server; the password must be supplied again. */
    CREDENTIALS_REQUIRED,
    /** The server reported an error inside the mechanism exchange. */
    SERVER_REJECTED,
    /** Calls arrived in an order the mechanism does not allow. */
    PROTOCOL_ERROR,
}

public class SaslException(public val failure: SaslFailure, message: String) : Exception("$failure: $message")

/** Channel-binding data taken from the TLS layer (RFC 5056, RFC 5929, RFC 9266). */
public class ChannelBinding(public val type: String, public val data: ByteArray) {
    override fun toString(): String = "ChannelBinding($type)"

    public companion object {
        public const val TLS_EXPORTER: String = "tls-exporter"
        public const val TLS_SERVER_END_POINT: String = "tls-server-end-point"
    }
}
