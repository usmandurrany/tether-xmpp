package io.github.usmandurrany.tether.debug

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpLogUploaderTest {
    @Test
    fun postsGzippedJsonWithHeaders() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var body = ""
        var auth: String? = null
        var encoding: String? = null
        server.createContext("/api/v1/ingest") { exchange ->
            auth = exchange.requestHeaders.getFirst("Authorization")
            encoding = exchange.requestHeaders.getFirst("Content-Encoding")
            body = GZIPInputStream(exchange.requestBody).readBytes().decodeToString()
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()
        try {
            val status = runBlocking {
                HttpLogUploader().post(
                    "http://127.0.0.1:${server.address.port}/api/v1/ingest",
                    mapOf("Authorization" to "Bearer k"),
                    "{\"v\":1}".encodeToByteArray(),
                )
            }
            assertEquals(202, status)
            assertEquals("Bearer k", auth)
            assertEquals("gzip", encoding)
            assertEquals("{\"v\":1}", body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun detectsTheDesktopPlatform() {
        val device = DeviceInfo.current("id-1", appVersion = "2.0")
        assertTrue(device.platform in setOf("linux", "macos", "windows"), device.platform)
        assertTrue(device.model!!.startsWith("JVM "))
    }
}
