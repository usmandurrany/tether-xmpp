package io.github.usmandurrany.tether.transport

import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DnsSrvLookupTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val closeables = ArrayList<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
    }

    /** Answers with the query's id and question, and one SRV record (or the TC bit over UDP when [truncate]). */
    private fun answer(query: ByteArray, length: Int, truncate: Boolean, idOffset: Int = 0): ByteArray {
        val q = query.copyOf(length)
        val id = (((q[0].toInt() and 0xFF) shl 8) or (q[1].toInt() and 0xFF)) + idOffset
        val header = byteArrayOf(
            (id ushr 8).toByte(), id.toByte(),
            if (truncate) 0x83.toByte() else 0x81.toByte(), 0x80.toByte(),
            0, 1, 0, if (truncate) 0 else 1, 0, 0, 0, 0,
        )
        val target = byteArrayOf(4) + "xmpp".encodeToByteArray() + byteArrayOf(7) + "example".encodeToByteArray() + byteArrayOf(3) + "com".encodeToByteArray() + 0
        val rdata = byteArrayOf(0, 5, 0, 1, 0x14, 0x66) + target // priority 5, weight 1, port 5222
        val record = byteArrayOf(0xC0.toByte(), 12, 0, 33, 0, 1, 0, 0, 0, 60, 0, rdata.size.toByte()) + rdata
        return header + q.copyOfRange(12, length) + if (truncate) ByteArray(0) else record
    }

    private fun udpServer(truncate: Boolean = false, spoofFirst: Boolean = false, silent: Boolean = false): DatagramSocket {
        val socket = DatagramSocket(0, loopback).also { closeables += it }
        thread(isDaemon = true) {
            val buffer = ByteArray(512)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(packet) }.onFailure { return@thread }
                if (silent) continue
                if (spoofFirst) {
                    val spoof = answer(buffer, packet.length, truncate = false, idOffset = 1)
                    socket.send(DatagramPacket(spoof, spoof.size, packet.socketAddress))
                }
                val reply = answer(buffer, packet.length, truncate)
                socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
            }
        }
        return socket
    }

    private val expected = listOf(SrvRecord(5, 1, 5222, "xmpp.example.com"))

    @Test
    fun resolvesOverUdp() = runBlocking<Unit> {
        val server = udpServer()
        val lookup = DnsSrvLookup({ listOf(InetSocketAddress(loopback, server.localPort)) })
        assertEquals(expected, lookup.lookup("_xmpp-client._tcp.example.com"))
    }

    @Test
    fun skipsAnswersWithTheWrongId() = runBlocking<Unit> {
        val server = udpServer(spoofFirst = true)
        val lookup = DnsSrvLookup({ listOf(InetSocketAddress(loopback, server.localPort)) })
        assertEquals(expected, lookup.lookup("_xmpp-client._tcp.example.com"))
    }

    @Test
    fun retriesTruncatedAnswersOverTcp() = runBlocking<Unit> {
        val udp = udpServer(truncate = true)
        val tcp = ServerSocket(udp.localPort, 1, loopback).also { closeables += it }
        thread(isDaemon = true) {
            runCatching {
                tcp.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val query = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                    val reply = answer(query, query.size, truncate = false)
                    DataOutputStream(s.getOutputStream()).apply { writeShort(reply.size); write(reply); flush() }
                }
            }
        }
        val lookup = DnsSrvLookup({ listOf(InetSocketAddress(loopback, udp.localPort)) })
        assertEquals(expected, lookup.lookup("_xmpp-client._tcp.example.com"))
    }

    @Test
    fun aSilentServerFallsThroughToTheNext() = runBlocking<Unit> {
        val dead = udpServer(silent = true)
        val good = udpServer()
        val lookup = DnsSrvLookup({ listOf(InetSocketAddress(loopback, dead.localPort), InetSocketAddress(loopback, good.localPort)) }, timeoutMillis = 300)
        assertEquals(expected, lookup.lookup("_xmpp-client._tcp.example.com"))
    }

    @Test
    fun noServersMeansNoRecords() = runBlocking<Unit> {
        assertTrue(DnsSrvLookup({ emptyList() }).lookup("_xmpp-client._tcp.example.com").isEmpty())
    }
}
