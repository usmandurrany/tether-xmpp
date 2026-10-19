package io.github.usmandurrany.tether.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileKeyValueStoreTest {
    @Test
    fun valuesSurviveANewInstanceAndTheFileIsPrivate() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("tether-store")
        val file = dir.resolve("state.properties")
        FileKeyValueStore(file).apply {
            put("a", "one\nwith = odd: characters")
            put("b", "two")
            put("b", null)
        }
        val again = FileKeyValueStore(file)
        assertEquals("one\nwith = odd: characters", again.get("a"))
        assertNull(again.get("b"))
        runCatching { Files.getPosixFilePermissions(file) }.getOrNull()?.let {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), it)
        }
        assertEquals(listOf("state.properties"), Files.list(dir).map { it.fileName.toString() }.toList(), "no temporary files left")
    }
}
