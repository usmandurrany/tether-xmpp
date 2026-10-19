package io.github.usmandurrany.tether.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteLogSinkTest {
    private class Upload(val url: String, val headers: Map<String, String>, val body: String)

    /** Answers each upload with the next status from [statuses] (the last one repeats). */
    private class FakeUploader(vararg val statuses: Int) : LogUploader {
        val uploads = ArrayList<Upload>()
        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): Int {
            uploads += Upload(url, headers, body.decodeToString())
            return statuses[minOf(uploads.size - 1, statuses.size - 1)]
        }
    }

    private val config = RemoteLogConfig(
        endpoint = "https://logs.example.com/",
        ingestKey = "k1",
        device = DeviceInfo("install-1", "android", "14 (API 34)", "Google Pixel 8", "com.example", "1.0"),
        label = "beta-3",
    )

    private fun entry(seq: Long, level: LogLevel = LogLevel.DEBUG) =
        LogEntry(seq, 1_000 + seq, level, "stanza", "alice@example.com/phone", "<presence id='$seq'/>", Direction.SENT, StanzaSummary("presence", id = "$seq"))

    private fun TestScope.sink(uploader: LogUploader, config: RemoteLogConfig = this@RemoteLogSinkTest.config, scope: CoroutineScope = backgroundScope) =
        RemoteLogSink(config, uploader, scope, clock = { 5_000 }, random = Random(1))

    private fun seqs(upload: Upload): List<Int> = Regex("\"seq\":(\\d+)").findAll(upload.body).map { it.groupValues[1].toInt() }.toList()

    @Test
    fun batchesAfterTheLingerAndSendsTheDocumentedFormat() = runTest {
        val uploader = FakeUploader(200)
        val sink = sink(uploader)
        sink.write(entry(1))
        sink.write(entry(2))
        advanceTimeBy(1_999)
        assertEquals(0, uploader.uploads.size)
        advanceTimeBy(2)
        val upload = uploader.uploads.single()
        assertEquals("https://logs.example.com/api/v1/ingest", upload.url)
        assertEquals("Bearer k1", upload.headers["Authorization"])
        assertEquals(
            "{\"v\":1,\"device\":{\"installId\":\"install-1\",\"platform\":\"android\",\"osVersion\":\"14 (API 34)\"," +
                "\"model\":\"Google Pixel 8\",\"appId\":\"com.example\",\"appVersion\":\"1.0\"}," +
                "\"session\":{\"id\":\"${sink.sessionId}\",\"startedAt\":5000,\"label\":\"beta-3\"},\"dropped\":0,\"entries\":[" +
                "{\"seq\":1,\"ts\":1001,\"level\":\"debug\",\"tag\":\"stanza\",\"account\":\"alice@example.com/phone\",\"dir\":\"out\"," +
                "\"text\":\"<presence id='1'/>\",\"stanza\":{\"name\":\"presence\",\"id\":\"1\"}}," +
                "{\"seq\":2,\"ts\":1002,\"level\":\"debug\",\"tag\":\"stanza\",\"account\":\"alice@example.com/phone\",\"dir\":\"out\"," +
                "\"text\":\"<presence id='2'/>\",\"stanza\":{\"name\":\"presence\",\"id\":\"2\"}}]}",
            upload.body,
        )
        assertEquals(RemoteLogStats(queued = 0, uploaded = 2, lastUploadMillis = 5_000), sink.stats.value)
    }

    @Test
    fun retriesTheSameBatchWithBackoff() = runTest {
        val uploader = FakeUploader(503, 503, 200)
        val sink = sink(uploader)
        sink.write(entry(1))
        advanceTimeBy(2_001)
        assertEquals(1, uploader.uploads.size)
        assertTrue(sink.stats.value.lastError!!.contains("503"))
        sink.write(entry(2)) // arrives during the backoff; goes in the next batch
        advanceTimeBy(130_000)
        assertEquals(listOf(listOf(1), listOf(1), listOf(1), listOf(2)), uploader.uploads.map(::seqs))
        assertEquals(2, sink.stats.value.uploaded)
        assertEquals(null, sink.stats.value.lastError)
    }

    @Test
    fun wrongKeyWaitsTheLongestDelay() = runTest {
        val uploader = FakeUploader(401, 200)
        sink(uploader).write(entry(1))
        advanceTimeBy(2_001)
        advanceTimeBy(config.maxRetryDelayMillis - 1)
        assertEquals(1, uploader.uploads.size)
        advanceTimeBy(2)
        assertEquals(2, uploader.uploads.size)
    }

    @Test
    fun tooLargeSplitsInOrder() = runTest {
        val uploader = FakeUploader(413, 200)
        val sink = sink(uploader)
        (1L..4L).forEach { sink.write(entry(it)) }
        advanceTimeBy(2_001)
        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(1, 2), listOf(3, 4)), uploader.uploads.map(::seqs))
        assertEquals(4, sink.stats.value.uploaded)
    }

    @Test
    fun invalidBatchIsDroppedAndCounted() = runTest {
        val uploader = FakeUploader(400, 200)
        val sink = sink(uploader)
        sink.write(entry(1))
        advanceTimeBy(2_001)
        sink.write(entry(2))
        advanceTimeBy(2_001)
        assertEquals(1L, sink.stats.value.dropped)
        assertEquals(1L, sink.stats.value.uploaded)
        assertTrue("\"dropped\":1" in uploader.uploads[1].body)
    }

    @Test
    fun fullQueueDropsTheOldestAndReportsIt() = runTest {
        val uploader = FakeUploader(200)
        val sink = sink(uploader, config.copy(maxQueuedEntries = 3))
        (1L..5L).forEach { sink.write(entry(it)) }
        runCurrent()
        advanceTimeBy(2_001)
        // All five arrived before the uploader ran: 1 and 2 fell out of the three-entry queue.
        assertEquals(listOf(3, 4, 5), uploader.uploads.flatMap(::seqs))
        assertEquals(2L, sink.stats.value.dropped)
        assertTrue("\"dropped\":2" in uploader.uploads.single().body)
        assertEquals(0, sink.stats.value.queued)
    }

    @Test
    fun batchesAreCappedBySize() = runTest {
        val uploader = FakeUploader(200)
        val sink = sink(uploader, config.copy(maxBatchEntries = 2))
        (1L..5L).forEach { sink.write(entry(it)) }
        advanceTimeBy(10_000)
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), uploader.uploads.map(::seqs))
    }

    @Test
    fun flushSkipsTheLinger() = runTest {
        val uploader = FakeUploader(200)
        val sink = sink(uploader)
        sink.write(entry(1))
        runCurrent()
        assertTrue(sink.flush())
        assertEquals(1, uploader.uploads.size)
        assertTrue(sink.flush()) // nothing left: returns at once
    }

    @Test
    fun levelBelowTheMinimumIsNotUploaded() = runTest {
        val uploader = FakeUploader(200)
        val sink = sink(uploader, config.copy(minLevel = LogLevel.INFO))
        sink.write(entry(1, LogLevel.DEBUG))
        sink.write(entry(2, LogLevel.WARN))
        advanceTimeBy(2_001)
        assertEquals(listOf(listOf(2)), uploader.uploads.map(::seqs))
    }

    @Test
    fun escapesJson() {
        val out = StringBuilder()
        LogJson.string(out, "a\"b\\c\nd\u0001 ")
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\\u2028\"", out.toString())
    }

    @Test
    fun configHidesTheKey() {
        assertTrue("k1" !in config.toString())
    }
}
