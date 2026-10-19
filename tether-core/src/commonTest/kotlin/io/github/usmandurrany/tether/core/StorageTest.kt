package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.sasl.ScramAlgorithm
import io.github.usmandurrany.tether.sasl.ScramKeys
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest {
    private val message = element("message", Ns.CLIENT) {
        attribute("to", "romeo@example.net")
        attribute("id", "m1")
        element("body") { text("wherefore <art> thou & why") }
    }

    @Test
    fun clientStateRoundTrips() {
        val features = element("features", Ns.STREAMS) { element("sm", Ns.SM); element("csi", Ns.CSI) }
        val state = ClientState(
            resume = ClientState.SavedResume(
                FullJid.parse("juliet@example.com/phone"),
                SmSnapshot("res-1", "host:5222", 300, 4_294_967_295, 7, listOf(message)),
                features,
                setOf("urn:xmpp:carbons:2"),
                setOf("urn:xmpp:mam:2"),
            ),
            unsent = listOf(message.copy(attributes = listOf(io.github.usmandurrany.tether.xml.Attribute("id", "m2")))),
            keepalive = mapOf("wifi" to (90_000L to 270_000L)),
        )
        val back = ClientState.decode(state.encode())!!
        val request = back.resume!!.toRequest()
        assertEquals("res-1", request.streamManagement.resumptionId)
        assertEquals(4_294_967_295, request.streamManagement.inbound)
        assertEquals(7, request.streamManagement.acknowledged)
        assertEquals(listOf(message), request.streamManagement.pending)
        assertTrue(request.capabilities.hasStreamFeature("csi", Ns.CSI))
        assertTrue(request.capabilities.accountSupports("urn:xmpp:mam:2"))
        assertEquals("m2", back.unsent.single().attribute("id"))
        assertEquals(mapOf("wifi" to (90_000L to 270_000L)), back.keepalive)
    }

    @Test
    fun brokenSavesAreIgnored() {
        assertNull(ClientState.decode("not xml"))
        assertNull(ClientState.decode("<state xmlns='other'/>"))
        assertNull(ClientState.decode("<state xmlns='urn:tether:state:0'><resume id='x'/></state>"))
    }

    @Test
    fun credentialsRoundTripAndClear() = runTest {
        val vault = CredentialVault(MemoryKeyValueStore())
        val jid = BareJid.parse("juliet@example.com")
        vault.saveKeys(jid, ScramKeys(ScramAlgorithm.SHA512, byteArrayOf(1, 2, 3), 4096, byteArrayOf(4, 5), byteArrayOf(6)))
        val keys = vault.loadKeys(jid).single()
        assertEquals(ScramAlgorithm.SHA512, keys.algorithm)
        assertEquals(4096, keys.iterations)
        assertContentEquals(byteArrayOf(4, 5), keys.clientKey)
        vault.clear(jid)
        assertTrue(vault.loadKeys(jid).isEmpty())
    }
}
