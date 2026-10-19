package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.xml.Attribute
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.TimeSource

/** Something that sends stanzas: the [XmppClient], or one session during setup. */
public interface StanzaSender {
    public suspend fun send(stanza: Element)

    /** Sends an IQ get/set and returns the server's result or error answer. */
    public suspend fun request(iq: Element, timeoutMillis: Long = 30_000): Element

    /**
     * Like [request], but returns only after every stanza that arrived before
     * the answer has been through the extensions. For queries whose results
     * come as separate stanzas before the answer, such as archive queries.
     */
    public suspend fun requestInOrder(iq: Element, timeoutMillis: Long = 30_000): Element = request(iq, timeoutMillis)
}

/**
 * What a [ClientExtension] sees of a freshly opened session, before the app
 * sees it online. Requests here go straight to this session.
 */
public interface SessionContext : StanzaSender {
    public val jid: FullJid
    public val capabilities: ServerCapabilities

    /** True when XEP-0198 resumed the previous session: the server kept its state (presence, carbons, push). */
    public val resumed: Boolean
}

/**
 * A feature plugged into [XmppClient] (roster, presence, carbons, push...).
 * Extensions run in the order they were installed.
 */
public interface ClientExtension {
    /**
     * Runs on every new session before it is reported online, in install order.
     * Check [SessionContext.capabilities] before enabling anything. Throwing
     * fails the attempt, and the supervisor retries; handle server refusals here
     * instead of throwing, or the connection will never come up.
     */
    public suspend fun onSessionStarted(session: SessionContext) {}

    /** Sees each incoming stanza in install order. Return true to consume it. */
    public suspend fun onStanza(stanza: Element): Boolean = false

    /** The server confirmed (XEP-0198) these stanzas the client sent. */
    public fun onConfirmed(stanzas: List<Element>) {}
}

/** Tuning for [XmppClient]. */
public data class ClientPolicy(
    val reconnect: ReconnectPolicy = ReconnectPolicy(),
    val session: SessionPolicy = SessionPolicy(),
    /** Unsent stanzas kept while offline. Beyond this, [XmppClient.send] fails. */
    val maxOutbox: Int = 1_000,
    /** Incoming stanzas no extension consumed, buffered for slow collectors of [XmppClient.stanzas]. */
    val stanzaBuffer: Int = 256,
) {
    init {
        require(maxOutbox >= 0 && stanzaBuffer >= 1)
    }
}

/** [XmppClient.send] could not keep a stanza: the outbox is full. */
public class OutboxFullException(public val stanza: Element) : Exception("The outbox is full")

/**
 * The entry point for an app: one account, kept connected by a
 * [ConnectionSupervisor], with features added as [ClientExtension]s.
 *
 * - [send] never loses a stanza to a dropped connection. With Stream
 *   Management it is queued until the server confirms it; while offline it is
 *   kept in an outbox and sent on the next session. Every stanza gets an id,
 *   so a receiver can recognise a re-send.
 * - Extensions set the session up (roster, presence, carbons...) before it is
 *   reported online, so `Online` means ready.
 * - [stanzas] carries what no extension consumed. Collect it before calling
 *   [connect]: stanzas with no collector are dropped.
 * - When the server offers CSI (XEP-0352), the client reports active and
 *   inactive with the app's foreground state.
 *
 * Use one client per account per process.
 */
