package io.github.usmandurrany.tether.transport

/** One SRV record (RFC 2782). */
public data class SrvRecord(val priority: Int, val weight: Int, val port: Int, val target: String)

/** Looks up SRV records. Returns an empty list when there are none or lookup is unavailable. */
public fun interface SrvLookup {
    public suspend fun lookup(name: String): List<SrvRecord>
}

/**
 * Turns an XMPP domain into an ordered list of endpoints (RFC 6120 §3.2, XEP-0368).
 *
 * Direct-TLS (`_xmpps-client._tcp`) and STARTTLS (`_xmpp-client._tcp`) records are
 * merged by priority, preferring direct TLS on ties, with RFC 2782 weighted ordering
 * inside a priority. A target of "." means the service is explicitly unavailable.
 * With no records at all, the domain itself on port 5222 is used.
 */
public class EndpointResolver(
    private val srv: SrvLookup,
    private val random: (Int) -> Int = { bound -> kotlin.random.Random.nextInt(bound) },
) {
    public suspend fun resolve(domain: String): List<Endpoint> {
        val direct = srv.lookup("_xmpps-client._tcp.$domain").map { it to true }
        val starttls = srv.lookup("_xmpp-client._tcp.$domain").map { it to false }
        val all = direct + starttls
        if (all.isEmpty()) return listOf(Endpoint(domain, 5222, directTls = false))
        if (all.all { it.first.target == "." || it.first.target.isEmpty() }) return emptyList()

        val result = ArrayList<Endpoint>()
        val byPriority = all.filter { it.first.target != "." }.groupBy { it.first.priority }
        for (priority in byPriority.keys.sorted()) {
            val group = byPriority.getValue(priority)
            // Direct TLS first within a priority, each family weighted-shuffled.
            for (family in listOf(true, false)) {
                weightedOrder(group.filter { it.second == family }.map { it.first })
                    .mapTo(result) { Endpoint(it.target.removeSuffix("."), it.port, family) }
            }
        }
        return result.distinct()
    }

    /** RFC 2782 selection: repeatedly pick a record with probability proportional to weight. */
    private fun weightedOrder(records: List<SrvRecord>): List<SrvRecord> {
        val pool = records.sortedBy { it.weight }.toMutableList() // zero weights first, as RFC 2782 suggests
        val out = ArrayList<SrvRecord>(pool.size)
        while (pool.isNotEmpty()) {
            val total = pool.sumOf { it.weight }
            if (total == 0) {
                out += pool
                break
            }
            val pick = random(total + 1)
            var running = 0
            val chosen = pool.first { running += it.weight; running >= pick }
            pool.remove(chosen)
            out += chosen
        }
        return out
    }
}
