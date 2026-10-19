package io.github.usmandurrany.tether.debug

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Clock

/** Who is sending logs. The portal groups entries by [installId]. */
public data class DeviceInfo(
    /** Stable per app install: generate a random id once and store it. */
    val installId: String,
    /** `android`, `linux`, `macos`, `windows`, `ios`, ... */
    val platform: String,
    val osVersion: String? = null,
    val model: String? = null,
    /** Package name or bundle id of the host app. */
    val appId: String? = null,
    val appVersion: String? = null,
) {
    init {
        require(installId.isNotBlank() && platform.isNotBlank())
    }

    public companion object {
        /** Fills [platform], [osVersion] and [model] from the running system (Android is detected on the JVM). */
        public fun current(installId: String, appId: String? = null, appVersion: String? = null): DeviceInfo {
            val p = currentPlatform()
            return DeviceInfo(installId, p.platform, p.osVersion, p.model, appId, appVersion)
        }
    }
}

/**
 * Remote logging to a Tether log portal. Off unless the app creates a
 * [RemoteLogSink]; meant for test builds and testing rounds.
 */
public data class RemoteLogConfig(
    /** Base URL of the portal, for example `https://logs.example.com`. */
    val endpoint: String,
    /**
     * Key the portal accepts uploads with. It ships inside the app, so treat it
     * as a gate against strangers, not as a secret; rotate it per testing round.
     */
    val ingestKey: String,
    val device: DeviceInfo,
    /** Free-form label shown in the portal, such as the testing round ("beta-3"). */
    val label: String? = null,
    /** Entries below this level are not uploaded. Stanzas are DEBUG. */
    val minLevel: LogLevel = LogLevel.DEBUG,
    /** How long entries gather before an upload. Lower is closer to real time but costs radio time. */
    val flushIntervalMillis: Long = 2_000,
    val maxBatchEntries: Int = 200,
    /** Upload body limit before compression. A single larger entry is still sent alone. */
    val maxBatchBytes: Int = 512 * 1024,
    /** Entries held while the portal is unreachable. Beyond this the oldest are dropped and counted. */
    val maxQueuedEntries: Int = 5_000,
    val maxRetryDelayMillis: Long = 60_000,
) {
    init {
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) { "endpoint must be an http(s) URL" }
        require(ingestKey.isNotBlank())
        require(flushIntervalMillis >= 0 && maxBatchEntries >= 1 && maxBatchBytes >= 1024 && maxQueuedEntries >= 1)
        require(maxRetryDelayMillis >= 1_000)
    }

    val ingestUrl: String get() = endpoint.trimEnd('/') + "/api/v1/ingest"

    override fun toString(): String = "RemoteLogConfig(endpoint=$endpoint, ingestKey=***, device=$device, label=$label)"
}

/** Sends one upload. Platforms provide one (`HttpLogUploader` on JVM and Android); apps can supply their own. */
public fun interface LogUploader {
    /** POSTs [body] (UTF-8 JSON) and returns the HTTP status code. Throws on network failure. */
    public suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): Int
}

/** Counters for a debug screen. */
public data class RemoteLogStats(
    val queued: Int = 0,
    val uploaded: Long = 0,
    /** Dropped because the queue was full, or because the portal refused them as invalid. */
    val dropped: Long = 0,
    val lastUploadMillis: Long? = null,
    val lastError: String? = null,
)

/**
 * Uploads entries to a log portal in batches, close to real time.
 *
 * - [write] never blocks: entries go into a bounded queue; when it is full the
 *   oldest are dropped and counted (sent to the portal as `dropped`).
 * - One upload at a time, in order. A batch leaves the queue only after the
 *   portal accepted it; failures back off exponentially up to
 *   [RemoteLogConfig.maxRetryDelayMillis] and retry the same batch.
 * - Every entry carries this run's session id and its sequence number, so
 *   the portal stores a retried batch only once.
 * - Entries live in memory only: what is queued when the process dies is
 *   lost. Call [flush] before the app goes to the background.
 *
 * Status handling: 2xx accepted; 401/403 (wrong key) and 408/429/5xx retried
 * with backoff; 413 splits the batch; any other 4xx drops the batch.
 */