public class XmppClient(
    negotiator: StreamNegotiator,
    parentScope: CoroutineScope,
    private val policy: ClientPolicy = ClientPolicy(),
    keepalive: AdaptiveKeepalive? = AdaptiveKeepalive(),
) : ConnectionControls, StanzaSender {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val extensions = ArrayList<ClientExtension>()
    private val sending = Mutex()
    private val outbox = ArrayDeque<Element>()
    private val foreground = MutableStateFlow(true)

    private val _session = MutableStateFlow<XmppSession?>(null)
    private val _stanzas = MutableSharedFlow<Element>(extraBufferCapacity = policy.stanzaBuffer)
    private val _confirmed = MutableSharedFlow<Element>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val supervisor = ConnectionSupervisor(
        scope,
        ClientOpener(XmppSessionOpener(negotiator, policy.session) { confirmed -> onConfirmed(confirmed) }),
        policy.reconnect,
        keepalive = keepalive,
    )

    public val state: StateFlow<ConnectionState> get() = supervisor.state

    /** The live session, or null between sessions. */
    public val session: StateFlow<XmppSession?> = _session.asStateFlow()

    /** Incoming stanzas no extension consumed. */
    public val stanzas: SharedFlow<Element> = _stanzas.asSharedFlow()

    /** Stanzas the server confirmed it received (XEP-0198). Only with Stream Management. */
    public val confirmed: SharedFlow<Element> = _confirmed.asSharedFlow()

    init {
        // One reporter, so active and inactive reach the server in the order they happened.
        scope.launch {
            foreground.collect { fg -> _session.value?.let { reportActivity(it, fg) } }
        }
    }

    /** Adds a feature. Install everything before [connect]. */
    public fun install(extension: ClientExtension) {
        check(state.value == ConnectionState.Idle) { "Install extensions before connecting" }
        extensions += extension
    }

    public suspend fun connect(): ConnectResult = supervisor.connect()

    public suspend fun disconnect() {
        supervisor.disconnect()
    }

    /** Stops for good and closes the connection. */
    public fun close() {
        supervisor.close()
        scope.cancel()
    }

    override fun wake(): Unit = supervisor.wake()

    override fun networkChanged(available: Boolean, id: String?, kind: String?): Unit =
        supervisor.networkChanged(available, id, kind)

    override fun setForeground(foreground: Boolean) {
        this.foreground.value = foreground
        supervisor.setForeground(foreground)
    }

    /**
     * Sends a message, presence or IQ. Returns once it is on its way: written to
     * the session, or kept in the outbox until the next one. Throws
     * [OutboxFullException] when offline and the outbox is full.
     */
    override suspend fun send(stanza: Element) {
        require(StreamManagement.isStanza(stanza)) { "Only message, presence and iq stanzas can be sent" }
        val stamped = withId(stanza)
        sending.withLock {
            val live = _session.value
            if (live != null && live.offer(stamped)) return
            if (outbox.size >= policy.maxOutbox) throw OutboxFullException(stamped)
            outbox.addLast(stamped)
        }
    }

    /**
     * Sends an IQ get/set and waits for the answer, waiting up to [timeoutMillis]
     * in total for a session first. A session that ends before the answer
     * arrives fails the request; the caller decides whether to retry.
     */
    override suspend fun request(iq: Element, timeoutMillis: Long): Element = request(iq, timeoutMillis, ordered = false)

    override suspend fun requestInOrder(iq: Element, timeoutMillis: Long): Element = request(iq, timeoutMillis, ordered = true)

    private suspend fun request(iq: Element, timeoutMillis: Long, ordered: Boolean): Element {
        val start = TimeSource.Monotonic.markNow()
        val live = withTimeoutOrNull(timeoutMillis) { _session.filterNotNull().first() }
            ?: throw XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, "Not connected")
        val left = (timeoutMillis - start.elapsedNow().inWholeMilliseconds).coerceAtLeast(1)
        return live.request(iq, left, ordered)
    }

    /** Stanzas waiting for a session, oldest first. */
    public suspend fun pendingOutbox(): List<Element> = sending.withLock { outbox.toList() }

    private fun withId(stanza: Element): Element =
        if (stanza.attribute("id") != null) stanza else stanza.copy(attributes = stanza.attributes + Attribute("id", newStanzaId()))

    private fun onConfirmed(stanzas: List<Element>) {
        stanzas.forEach { _confirmed.tryEmit(it) }
        extensions.forEach { runCatching { it.onConfirmed(stanzas) } }
    }

    private suspend fun dispatch(stanza: Element) {
        for (extension in extensions) {
            val consumed = try {
                extension.onStanza(stanza)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false // A failing extension must not stop the stream.
            }
            if (consumed) return
        }
        _stanzas.emit(stanza)
    }

    private suspend fun reportActivity(session: XmppSession, foreground: Boolean) {
        if (!session.capabilities.hasStreamFeature("csi", Ns.CSI)) return
        runCatching { session.sendNonza(element(if (foreground) "active" else "inactive", Ns.CSI)) }
    }

    /**
     * Opens sessions for the supervisor and readies each one: starts reading,
     * runs the extensions, sends the outbox, and only then hands it over.
     */
    private inner class ClientOpener(private val inner: SessionOpener) : SessionOpener {
        override suspend fun open(): LiveSession {
            val session = inner.open() as XmppSession
            val reader = scope.launch {
                launch { session.incoming.collect { dispatch(it) } }
                session.awaitClosed()
                _session.compareAndSet(session, null)
            }
            try {
                val context = object : SessionContext {
                    override val jid: FullJid get() = session.jid
                    override val capabilities: ServerCapabilities get() = session.capabilities
                    override val resumed: Boolean get() = session.resumed
                    override suspend fun send(stanza: Element): Unit = session.send(stanza)
                    override suspend fun request(iq: Element, timeoutMillis: Long): Element = session.request(iq, timeoutMillis)
                    override suspend fun requestInOrder(iq: Element, timeoutMillis: Long): Element =
                        session.request(iq, timeoutMillis, ordered = true)
                }
                for (extension in extensions) extension.onSessionStarted(context)
                if (!foreground.value) reportActivity(session, false)
                sending.withLock {
                    while (outbox.isNotEmpty()) {
                        if (!session.offer(outbox.first())) {
                            throw XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, "Session ended while sending the outbox")
                        }
                        outbox.removeFirst()
                    }
                    _session.value = session
                }
                // It may have ended meanwhile; the reader has then already run.
                if (!session.isOpen) _session.compareAndSet(session, null)
                return session
            } catch (t: Throwable) {
                session.close()
                reader.cancel()
                throw t
            }
        }
    }

    internal companion object {
        /** 128 random bits: unique without coordination, also across restarts. */
        fun newStanzaId(): String =
            Random.nextLong().toULong().toString(36) + Random.nextLong().toULong().toString(36)
    }
}
