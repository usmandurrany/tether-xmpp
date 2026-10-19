package io.github.usmandurrany.tether.transport

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeTransportTest {
    @Test
    fun opensslIsLinkedAndHashes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".encodeToByteArray()))
        assertEquals(64, digest(512, ByteArray(0)).size)
    }

    @Test
    fun aRefusedConnectionFailsCleanly() = runBlocking<Unit> {
        // Port 1 on loopback has no listener in CI.
        assertFailsWith<TransportException> {
            NativeSocketTransportFactory().connect(Endpoint("127.0.0.1", 1, directTls = false), "localhost")
        }
    }

    @Test
    fun readsResolvConf() {
        resolvConfServers().forEach { assertEquals(it.trim(), it) }
    }
}
