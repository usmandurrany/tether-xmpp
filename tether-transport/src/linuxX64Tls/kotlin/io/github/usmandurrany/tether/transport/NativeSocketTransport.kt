@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package io.github.usmandurrany.tether.transport

import io.github.usmandurrany.tether.transport.tls.SSL
import io.github.usmandurrany.tether.transport.tls.SSL_ERROR_ZERO_RETURN
import io.github.usmandurrany.tether.transport.tls.SSL_connect
import io.github.usmandurrany.tether.transport.tls.SSL_free
import io.github.usmandurrany.tether.transport.tls.SSL_get_error
import io.github.usmandurrany.tether.transport.tls.SSL_get_verify_result
import io.github.usmandurrany.tether.transport.tls.SSL_get_version
import io.github.usmandurrany.tether.transport.tls.SSL_read
import io.github.usmandurrany.tether.transport.tls.SSL_write
import io.github.usmandurrany.tether.transport.tls.X509_verify_cert_error_string
import io.github.usmandurrany.tether.transport.tls.tether_peer_der
import io.github.usmandurrany.tether.transport.tls.tether_peer_sig_bits
import io.github.usmandurrany.tether.transport.tls.tether_peer_valid_now
import io.github.usmandurrany.tether.transport.tls.tether_tcp_connect
import io.github.usmandurrany.tether.transport.tls.tether_tls_cipher
import io.github.usmandurrany.tether.transport.tls.tether_tls_error
import io.github.usmandurrany.tether.transport.tls.tether_tls_exporter
import io.github.usmandurrany.tether.transport.tls.tether_tls_new
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UByteVar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.cinterop.readBytes
import platform.posix.EINTR
import platform.posix.SHUT_RDWR
import platform.posix.close
import platform.posix.errno
import platform.posix.recv
import platform.posix.send
import platform.posix.shutdown
import io.github.usmandurrany.tether.transport.tls.tether_digest
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * TCP/TLS transport for Linux native, on POSIX sockets and the system's
 * OpenSSL (libssl 1.1 or 3). Blocking calls run on [Dispatchers.IO].
 *
 * The same rules as on the JVM: TLS 1.2 minimum, the system trust store and
 * RFC 6125 hostname checks (OpenSSL's), or exact SHA-256 pins instead.
 * `tls-exporter` is offered on TLS 1.3, and `tls-server-end-point` always.
 */
public class NativeSocketTransportFactory(private val config: TlsConfig = TlsConfig()) : XmppTransportFactory {
    override suspend fun connect(endpoint: Endpoint, serverName: String): XmppTransport = withContext(Dispatchers.IO) {
        val fd = tether_tcp_connect(endpoint.host, endpoint.port, config.connectTimeoutMillis.toInt())
        if (fd < 0) throw TransportException("Cannot connect to $endpoint")
        val transport = NativeSocketTransport(endpoint, fd, config)
        try {
            if (endpoint.directTls) transport.upgrade(serverName)
        } catch (t: Throwable) {
            transport.close()
            throw t
        }
        transport
    }
}

/** A connection failure below TLS. */
public class TransportException(message: String) : Exception(message)

internal class NativeSocketTransport(
    override val endpoint: Endpoint,
    private val fd: Int,
    private val config: TlsConfig,
) : XmppTransport {
    private var ssl: CPointer<SSL>? = null

    // Calls in progress, so close() frees the socket and the SSL only once nobody uses them.
    private val users = AtomicInt(0)
    private val state = AtomicInt(OPEN)

    override var tls: TlsInfo? = null
        private set

    override var channelBindings: List<TlsChannelBinding> = emptyList()
        private set

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        using {
            val tlsSocket = ssl
            buffer.usePinned { pinned ->
                while (true) {
                    if (tlsSocket != null) {
                        val n = SSL_read(tlsSocket, pinned.addressOf(0), buffer.size)
                        if (n > 0) return@usePinned n
                        if (SSL_get_error(tlsSocket, n) == SSL_ERROR_ZERO_RETURN) return@usePinned -1
                        if (state.load() != OPEN) return@usePinned -1
                        throw TransportException("TLS read failed: ${lastTlsError()}")
                    } else {
                        val n = recv(fd, pinned.addressOf(0), buffer.size.convert(), 0).toInt()
                        if (n > 0) return@usePinned n
                        if (n == 0 || state.load() != OPEN) return@usePinned -1
                        if (errno != EINTR) throw TransportException("Read failed (errno $errno)")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                -1
            }
        }
    }

    override suspend fun write(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        using {
            if (data.isEmpty()) return@using
            val tlsSocket = ssl
            data.usePinned { pinned ->
                var offset = 0
                while (offset < data.size) {
                    val n = if (tlsSocket != null) {
                        SSL_write(tlsSocket, pinned.addressOf(offset), data.size - offset)
                    } else {
                        send(fd, pinned.addressOf(offset), (data.size - offset).convert(), MSG_NOSIGNAL).toInt()
                    }
                    if (n > 0) {
                        offset += n
                    } else if (tlsSocket == null && errno == EINTR) {
                        continue
                    } else {
                        throw TransportException("Write failed")
                    }
                }
            }
        }
    }

    override suspend fun startTls(serverName: String): Unit = withContext(Dispatchers.IO) { upgrade(serverName) }

    internal fun upgrade(serverName: String): Unit = using {
        check(ssl == null) { "TLS is already established" }
        val pinned = config.pinnedCertificateSha256.isNotEmpty()
        val session = tether_tls_new(fd, serverName, if (pinned) 0 else 1)
            ?: throw TlsVerificationException("Cannot set up TLS: ${lastTlsError()}", null)
        if (SSL_connect(session) != 1) {
            val verify = SSL_get_verify_result(session)
            val reason = if (verify != 0L) X509_verify_cert_error_string(verify)?.toKString() else lastTlsError()
            val fingerprint = leafDer(session)?.let(::sha256Hex)
            SSL_free(session)
            throw TlsVerificationException("TLS handshake with $serverName failed: $reason", fingerprint)
        }
        val der = leafDer(session)
        if (der == null) {
            SSL_free(session)
            throw TlsVerificationException("Server sent no certificate", null)
        }
        val fingerprint = sha256Hex(der)
        if (pinned && (fingerprint !in config.pinnedCertificateSha256 || tether_peer_valid_now(session) != 1)) {
            SSL_free(session)
            throw TlsVerificationException("Certificate $fingerprint is not pinned or has expired", fingerprint)
        }
        ssl = session
        val exporter = memScoped {
            val out = allocArray<UByteVar>(32)
            if (tether_tls_exporter(session, out) == 1) out.readBytes(32) else null
        }
        val endPoint = when (tether_peer_sig_bits(session)) {
            0 -> null
            else -> digest(tether_peer_sig_bits(session), der)
        }
        channelBindings = listOfNotNull(
            exporter?.let { TlsChannelBinding("tls-exporter", it) },
            endPoint?.let { TlsChannelBinding("tls-server-end-point", it) },
        )
        tls = TlsInfo(
            protocol = SSL_get_version(session)?.toKString().orEmpty(),
            cipherSuite = tether_tls_cipher(session)?.toKString().orEmpty(),
            peerCertificateSha256 = fingerprint,
            pinned = pinned,
        )
    }

    override fun close() {
        if (!state.compareAndSet(OPEN, CLOSING)) return
        shutdown(fd, SHUT_RDWR) // wakes a blocked read
        if (users.load() == 0) release()
    }

    /** Runs [block] while counting as a user; the last user out after close() frees everything. */
    private inline fun <T> using(block: () -> T): T {
        users.incrementAndFetch()
        try {
            if (state.load() != OPEN) throw TransportException("Connection closed")
            return block()
        } finally {
            if (users.decrementAndFetch() == 0 && state.load() == CLOSING) release()
        }
    }

    private fun release() {
        if (!state.compareAndSet(CLOSING, CLOSED)) return
        ssl?.let { SSL_free(it) }
        ssl = null
        close(fd)
    }

    private fun leafDer(session: CPointer<SSL>): ByteArray? {
        val buffer = ByteArray(16_384)
        val length = buffer.usePinned { tether_peer_der(session, it.addressOf(0).reinterpret(), buffer.size) }
        return if (length > 0) buffer.copyOf(length) else null
    }

    private fun lastTlsError(): String = memScoped {
        val buf = allocArray<ByteVar>(256)
        tether_tls_error(buf, 256)
        buf.toKString()
    }

    private companion object {
        const val OPEN = 0
        const val CLOSING = 1
        const val CLOSED = 2
        const val MSG_NOSIGNAL = 0x4000
    }
}

/** SHA-2 through OpenSSL. */
internal fun digest(bits: Int, data: ByteArray): ByteArray {
    val out = ByteArray(64)
    val input = if (data.isEmpty()) ByteArray(1) else data
    val n = input.usePinned { i ->
        out.usePinned { o -> tether_digest(bits, i.addressOf(0).reinterpret(), data.size, o.addressOf(0).reinterpret()) }
    }
    check(n > 0) { "Digest failed" }
    return out.copyOf(n)
}

internal fun sha256Hex(data: ByteArray): String =
    digest(256, data).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