public class RemoteLogSink(
    public val config: RemoteLogConfig,
    private val uploader: LogUploader,
    scope: CoroutineScope,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val random: Random = Random.Default,
) : LogSink {
    /** Identifies this run of the app in the portal. */
    public val sessionId: String = randomHex(16)
    private val startedAt = clock()

    private val _stats = MutableStateFlow(RemoteLogStats())
    public val stats: StateFlow<RemoteLogStats> = _stats.asStateFlow()

    private val queue = Channel<LogEntry>(
        capacity = config.maxQueuedEntries,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { _stats.update { it.copy(dropped = it.dropped + 1, queued = (it.queued - 1).coerceAtLeast(0)) } },
    )
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** True while a batch has been taken from the queue but not yet accepted or dropped. */
    private val inFlight = MutableStateFlow(false)
    private var droppedReported = 0L
    private val job: Job = scope.launch { run() }

    override fun write(entry: LogEntry) {
        if (entry.level < config.minLevel) return
        // Count before sending, so a drop triggered by this send never makes the count negative.
        _stats.update { it.copy(queued = it.queued + 1) }
        if (queue.trySend(entry).isFailure) _stats.update { it.copy(queued = it.queued - 1) } // closed
    }

    /** Uploads what is queued now instead of waiting; returns true if everything was sent within [timeoutMillis]. */
    public suspend fun flush(timeoutMillis: Long = 5_000): Boolean {
        wake.trySend(Unit)
        return withTimeoutOrNull(timeoutMillis) {
            combine(_stats, inFlight) { stats, busy -> stats.queued == 0 && !busy }.first { it }
        } != null
    }

    /** Stops uploading. Entries still queued are discarded. */
    public fun close() {
        queue.close()
        job.cancel()
    }

    private suspend fun run() {
        // Batches in send order; more than one only after a 413 split one.
        val batches = ArrayDeque<List<String>>()
        var failures = 0
        while (true) {
            if (batches.isEmpty()) {
                inFlight.value = false
                val first = queue.receiveCatching().getOrNull() ?: return
                inFlight.value = true
                // Linger so a burst of stanzas goes up as one request; flush() cuts it short.
                if (config.flushIntervalMillis > 0) withTimeoutOrNull(config.flushIntervalMillis) { wake.receive() }
                batches.addLast(collectBatch(first))
            }
            val batch = batches.first()
            val outcome = try {
                upload(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Outcome.Retry(e.message ?: e::class.simpleName ?: "network error")
            }
            when (outcome) {
                Outcome.Accepted -> {
                    failures = 0
                    batches.removeFirst()
                    _stats.update { it.copy(uploaded = it.uploaded + batch.size, lastUploadMillis = clock(), lastError = null) }
                }
                is Outcome.Retry -> {
                    failures++
                    _stats.update { it.copy(lastError = outcome.reason) }
                    delay(backoff(failures, outcome.longWait))
                }
                Outcome.TooLarge -> {
                    batches.removeFirst()
                    if (batch.size > 1) {
                        batches.addFirst(batch.drop(batch.size / 2))
                        batches.addFirst(batch.take(batch.size / 2))
                    } else {
                        drop(batch, "portal refused an entry as too large")
                    }
                }
                is Outcome.Rejected -> {
                    batches.removeFirst()
                    drop(batch, outcome.reason)
                }
            }
        }
    }

    private fun collectBatch(first: LogEntry): List<String> {
        val batch = ArrayList<String>()
        var bytes = 0
        var next: LogEntry? = first
        while (next != null) {
            val encoded = LogJson.entry(next)
            batch += encoded
            bytes += encoded.length
            _stats.update { it.copy(queued = (it.queued - 1).coerceAtLeast(0)) }
            if (batch.size >= config.maxBatchEntries || bytes >= config.maxBatchBytes) break
            next = queue.tryReceive().getOrNull()
        }
        return batch
    }

    private suspend fun upload(entries: List<String>): Outcome {
        val dropped = _stats.value.dropped
        val body = LogJson.batch(config, sessionId, startedAt, entries, dropped - droppedReported)
        val status = uploader.post(
            config.ingestUrl,
            mapOf(
                "Authorization" to "Bearer ${config.ingestKey}",
                "Content-Type" to "application/json; charset=utf-8",
            ),
            body.encodeToByteArray(),
        )
        return when {
            status in 200..299 -> Outcome.Accepted.also { droppedReported = dropped }
            status == 401 || status == 403 -> Outcome.Retry("portal refused the ingest key (HTTP $status)", longWait = true)
            status == 413 -> Outcome.TooLarge
            status == 408 || status == 429 || status >= 500 -> Outcome.Retry("portal unavailable (HTTP $status)")
            else -> Outcome.Rejected("portal rejected the batch (HTTP $status)")
        }
    }

    private fun drop(entries: List<String>, reason: String) {
        _stats.update { it.copy(dropped = it.dropped + entries.size, lastError = reason) }
    }

    /** Full jitter: a random wait in [base, min(max, base·2^failures)], so many devices do not retry in step. */
    private fun backoff(failures: Int, longWait: Boolean): Long {
        if (longWait) return config.maxRetryDelayMillis
        val base = 1_000L
        val ceiling = (base shl (failures - 1).coerceAtMost(16)).coerceAtMost(config.maxRetryDelayMillis)
        return base + random.nextLong(ceiling - base + 1)
    }

    private fun randomHex(bytes: Int): String = buildString {
        repeat(bytes) { append(random.nextInt(256).toString(16).padStart(2, '0')) }
    }

    private sealed interface Outcome {
        data object Accepted : Outcome
        data object TooLarge : Outcome
        data class Retry(val reason: String, val longWait: Boolean = false) : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    public companion object
}

internal class PlatformInfo(val platform: String, val osVersion: String?, val model: String?)

internal expect fun currentPlatform(): PlatformInfo
