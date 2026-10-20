package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileMessageStoreTest {
    private val bob = BareJid.parse("bob@example.com")

    private fun message(i: Int, status: MessageStatus = MessageStatus.SENT) =
        Message("m$i", bob, outgoing = true, body = "message $i\nwith a newline", timestamp = i.toLong(), status = status)

    @Test
    fun passesTheStoreCheck() = runBlocking {
        MessageStoreCheck.run(FileMessageStore(Files.createTempDirectory("tether").resolve("a.messages")))
    }

    @Test
    fun messagesSurviveANewInstanceAndTheFileIsPrivate() = runBlocking<Unit> {
        val file = Files.createTempDirectory("tether").resolve("a.messages")
        FileMessageStore(file).apply {
            save(listOf(message(1), message(2)))
            save(listOf(message(1, MessageStatus.READ)))
            delete(listOf(message(2)))
        }
        assertEquals(listOf(message(1, MessageStatus.READ)), FileMessageStore(file).load())
        runCatching { Files.getPosixFilePermissions(file) }.getOrNull()?.let {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), it)
        }
    }

    @Test
    fun aTornLastLineFromACrashIsSkipped() = runBlocking {
        val file = Files.createTempDirectory("tether").resolve("a.messages")
        FileMessageStore(file).save(listOf(message(1)))
        Files.writeString(file, "Sbm90IGJhc2U2NC", StandardOpenOption.APPEND)
        assertEquals(listOf(message(1)), FileMessageStore(file).load())
    }

    @Test
    fun oldCopiesAreCompactedAway() = runBlocking {
        val file = Files.createTempDirectory("tether").resolve("a.messages")
        val store = FileMessageStore(file)
        repeat(1_200) { store.save(listOf(message(1, if (it % 2 == 0) MessageStatus.SENT else MessageStatus.DELIVERED))) }
        assertTrue(Files.readAllLines(file).size < 1_000, "the file was rewritten")
        assertEquals(1, FileMessageStore(file).load().size)
        assertEquals(listOf("a.messages"), Files.list(file.parent).map { it.fileName.toString() }.toList(), "no temporary files left")
    }

    @Test
    fun clearRemovesTheFile() = runBlocking {
        val file = Files.createTempDirectory("tether").resolve("a.messages")
        val store = FileMessageStore(file)
        store.save(listOf(message(1)))
        store.clear()
        assertTrue(!Files.exists(file))
        assertTrue(store.load().isEmpty())
    }
}
