package io.github.usmandurrany.tether.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * SRV lookup that talks DNS itself, over UDP with a TCP fallback for truncated
 * answers. For Android, where there is no JNDI: pass the network's DNS servers
 * (from `LinkProperties.getDnsServers()`).
 *
 * Each query uses a random id and a fresh socket (so a random source port), and
 * only an answer from the queried server that echoes the question is accepted.
 * Any failure means "no records", so resolution falls back to the domain itself.
 *
 * @param servers asked in order, on every lookup, so a network change is picked up.
 */
public class DnsSrvLookup(
    private val servers: () -> List<InetSocketAddress>,
    private val timeoutMillis: Int = 2_000,
) : SrvLookup {
    private val random = SecureRandom()

    override suspend fun lookup(name: String): List<SrvRecord> = withContext(Dispatchers.IO) {
        for (server in servers()) {
            when (val answer = ask(server, name)) {
                is DnsSrv.Answer.Records -> return@withContext answer.records
                DnsSrv.Answer.NoSuchName -> return@withContext emptyList()
                else -> continue // no answer, failure, or truncated over TCP too: next server
            }
        }
        emptyList()
    }

    private fun ask(server: InetSocketAddress, name: String): DnsSrv.Answer? {
        val id = random.nextInt(0x10000)
        val query = try {
            DnsSrv.query(name, id)
        } catch (e: IllegalArgumentException) {
            return DnsSrv.Answer.NoSuchName // not a valid DNS name
        }
        val udp = runCatching { overUdp(server, query, id, name) }.getOrNull()
        if (udp != DnsSrv.Answer.Truncated) return udp
        return runCatching { overTcp(server, query, id, name) }.getOrNull()
    }

    private fun overUdp(server: InetSocketAddress, query: ByteArray, id: Int, name: String): DnsSrv.Answer? {
        DatagramSocket().use { socket ->
            socket.connect(server) // the OS then drops datagrams from any other address
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(4096)
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (true) {
                val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                if (remaining <= 0) return null
                socket.soTimeout = remaining
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    return null
                }
                // A reply to some other query (or a spoof) is skipped, not trusted.
                DnsSrv.parse(buffer.copyOf(packet.length), id, name)?.let { return it }
            }
        }
    }

    private fun overTcp(server: InetSocketAddress, query: ByteArray, id: Int, name: String): DnsSrv.Answer? =
        Socket().use { socket ->
            socket.connect(server, timeoutMillis)
            socket.soTimeout = timeoutMillis
            DataOutputStream(socket.getOutputStream()).apply {
                writeShort(query.size)
                write(query)
                flush()
            }
            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            val response = ByteArray(length).also { input.readFully(it) }
            DnsSrv.parse(response, id, name)?.takeIf { it != DnsSrv.Answer.Truncated }
        }
}
