package io.github.usmandurrany.tether.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostnameCheckTest {
    private fun ok(domain: String, dns: List<String> = emptyList(), ips: List<String> = emptyList(), cn: String? = null) =
        HostnameCheck.matches(domain, dns, ips, cn)

    @Test
    fun exactNamesMatchIgnoringCaseAndTrailingDots() {
        assertTrue(ok("Example.COM", listOf("example.com")))
        assertTrue(ok("example.com.", listOf("example.com")))
        assertFalse(ok("example.org", listOf("example.com")))
    }

    @Test
    fun wildcardsCoverExactlyOneLeftmostLabel() {
        assertTrue(ok("chat.example.com", listOf("*.example.com")))
        assertFalse(ok("example.com", listOf("*.example.com")))
        assertFalse(ok("a.chat.example.com", listOf("*.example.com")))
        assertFalse(ok("example.com", listOf("*.com")), "never a wildcard over a top-level domain")
        assertFalse(ok("chat.example.com", listOf("c*.example.com")), "partial wildcards are refused")
    }

    @Test
    fun commonNameOnlyWhenThereAreNoDnsNames() {
        assertTrue(ok("nas.local", cn = "nas.local"))
        assertFalse(ok("nas.local", dns = listOf("other.local"), cn = "nas.local"))
    }

    @Test
    fun ipLiteralsNeedAnIpEntry() {
        assertTrue(ok("192.168.1.10", ips = listOf("192.168.1.10")))
        assertFalse(ok("192.168.1.10", dns = listOf("192.168.1.10")))
        assertTrue(ok("::1", ips = listOf("0:0:0:0:0:0:0:1")))
    }

    @Test
    fun internationalisedNamesCompareInAsciiForm() {
        assertTrue(ok("bücher.example", listOf("xn--bcher-kva.example")))
    }
}
