package io.github.usmandurrany.tether.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the OS reports about the default network, reduced to what a connection needs. */
public data class NetworkSnapshot(
    val available: Boolean,
    /** Unique for each connection to a network: a change means a switch. */
    val id: String?,
    /** Stable for a kind of network ("wifi", "cell:310260"), for keepalive learning. */
    val kind: String?,
)

/**
 * Watches the default network with `registerDefaultNetworkCallback` and reports
 * changes after [debounceMillis] of quiet, because the OS sends bursts of
 * callbacks during a switch (lost, available, capabilities, link properties).
 * Only distinct snapshots are reported.
 */
public class AndroidNetworkMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 500,
    private val onChange: (NetworkSnapshot) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val lock = Any()
    private var pending: Job? = null
    private var reported: NetworkSnapshot? = null
    private var defaultNetwork: Network? = null
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) { defaultNetwork = network }
            update(network, connectivity.getNetworkCapabilities(network))
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(lock) { defaultNetwork = network }
            update(network, capabilities)
        }

        override fun onLost(network: Network) {
            val wasDefault = synchronized(lock) {
                (defaultNetwork == network).also { if (it) defaultNetwork = null }
            }
            if (wasDefault) post(NetworkSnapshot(available = false, id = null, kind = null))
        }
    }

    /** Starts watching. Reports the current state first. */
    public fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        val active = connectivity.activeNetwork
        if (active == null) post(NetworkSnapshot(false, null, null), immediately = true)
        // Calls onAvailable right away when there is a default network.
        connectivity.registerDefaultNetworkCallback(callback)
    }

    public fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            pending?.cancel()
            pending = null
        }
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    private fun update(network: Network, capabilities: NetworkCapabilities?) {
        val usable = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        post(
            if (usable) NetworkSnapshot(true, network.networkHandle.toString(), kindOf(capabilities!!))
            else NetworkSnapshot(false, null, null),
        )
    }

    private fun kindOf(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
            // Carriers differ in NAT timeouts; the operator code needs no permission.
            val operator = runCatching { telephony?.networkOperator }.getOrNull()
            if (operator.isNullOrEmpty()) "cell" else "cell:$operator"
        }
        else -> "other"
    }

    private fun post(snapshot: NetworkSnapshot, immediately: Boolean = false) {
        synchronized(lock) {
            if (!started) return
            pending?.cancel()
            pending = scope.launch {
                if (!immediately) delay(debounceMillis)
                val changed = synchronized(lock) {
                    (snapshot != reported).also { if (it) reported = snapshot }
                }
                if (changed) onChange(snapshot)
            }
        }
    }
}
