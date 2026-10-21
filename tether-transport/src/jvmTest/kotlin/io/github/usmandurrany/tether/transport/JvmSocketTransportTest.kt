package io.github.usmandurrany.tether.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** How the transport behaves when the link misbehaves during the TLS handshake. */
class JvmSocketTransportTest {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val accepted = ArrayList<Socket>()

    @AfterTest
    fun tearDown() {
        runCatching { server.close() }
        synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }
    }

    /** Accepts connections and then does what [handle] says with each one. */
    private fun serve(handle: (Socket) -> Unit) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                synchronized(accepted) { accepted += socket }
                thread(isDaemon = true) { runCatching { handle(socket) } }
            }
        }
    }

    private val endpoint get() = Endpoint("127.0.0.1", server.localPort, directTls = true)

    @Test
    fun aStalledHandshakeTimesOutAsANetworkError() = runBlocking {
        serve { /* accepts, then says nothing: a link that died without closing */ }
        val factory = JvmSocketTransportFactory(TlsConfig(handshakeTimeoutMillis = 500))
        val start = TimeSource.Monotonic.markNow()
        assertFailsWith<IOException> { factory.connect(endpoint, "localhost") }
        assertTrue(start.elapsedNow().inWholeMilliseconds < 5_000, "gave up after the handshake timeout")
    }

    @Test
    fun aLinkClosedMidHandshakeIsRetryableNotACertificateError() = runBlocking {
        serve { socket ->
            socket.getInputStream().read(ByteArray(64)) // part of the ClientHello
            socket.close()
        }
        val factory = JvmSocketTransportFactory(TlsConfig())
        val error = runCatching { factory.connect(endpoint, "localhost") }.exceptionOrNull()
        assertTrue(error != null && error !is TlsVerificationException, "got $error")
    }

    @Test
    fun cancellingAStalledHandshakeEndsItAtOnce() = runBlocking {
        serve { }
        val factory = JvmSocketTransportFactory(TlsConfig(handshakeTimeoutMillis = 60_000))
        val attempt = async { runCatching { factory.connect(endpoint, "localhost") } }
        delay(300)
        val start = TimeSource.Monotonic.markNow()
        withTimeout(5_000) {
            attempt.cancel()
            attempt.join()
        }
        assertTrue(start.elapsedNow().inWholeMilliseconds < 2_000, "the blocked handshake was closed")
    }
}
