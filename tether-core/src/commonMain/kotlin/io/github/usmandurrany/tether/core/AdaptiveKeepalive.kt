package io.github.usmandurrany.tether.core

/** Bounds for the idle keepalive (design §4.3). */
public data class KeepalivePolicy(
    /** Interval on a network we know nothing about yet. */
    val initialMillis: Long = 60_000,
    val minMillis: Long = 30_000,
    /** Above ~4.5 minutes most mobile NATs and Android's doze windows work against us anyway. */
    val maxMillis: Long = 270_000,
    val stepMillis: Long = 30_000,
    /** Idle probes that must succeed at an interval before trying a longer one. */
    val successesToGrow: Int = 3,
) {
    init {
        require(minMillis in 1..initialMillis && initialMillis <= maxMillis) { "Need min <= initial <= max" }
        require(stepMillis > 0 && successesToGrow >= 1)
    }
}

/**
 * Learns, per network, the longest idle interval that keeps the connection
 * alive through NATs and firewalls, so the radio wakes as rarely as possible.
 *
 * The interval grows one step after a run of successful idle probes and drops
 * one step when the connection dies while idle. A death also caps that network
 * just below the failing interval, so it does not keep climbing back into the
 * same NAT timeout. Networks are keyed by an opaque id (for example a hash of
 * the Wi-Fi SSID or the carrier); [snapshot] and [restore] let the app persist
 * what was learned.
 *
 * Not thread-safe: the supervisor's actor owns it.
 */
public class AdaptiveKeepalive(private val policy: KeepalivePolicy = KeepalivePolicy()) {
    private class Learned(var interval: Long, var ceiling: Long, var successes: Int = 0)

    private val networks = HashMap<String, Learned>()

    private fun learned(network: String?): Learned =
        networks.getOrPut(network ?: UNKNOWN) { Learned(policy.initialMillis, policy.maxMillis) }

    public fun intervalFor(network: String?): Long = learned(network).interval

    /** An idle probe at the current interval was answered. */
    public fun onIdleSuccess(network: String?) {
        val l = learned(network)
        if (++l.successes >= policy.successesToGrow) {
            l.successes = 0
            l.interval = minOf(l.ceiling, l.interval + policy.stepMillis)
        }
    }

    /** The connection died during an idle period at the current interval. */
    public fun onIdleDeath(network: String?) {
        val l = learned(network)
        l.successes = 0
        l.ceiling = maxOf(policy.minMillis, l.interval - policy.stepMillis)
        l.interval = maxOf(policy.minMillis, l.interval - policy.stepMillis)
    }

    /** Learned intervals and ceilings by network id, for persistence. */
    public fun snapshot(): Map<String, Pair<Long, Long>> = networks.mapValues { (_, l) -> l.interval to l.ceiling }

    public fun restore(saved: Map<String, Pair<Long, Long>>) {
        for ((network, value) in saved) {
            val ceiling = value.second.coerceIn(policy.minMillis, policy.maxMillis)
            networks[network] = Learned(value.first.coerceIn(policy.minMillis, ceiling), ceiling)
        }
    }

    private companion object {
        const val UNKNOWN = "\u0000unknown"
    }
}
