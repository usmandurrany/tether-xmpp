package io.github.usmandurrany.tether.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Hashtable
import javax.naming.directory.InitialDirContext

/**
 * SRV lookup through the JDK's JNDI DNS provider, using the system resolvers.
 * JNDI does not exist on Android; there this returns no records, so resolution
 * falls back to the domain on port 5222; use [DnsSrvLookup] there instead.
 */
public class JndiSrvLookup : SrvLookup {
    override suspend fun lookup(name: String): List<SrvRecord> = withContext(Dispatchers.IO) {
        try {
            val env = Hashtable<String, String>().apply {
                put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")
                put("com.sun.jndi.dns.timeout.initial", "2000")
                put("com.sun.jndi.dns.timeout.retries", "2")
            }
            val context = InitialDirContext(env)
            try {
                val attribute = context.getAttributes(name, arrayOf("SRV")).get("SRV") ?: return@withContext emptyList()
                (0 until attribute.size()).mapNotNull { parse(attribute.get(it).toString()) }
            } finally {
                context.close()
            }
        } catch (t: Throwable) {
            // NXDOMAIN, no SRV records, no network, or no JNDI (Android): all mean "no records".
            emptyList()
        }
    }

    private fun parse(record: String): SrvRecord? {
        val parts = record.trim().split(Regex("\\s+"))
        if (parts.size != 4) return null
        val (priority, weight, port) = parts.take(3).map { it.toIntOrNull() ?: return null }
        if (port !in 1..65535 && parts[3] != ".") return null
        return SrvRecord(priority, weight, port, parts[3])
    }
}
