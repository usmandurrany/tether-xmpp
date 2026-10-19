package io.github.usmandurrany.tether.debug

import io.github.usmandurrany.tether.core.TrafficLogger
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.XmlLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.time.Clock

/** What [TetherDebug] logs and how much it hides. */
public data class DebugConfig(
    /** Log XML traffic. Events logged with [TetherDebug.log] are kept either way. */
    val stanzas: Boolean = true,
    /** Also log stream-level elements: features, TLS, SASL (redacted), Stream Management acks. */
    val nonzas: Boolean = true,
    /** Replace message bodies with their length ([Redaction.messageBodies]). */
    val redactBodies: Boolean = false,
    /** Runs after the built-in redaction, for app-specific secrets. */
    val redactor: StanzaRedactor? = null,
    /** Text nodes longer than this are shortened (base64 avatars, previews). */
    val maxTextChars: Int = 4_096,
    /** Entries below this level are dropped before reaching any sink. */
    val minLevel: LogLevel = LogLevel.VERBOSE,
) {
    init {
        require(maxTextChars >= 16)
    }
}

/**
 * Debug logging for Tether: shows every stanza sent and received, and events
 * the app adds, in whatever [LogSink]s are attached (console, an in-app
 * screen, a remote server).
 *
 * ```
 * val debug = TetherDebug(sinks = listOf(ConsoleSink()))
 * val client = TetherJvm.client(config, scope, logger = debug.trafficLogger())
 * debug.trackConnection(client.state, scope)
 * debug.log(LogLevel.INFO, "app", "Opened chat with bob")
 * ```
 *
 * Credentials never reach a sink: the core already blanks SASL, and
 * [Redaction.credentials] runs on every stanza on top of it. Anything that
 * cannot be parsed for redaction is replaced by a placeholder, never logged raw.
 *
 * Logging costs nothing measurable when [enabled] is false or no sink is attached.
 */
public class TetherDebug(
    public val config: DebugConfig = DebugConfig(),
    sinks: List<LogSink> = emptyList(),
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val sinks = AtomicReference(sinks.toList())
    private val seq = AtomicLong(0)
    private val redactor: StanzaRedactor = StanzaRedactor.chain(
        *listOfNotNull(Redaction.credentials, Redaction.messageBodies.takeIf { config.redactBodies }, config.redactor).toTypedArray(),
    )

    /** Master switch, safe to flip at any time (for example from a hidden settings screen). */
    @Volatile
    public var enabled: Boolean = true

    public fun addSink(sink: LogSink) {
        sinks.update { it + sink }
    }

    public fun removeSink(sink: LogSink) {
        sinks.update { it - sink }
    }

    /**
     * A [TrafficLogger] to pass to `TetherJvm.client`, `TetherAndroid.client` or
     * a `StreamNegotiator`. Use one per account. [account] labels its entries;
     * when null it is learned from the stream: the bare JID from our stream
     * header, then the full JID once the server binds a resource.
     */
    public fun trafficLogger(account: String? = null): TrafficLogger = StanzaLogger(account)

    /** Logs an event from the app or an integration. */
    public fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null, account: String? = null) {
        emit(level, tag, account, message, error = error?.stackTraceToString())
    }

    /**
     * Logs every change of a state flow, such as `XmppClient.state`, at INFO
     * under the tag `connection`. Returns the collecting job.
     */
    public fun trackConnection(state: Flow<Any?>, scope: CoroutineScope, account: String? = null): Job = scope.launch {
        state.distinctUntilChanged().collect { log(LogLevel.INFO, "connection", it.toString(), account = account) }
    }

    private fun active(level: LogLevel): Boolean = enabled && level >= config.minLevel && sinks.load().isNotEmpty()

    private fun emit(
        level: LogLevel,
        tag: String,
        account: String?,
        text: String,
        direction: Direction? = null,
        stanza: StanzaSummary? = null,
        error: String? = null,
    ) {
        if (!active(level)) return
        val entry = LogEntry(seq.incrementAndFetch(), clock(), level, tag, account, text, direction, stanza, error)
        for (sink in sinks.load()) {
            try {
                sink.write(entry)
            } catch (e: Throwable) {
                // A broken sink must never break the connection that is logging.
            }
        }
    }

    private inner class StanzaLogger(fixedAccount: String?) : TrafficLogger {
        @Volatile
        private var account: String? = fixedAccount
        private val learnAccount = fixedAccount == null

        override fun sent(xml: String) = record(Direction.SENT, xml)

        override fun received(xml: String) = record(Direction.RECEIVED, xml)

        private fun record(direction: Direction, xml: String) {
            if (!config.stanzas || !active(LogLevel.DEBUG)) return
            if (xml.startsWith("<?xml") || xml.startsWith("<stream:stream") || xml == "</stream:stream>") {
                // Stream headers carry only addresses and version; they are logged as they are.
                if (learnAccount && direction == Direction.SENT && account == null) account = HEADER_FROM.find(xml)?.groupValues?.get(1)
                if (config.nonzas) emit(LogLevel.DEBUG, LogEntry.STANZA_TAG, account, xml, direction, StanzaSummary("stream"))
                return
            }
            val parsed = XmlSupport.parse(xml, LIMITS)
            if (parsed == null) {
                // Fail closed: what cannot be parsed cannot be redacted.
                emit(LogLevel.DEBUG, LogEntry.STANZA_TAG, account, "[unparsed XML, ${xml.length} chars]", direction)
                return
            }
            if (learnAccount && direction == Direction.RECEIVED) boundJid(parsed)?.let { account = it }
            val isStanza = parsed.namespace == XmlSupport.CLIENT && parsed.name in STANZAS
            if (!isStanza && !config.nonzas) return
            val safe = XmlSupport.truncateText(redact(parsed), config.maxTextChars)
            val text = runCatching { XmlSupport.write(safe) }.getOrElse { "[unwritable XML <${safe.name}/>]" }
            emit(LogLevel.DEBUG, LogEntry.STANZA_TAG, account, text, direction, XmlSupport.summarize(safe))
        }

        private fun redact(element: Element): Element = try {
            redactor.redact(element)
        } catch (e: Throwable) {
            // An app redactor failed; keep only what identifies the element.
            Element(element.name, element.namespace, element.attributes.filter { it.name in setOf("type", "id") }, listOf())
        }
    }

    private companion object {
        val STANZAS = setOf("message", "presence", "iq")
        val LIMITS = XmlLimits(maxStanzaBytes = 16 * 1024 * 1024)
        val HEADER_FROM = Regex("""\sfrom='([^']+)'""")
        const val BIND = "urn:ietf:params:xml:ns:xmpp-bind"

        fun boundJid(element: Element): String? {
            if (element.name != "iq" || element.attribute("type") != "result") return null
            return element.child("bind", BIND)?.child("jid")?.text?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
