package io.github.usmandurrany.tether.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TCP/TLS transport on the JVM and Android, using blocking sockets on
 * [Dispatchers.IO]. Only JDK APIs available on Android API 26 are used.
 *
 * TLS 1.2 is the minimum and 1.3 is preferred. The certificate is checked
 * against the XMPP domain with the platform trust store and hostname
 * verification, or against [TlsConfig.pinnedCertificateSha256] when pins are
 * configured.
 */
public class JvmSocketTransportFactory(
    private val config: TlsConfig = TlsConfig(),
    private val tlsProvider: TlsProvider = PlatformTlsProvider,
) : XmppTransportFactory {
    override suspend fun connect(endpoint: Endpoint, serverName: String): XmppTransport {
        // Closing the socket is the only way to stop a blocking connect or handshake.
        val current = AtomicReference<Socket?>(null)
        val cancelled = AtomicBoolean(false)
        return blocking(onCancel = { cancelled.set(true); runCatching { current.get()?.close() } }) {
            val socket = connectAny(endpoint) { current.set(it); if (cancelled.get()) it.close() }
            try {
                val transport = JvmSocketTransport(endpoint, socket, config, tlsProvider)
                if (endpoint.directTls) transport.upgrade(serverName)
                transport
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }
    }

    /**
     * Tries every address of the host in turn, so a broken IPv6 route (common on
     * mobile networks) falls through to IPv4 instead of failing the endpoint.
     */
    private fun connectAny(endpoint: Endpoint, created: (Socket) -> Unit): Socket {
        val addresses = InetAddress.getAllByName(endpoint.host).take(MAX_ADDRESSES)
        var last: Exception? = null
        for (address in addresses) {
            val socket = Socket()
            try {
                created(socket)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(address, endpoint.port), config.connectTimeoutMillis.toInt())
                return socket
            } catch (e: Exception) {
                runCatching { socket.close() }
                last = e
            }
        }
        throw last ?: java.net.UnknownHostException(endpoint.host)
    }

    private companion object {
        const val MAX_ADDRESSES = 4
    }
}

internal class JvmSocketTransport(
    override val endpoint: Endpoint,
    private var socket: Socket,
    private val config: TlsConfig,
    private val tlsProvider: TlsProvider = PlatformTlsProvider,
) : XmppTransport {
    private var input: InputStream = socket.getInputStream()
    private var output: OutputStream = socket.getOutputStream()

    @Volatile
    override var tls: TlsInfo? = null
        private set

    @Volatile
    override var channelBindings: List<TlsChannelBinding> = emptyList()
        private set

    // Reads and the handshake block and ignore coroutine cancellation, so cancelling them
    // closes the socket: a cancelled connection attempt then ends at once instead of
    // holding up the next one. Only the connection's own reader reads, so nothing else
    // can close it this way. Writes come from callers of send(), whose cancellation
    // must not end the connection, so they keep the plain dispatch.
    override suspend fun read(buffer: ByteArray): Int = blocking(::close) { input.read(buffer) }

    override suspend fun write(data: ByteArray): Unit = withContext(Dispatchers.IO) {
        output.write(data)
        output.flush()
    }

    override suspend fun startTls(serverName: String): Unit = blocking(::close) { upgrade(serverName) }

    override fun close() {
        runCatching { socket.close() }
    }

    internal fun upgrade(serverName: String) {
        check(tls == null) { "TLS is already established" }
        val trust = RecordingTrustManager(config.pinnedCertificateSha256)
        val context = tlsProvider.sslContext(trust)
        val ssl = context.socketFactory.createSocket(socket, serverName, endpoint.port, true) as SSLSocket
        ssl.enabledProtocols = ssl.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        check(ssl.enabledProtocols.isNotEmpty()) { "Neither TLS 1.2 nor TLS 1.3 is available" }
        ssl.sslParameters = ssl.sslParameters.apply {
            // SNI cannot carry IP literals; skip it for those.
            runCatching { SNIHostName(serverName) }.getOrNull()?.let { serverNames = listOf(it) }
            // Hostname verification against the XMPP domain. A pinned certificate is an
            // exact match on the certificate itself, so the name check is not needed then.
            if (config.pinnedCertificateSha256.isEmpty()) endpointIdentificationAlgorithm = "HTTPS"
        }
        try {
            // A handshake that stalls (a link that died without closing) would otherwise wait forever.
            ssl.soTimeout = config.handshakeTimeoutMillis.toInt()
            ssl.startHandshake()
            ssl.soTimeout = 0 // Liveness of an open stream is the supervisor's probe.
        } catch (e: Exception) {
            runCatching { ssl.close() }
            // Only a refused certificate is final. A reset, timeout or closed link mid-handshake
            // is a network problem, and retrying will fix it.
            if (trust.rejected || generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException }) {
                throw TlsVerificationException("TLS handshake with $serverName failed: ${e.message}", trust.seenFingerprint, e)
            }
            throw IOException("TLS handshake with $serverName failed: ${e.message}", e)
        }
        val leaf = ssl.session.peerCertificates.first() as X509Certificate
        if (config.pinnedCertificateSha256.isEmpty() && !HostnameCheck.matches(leaf, serverName)) {
            runCatching { ssl.close() }
            throw TlsVerificationException("The certificate does not name $serverName", sha256Hex(leaf.encoded))
        }
        val exporter = try {
            tlsProvider.tlsExporter(ssl)
        } catch (e: Exception) {
            null // Without it, SCRAM falls back to tls-server-end-point or no binding.
        }
        socket = ssl
        input = ssl.inputStream
        output = ssl.outputStream
        channelBindings = listOfNotNull(
            exporter?.let { TlsChannelBinding("tls-exporter", it) },
            tlsServerEndPoint(leaf),
        )
        tls = TlsInfo(
            protocol = ssl.session.protocol,
            cipherSuite = ssl.session.cipherSuite,
            peerCertificateSha256 = sha256Hex(leaf.encoded),
            pinned = config.pinnedCertificateSha256.isNotEmpty(),
        )
    }
}

