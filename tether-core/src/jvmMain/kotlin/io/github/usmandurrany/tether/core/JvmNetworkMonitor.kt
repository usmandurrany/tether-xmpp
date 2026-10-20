package io.github.usmandurrany.tether.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface

/**
 * Tells [controls] when the network comes and goes, which the JVM has no event
 * for: it looks at the network interfaces every [intervalMillis]. A lost
 * network pauses retries; a new or changed one reconnects (or probes) at once.
 * [TetherJvm.client] and `TetherJvm.chat` start one for you.
 */
public class JvmNetworkMonitor(private val controls: ConnectionControls, private val intervalMillis: Long = 3_000) {
    public fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        var last: String? = null
        while (isActive) {
            val id = currentNetworkId()
            // Until a network has been seen, nothing is reported: on a system whose
            // interfaces look unusual, the client then simply behaves as if always online.
            if (id != last && (id != null || last != null)) controls.networkChanged(available = id != null, id = id)
            last = id
            delay(intervalMillis)
        }
    }

    /** A fingerprint of the usable interfaces and their addresses, or null when there is none. */
    private fun currentNetworkId(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .mapNotNull { nic ->
                val addresses = nic.inetAddresses.toList().filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
                if (addresses.isEmpty()) null else nic.name + "=" + addresses.joinToString(",") { it.hostAddress }
            }
            .sorted()
            .joinToString(";")
            .ifEmpty { null }
    }.getOrElse { "unknown" } // if the OS won't say, assume a network rather than blocking every attempt
}
