package io.github.usmandurrany.tether.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * A [KeyValueStore] in one properties file, for desktop and server JVMs.
 * Writes go to a temporary file that is then moved over the old one, so a crash
 * never leaves a half-written file. On POSIX systems the file is readable by
 * its owner only.
 *
 * The file is not encrypted. Put it where only this user can read it, or wrap
 * secrets with the OS keychain; on Android use `TetherAndroid.secureStore`.
 */
public class FileKeyValueStore(private val file: Path) : KeyValueStore {
    private val mutex = Mutex()
    private var cache: Properties? = null

    override suspend fun get(key: String): String? = mutex.withLock { load().getProperty(key) }

    override suspend fun put(key: String, value: String?) {
        mutex.withLock {
            val props = load()
            if (value == null) props.remove(key) else props.setProperty(key, value)
            withContext(Dispatchers.IO) { write(props) }
        }
    }

    private suspend fun load(): Properties = cache ?: withContext(Dispatchers.IO) {
        Properties().apply { if (Files.exists(file)) Files.newInputStream(file).use { load(it) } }
    }.also { cache = it }

    private fun write(props: Properties) {
        val dir = file.toAbsolutePath().parent
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, file.fileName.toString(), ".tmp")
        try {
            runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
            Files.newOutputStream(temp).use { props.store(it, null) }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