/**
 * Delegates to the platform trust store, or checks pins instead when configured.
 * Records the leaf fingerprint either way, so a refused certificate can be
 * reported to the user (who may then decide to pin it).
 */
private class RecordingTrustManager(private val pins: Set<String>) : X509TrustManager {
    private val platform: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    @Volatile
    var seenFingerprint: String? = null

    /** The certificate was checked and refused (as opposed to the handshake failing for another reason). */
    @Volatile
    var rejected = false

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            check(chain, authType)
        } catch (e: CertificateException) {
            rejected = true
            throw e
        }
    }

    private fun check(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Server sent no certificate")
        val fingerprint = sha256Hex(chain[0].encoded)
        seenFingerprint = fingerprint
        if (pins.isNotEmpty()) {
            if (fingerprint !in pins) throw CertificateException("Certificate $fingerprint is not pinned")
            chain[0].checkValidity()
            return
        }
        platform.checkServerTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
        throw CertificateException("Client certificates are not checked here")

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        if (pins.isNotEmpty()) emptyArray() else platform.acceptedIssuers
}

/**
 * Runs blocking socket work on [Dispatchers.IO]. If the caller is cancelled,
 * [onCancel] closes the socket, which makes the blocked call throw, so
 * cancellation takes effect at once.
 */
internal suspend fun <T> blocking(onCancel: () -> Unit, block: () -> T): T = coroutineScope {
    val work = async(Dispatchers.IO) { block() }
    try {
        work.await()
    } catch (e: CancellationException) {
        onCancel()
        throw e
    }
}

/**
 * `tls-server-end-point` (RFC 5929 §4.1): hash of the server certificate using the
 * hash from its signature algorithm, upgraded to SHA-256 for MD5 and SHA-1. Returns
 * null for algorithms without a clear hash (for example Ed25519), where the
 * binding is undefined.
 *
 * `tls-exporter` (RFC 9266) needs keying-material export, which the JDK does not
 * expose; [ConscryptTlsProvider] adds it.
 */
internal fun tlsServerEndPoint(certificate: X509Certificate): TlsChannelBinding? {
    val algorithm = certificate.sigAlgName.uppercase()
    val digest = when {
        algorithm.startsWith("SHA512") -> "SHA-512"
        algorithm.startsWith("SHA384") -> "SHA-384"
        algorithm.startsWith("SHA256") || algorithm.startsWith("SHA1") || algorithm.startsWith("MD5") -> "SHA-256"
        else -> return null
    }
    return TlsChannelBinding("tls-server-end-point", MessageDigest.getInstance(digest).digest(certificate.encoded))
}

internal fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
