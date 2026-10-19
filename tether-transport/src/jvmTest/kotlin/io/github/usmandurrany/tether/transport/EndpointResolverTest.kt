package io.github.usmandurrany.tether.transport

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndpointResolverTest {
    private fun resolver(records: Map<String, List<SrvRecord>>) =
        EndpointResolver(SrvLookup { records[it].orEmpty() }, random = { 0 })

    @Test
    fun fallsBackToDomainWithoutRecords() = runBlocking {
        assertEquals(listOf(Endpoint("example.com", 5222, false)), resolver(emptyMap()).resolve("example.com"))
    }

    @Test
    fun ordersByPriorityThenPrefersDirectTls() = runBlocking {
        val r = resolver(
            mapOf(
                "_xmpp-client._tcp.example.com" to listOf(SrvRecord(10, 0, 5222, "a.example.com."), SrvRecord(5, 0, 5222, "b.example.com.")),
                "_xmpps-client._tcp.example.com" to listOf(SrvRecord(10, 0, 5223, "a.example.com."), SrvRecord(20, 0, 443, "c.example.com.")),
            ),
        )
        assertEquals(
            listOf(
                Endpoint("b.example.com", 5222, false),
                Endpoint("a.example.com", 5223, true),
                Endpoint("a.example.com", 5222, false),
                Endpoint("c.example.com", 443, true),
            ),
            r.resolve("example.com"),
        )
    }

    @Test
    fun dotTargetMeansServiceUnavailable() = runBlocking {
        val r = resolver(mapOf("_xmpp-client._tcp.example.com" to listOf(SrvRecord(0, 0, 0, "."))))
        assertTrue(r.resolve("example.com").isEmpty())
    }

    @Test
    fun weightedOrderIncludesEveryRecordOnce() = runBlocking {
        val records = (1..5).map { SrvRecord(0, it * 10, 5222, "h$it.example.com") }
        val r = EndpointResolver(SrvLookup { if (it.startsWith("_xmpp-")) records else emptyList() })
        repeat(50) {
            assertEquals(records.map { it.target }.toSet(), r.resolve("example.com").map { it.host }.toSet())
            assertEquals(5, r.resolve("example.com").size)
        }
    }
}
