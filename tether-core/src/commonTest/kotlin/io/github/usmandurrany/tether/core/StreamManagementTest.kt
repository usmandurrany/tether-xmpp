package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.xml.element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamManagementTest {
    private fun stanza(i: Int) = element("message", Ns.CLIENT) { attribute("id", "m$i") }

    @Test
    fun acksRemoveConfirmedStanzasInOrder() {
        val sm = StreamManagement("id", null, null)
        repeat(5) { sm.onSent(stanza(it)) }
        assertTrue(sm.onAck(3))
        assertEquals(listOf("m3", "m4"), sm.pending.map { it.attribute("id") })
        assertTrue(sm.onAck(3), "a repeated ack is harmless")
        assertEquals(2, sm.pending.size)
    }

    @Test
    fun ackingMoreThanWasSentIsRejected() {
        val sm = StreamManagement("id", null, null)
        repeat(2) { sm.onSent(stanza(it)) }
        assertFalse(sm.onAck(3))
        assertEquals(2, sm.pending.size)
    }

    @Test
    fun countersWrapAt32Bits() {
        val sm = StreamManagement("id", null, null)
        sm.restoreCounters(inbound = StreamManagement.MASK, acknowledged = StreamManagement.MASK - 1)
        repeat(4) { sm.onSent(stanza(it)) }
        // MASK - 1 + 4 wraps to 2 (mod 2^32).
        assertTrue(sm.onAck(2))
        assertTrue(sm.pending.isEmpty())
        assertEquals(2, sm.acknowledged)
        sm.onInboundStanza()
        assertEquals(0, sm.inbound)
    }

    @Test
    fun parseHRejectsOutOfRangeValues() {
        assertEquals(5, StreamManagement.parseH("5"))
        assertEquals(null, StreamManagement.parseH("-1"))
        assertEquals(null, StreamManagement.parseH("4294967296"))
        assertEquals(null, StreamManagement.parseH("x"))
    }
}
