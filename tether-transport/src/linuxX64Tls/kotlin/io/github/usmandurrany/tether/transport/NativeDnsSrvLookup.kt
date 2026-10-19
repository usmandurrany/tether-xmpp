@file:OptIn(ExperimentalForeignApi::class)

package io.github.usmandurrany.tether.transport

import io.github.usmandurrany.tether.transport.tls.tether_udp_exchange
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import kotlin.random.Random

/**
 * SRV lookup over UDP to the resolvers in `/etc/resolv.conf`, with the same
 * checks as the JVM's [DnsSrvLookup]: random ids, a connected socket, and only
 * answers that echo the question. Truncated answers count as no answer.
 */
public class NativeDnsSrvLookup(
    private val servers: () -> List<String> = ::resolvConfServers,
    private val port: Int = 53,
    private val timeoutMillis: Int = 2_000,
) : SrvLookup {
    override suspend fun lookup(name: String): List<SrvRecord> = withContext(Dispatchers.IO) {
        for (server in servers()) {
            val id = Random.nextInt(0x10000)
            val query = runCatching { DnsSrv.query(name, id) }.getOrNull() ?: return@withContext emptyList()
            val answer = ByteArray(4096)
            val n = query.usePinned { q ->
                answer.usePinned { a ->
                    tether_udp_exchange(server, port, q.addressOf(0).reinterpret(), query.size, a.addressOf(0).reinterpret(), answer.size, timeoutMillis)
                }
            }
            if (n <= 0) continue
            when (val parsed = DnsSrv.parse(answer.copyOf(n), id, name)) {
                is DnsSrv.Answer.Records -> return@withContext parsed.records
                DnsSrv.Answer.NoSuchName -> return@withContext emptyList()
                else -> continue
            }
        }
        emptyList()
    }
}

/** `nameserver` lines of /etc/resolv.conf. */
public fun resolvConfServers(): List<String> {
    val file = fopen("/etc/resolv.conf", "r") ?: return emptyList()
    val servers = ArrayList<String>()
    try {
        memScoped {
            val line = allocArray<ByteVar>(512)
            while (fgets(line, 512, file) != null) {
                val parts = line.toKString().trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[0] == "nameserver") servers += parts[1]
            }
        }
    } finally {
        fclose(file)
    }
    return servers
}
