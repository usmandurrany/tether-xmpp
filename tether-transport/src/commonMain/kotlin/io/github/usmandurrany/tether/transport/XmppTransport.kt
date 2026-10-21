package io.github.usmandurrany.tether.transport

/** Where to connect. [directTls] means TLS from the first byte (XEP-0368); otherwise STARTTLS. */
public data class Endpoint(
    public val host: String,
    public val port: Int,
    public val directTls: Boolean,
) {
    init {
        require(host.isNotEmpty()) { "host must not be empty" }
        require(port in 1..65535) { "port out of range" }
    }

    override fun toString(): String = "$host:$port${if (directTls) " (direct TLS)" else ""}"
}

/** Channel-binding data exported from the TLS session (RFC 5929, RFC 9266). */
public class TlsChannelBinding(public val type: String, public val data: ByteArray) {
    override fun toString(): String = "TlsChannelBinding($type)"
}

/** Facts about an established TLS session, for logs and for the app's security UI. */
public data class TlsInfo(
    public val protocol: String,
    public val cipherSuite: String,
    /** SHA-256 of the server's leaf certificate (DER), lowercase hex. */
    public val peerCertificateSha256: String,
    /** True when the certificate was accepted because it matched a configured pin. */
    public val pinned: Boolean,
)

/**
 * A byte pipe to one XMPP server. One transport carries one connection attempt;
 * reconnecting means a new transport. Not thread-safe: one coroutine reads, and
 * writes are serialised by the caller.
 */
public interface XmppTransport {
    public val endpoint: Endpoint

    /** Non-null once TLS is established and the server certificate is verified. */
    public val tls: TlsInfo?

    /** Channel bindings the TLS session can provide, most preferred first. Empty before TLS. */
    public val channelBindings: List<TlsChannelBinding>

    /** Reads into [buffer]; returns the byte count, or -1 when the server closed the connection. */
    public suspend fun read(buffer: ByteArray): Int

    public suspend fun write(data: ByteArray)

    /**
     * Upgrades to TLS after `<proceed/>` (RFC 6120 §5.4.3.3), verifying the certificate
     * against [serverName] (the XMPP domain, never the SRV target).
     */
    public suspend fun startTls(serverName: String)

    /** Closes immediately. Safe to call more than once. */
    public fun close()
}

public interface XmppTransportFactory {
    /** Opens a connection; for [Endpoint.directTls] the TLS handshake is part of connecting. */
    public suspend fun connect(endpoint: Endpoint, serverName: String): XmppTransport
}

/**
 * Certificate policy. By default the platform trust store and hostname checks
 * apply. Pins are for servers with self-signed or private-CA certificates: when
 * set, only certificates whose SHA-256 fingerprint is listed are accepted, and
 * the trust store is not consulted.
 */
public data class TlsConfig(
    /** Lowercase hex SHA-256 fingerprints of acceptable leaf certificates (DER). */
    public val pinnedCertificateSha256: Set<String> = emptySet(),
    public val connectTimeoutMillis: Long = 10_000,
    /** Longest wait for the server during the TLS handshake before the attempt fails and is retried. */
    public val handshakeTimeoutMillis: Long = 15_000,
) {
    init {
        require(pinnedCertificateSha256.all { it.matches(Regex("[0-9a-f]{64}")) }) {
            "Pins must be 64 lowercase hex characters (SHA-256 of the DER certificate)"
        }
    }
}

/** TLS failed. [peerCertificateSha256] is set when the server presented a certificate we refused. */
public class TlsVerificationException(
    message: String,
    public val peerCertificateSha256: String?,
    cause: Throwable? = null,
) : Exception(message, cause)
