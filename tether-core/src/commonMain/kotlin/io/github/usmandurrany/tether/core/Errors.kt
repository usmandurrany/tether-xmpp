package io.github.usmandurrany.tether.core

/** Why negotiation stopped. The reconnection logic uses this to decide whether retrying makes sense. */
public enum class NegotiationFailure(public val retryable: Boolean) {
    /** No endpoint accepted a TCP connection. */
    UNREACHABLE(true),
    /** The connection dropped or timed out mid-negotiation. */
    CONNECTION_LOST(true),
    /** The server does not offer TLS; we never continue in plaintext. */
    TLS_UNAVAILABLE(false),
    /** The certificate was rejected. Never retried automatically. */
    TLS_VERIFICATION(false),
    /** Login failed or was refused by policy. */
    AUTHENTICATION(false),
    /** The server sent a stream error. Some conditions are transient (see [XmppNegotiationException.streamError]). */
    STREAM_ERROR(true),
    /** The server broke the protocol (missing bind, bad responses). */
    PROTOCOL(false),
}

public class XmppNegotiationException(
    public val failure: NegotiationFailure,
    message: String,
    /** Stream error condition (RFC 6120 §4.9.3), when [failure] is [NegotiationFailure.STREAM_ERROR]. */
    public val streamError: String? = null,
    cause: Throwable? = null,
) : Exception("$failure: $message", cause)
