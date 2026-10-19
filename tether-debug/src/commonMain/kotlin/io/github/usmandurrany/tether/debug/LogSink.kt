package io.github.usmandurrany.tether.debug

import io.github.usmandurrany.tether.xml.XmlLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/**
 * Where log entries go. [write] is called on the thread that sent or read the
 * stanza, so it must return quickly and never block on I/O: hand the entry to
 * a queue if the work is slow. Exceptions are caught and ignored.
 *
 * On Android, logcat is one line:
 * `LogSink { Log.d("Tether", LogFormatter().format(it)) }`.
 */
public fun interface LogSink {
    public fun write(entry: LogEntry)
}

/** Turns entries into readable lines. */
public class LogFormatter(
    /** Indent stanzas over several lines instead of one line each. */
    private val pretty: Boolean = false,
) {
    public fun format(entry: LogEntry): String = buildString {
        append(Instant.fromEpochMilliseconds(entry.timestampMillis).toString())
        append(' ').append(entry.level.name.first())
        append(' ').append(entry.tag)
        entry.account?.let { append(' ').append(it) }
        when (entry.direction) {
            Direction.SENT -> append(" → ")
            Direction.RECEIVED -> append(" ← ")
            null -> append(": ")
        }
        val text = if (pretty && entry.isStanza) {
            XmlSupport.parse(entry.text, LIMITS)?.let { "\n" + XmlSupport.pretty(it) } ?: entry.text
        } else {
            entry.text
        }
        append(text)
        entry.error?.let { append('\n').append(it) }
    }

    private companion object {
        val LIMITS = XmlLimits(maxStanzaBytes = 16 * 1024 * 1024)
    }
}

/** Prints each entry, by default to standard output. */
public class ConsoleSink(
    private val formatter: LogFormatter = LogFormatter(),
    private val output: (String) -> Unit = ::println,
) : LogSink {
    override fun write(entry: LogEntry) {
        output(formatter.format(entry))
    }
}

/**
 * Keeps the last [capacity] entries in memory for an in-app debug screen.
 * Collect [entries] from the UI; it updates on every new entry.
 */
public class MemorySink(public val capacity: Int = 1_000) : LogSink {
    init {
        require(capacity >= 1)
    }

    private val state = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Oldest first. */
    public val entries: StateFlow<List<LogEntry>> = state.asStateFlow()

    override fun write(entry: LogEntry) {
        // update {} retries on contention, so concurrent writers never lose an entry.
        state.update { if (it.size < capacity) it + entry else it.drop(it.size - capacity + 1) + entry }
    }

    public fun clear() {
        state.value = emptyList()
    }
}
