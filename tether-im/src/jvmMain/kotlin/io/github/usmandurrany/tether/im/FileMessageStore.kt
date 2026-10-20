package io.github.usmandurrany.tether.im

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/**
 * A [MessageStore] in one file, for desktop and server JVMs: the default of
 * `TetherJvm.chat`. Each change is appended as one line, so a save is cheap
 * and a crash can at worst lose the last line; the file is rewritten without
 * the old copies when it grows to several times what it holds. On POSIX
 * systems only the owner can read it.
 *
 * The file is not encrypted. Every message is kept in memory by [TetherChat]
 * anyway, so this suits chat histories of up to some hundred thousand
 * messages; use a database for more.
 */
public class FileMessageStore(private val file: Path) : MessageStore {
    private val mutex = Mutex()
    private var live: LinkedHashMap<String, String>? = null
    private var lines = 0

    override suspend fun load(): List<Message> = mutex.withLock {
        io { messages().values.mapNotNull(MessageCodec::decode) }
    }

    override suspend fun save(messages: List<Message>) {
        if (messages.isEmpty()) return
        mutex.withLock {
            io {
                val map = messages()
                append(
                    messages.map { m ->
                        val text = MessageCodec.encode(m)
                        map[MessageCodec.keyOf(m)] = text
                        SAVE + encode(text)
                    },
                )
                compactIfLarge(map)
            }
        }
    }

    override suspend fun delete(messages: List<Message>) {
        if (messages.isEmpty()) return
        mutex.withLock {
            io {
                val map = messages()
                append(
                    messages.map { m ->
                        val key = MessageCodec.keyOf(m)
                        map.remove(key)
                        DELETE + encode(key)
                    },
                )
                compactIfLarge(map)
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            io {
                Files.deleteIfExists(file)
                live = LinkedHashMap()
                lines = 0
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** What the file holds now, read once. A torn or unreadable line (a crash mid-write) is skipped. */
    private fun messages(): LinkedHashMap<String, String> {
        live?.let { return it }
        val map = LinkedHashMap<String, String>()
        var count = 0
        if (Files.exists(file)) {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).useLines { all ->
                for (line in all) {
                    count++
                    val payload = runCatching { decode(line.substring(1)) }.getOrNull() ?: continue
                    when (line.firstOrNull()) {
                        SAVE -> MessageCodec.decode(payload)?.let { map[MessageCodec.keyOf(it)] = payload }
                        DELETE -> map.remove(payload)
                    }
                }
            }
        }
        live = map
        lines = count
        return map
    }

    private fun append(newLines: List<String>) {
        Files.createDirectories(file.toAbsolutePath().parent)
        val fresh = !Files.exists(file)
        Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { out ->
            newLines.forEach { out.line(it) }
        }
        if (fresh) private(file)
        lines += newLines.size
    }

    /** Rewrites the file with one line per message once old copies outnumber them. */
    private fun compactIfLarge(map: Map<String, String>) {
        if (lines < COMPACT_MIN_LINES || lines < map.size * 3) return
        val dir = file.toAbsolutePath().parent
        val temp = Files.createTempFile(dir, file.fileName.toString(), ".tmp")
        try {
            private(temp)
            Files.newBufferedWriter(temp, StandardCharsets.UTF_8).use { out -> map.values.forEach { out.line(SAVE + encode(it)) } }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            lines = map.size
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun BufferedWriter.line(text: String) {
        write(text)
        newLine()
    }

    private fun private(path: Path) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
    }

    private companion object {
        const val SAVE = 'S'
        const val DELETE = 'D'
        const val COMPACT_MIN_LINES = 1_000

        // One record per line whatever the text holds.
        fun encode(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))

        fun decode(text: String): String = String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8)
    }
}
