package io.github.usmandurrany.tether.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * [LogUploader] on `HttpURLConnection`, available on every JVM and Android
 * without extra dependencies. Bodies are gzip-compressed: stanza XML shrinks
 * to roughly a tenth.
 *
 * On Android, cleartext `http://` is blocked by default from API 28; use
 * https, or allow your portal's host in the app's network security config.
 */
public class HttpLogUploader(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
    private val gzip: Boolean = true,
) : LogUploader {
    override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): Int = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val payload = if (gzip) {
                connection.setRequestProperty("Content-Encoding", "gzip")
                ByteArrayOutputStream(body.size / 4 + 64).also { out -> GZIPOutputStream(out).use { it.write(body) } }.toByteArray()
            } else {
                body
            }
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            // Drain the body so the connection can be reused.
            runCatching { (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() } }
            status
        } finally {
            connection.disconnect()
        }
    }
}

/** A [RemoteLogSink] that uploads with [HttpLogUploader]. */
public fun RemoteLogSink.Companion.http(config: RemoteLogConfig, scope: CoroutineScope): RemoteLogSink =
    RemoteLogSink(config, HttpLogUploader(), scope)
