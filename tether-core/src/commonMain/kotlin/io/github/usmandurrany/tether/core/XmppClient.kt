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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    /**
     * After each chat message (a message with a body), ask the server to
     * confirm at once and check that the connection answers. A connection
     * that has silently died (a "zombie" after a network change) is then
     * found within the probe timeout, and the message goes out again on a new
     * one, instead of waiting for the next idle check.
     */
    val confirmEachMessage: Boolean = true,
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
 * - With a [store], unconfirmed stanzas, the outbox, the session to resume
 *   and learned keepalive timing survive a restart of the app. It is saved
 *   shortly after every change and at once when the app goes to the background.
 * - With [credentials], the SCRAM keys of each login are saved there, so the
 *   next run can log in without the password.
 *
 * Use one client per account per process.
 */
public class XmppClient(
    negotiator: StreamNegotiator,
    parentScope: CoroutineScope,
    private val policy: ClientPolicy = ClientPolicy(),
    private val keepalive: AdaptiveKeepalive? = AdaptiveKeepalive(),
    private val store: KeyValueStore? = null,
    private val credentials: CredentialVault? = null,
) : ConnectionControls, StanzaSender {
    /** The account this client logs in to. */
    public val account: BareJid = negotiator.account

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val extensions = ArrayList<ClientExtension>()
    private val sending = Mutex()
    private val outbox = ArrayDeque<Element>()
    private val _foreground = MutableStateFlow(true)

    /**
     * Lives as long as the client: cancelled by [close]. Features built on the
     * client run their background work here, so closing the client stops them too.
     */
    public val coroutineScope: CoroutineScope get() = scope

    /** Whether the app is in the foreground, as last reported through [setForeground]. */
    public val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _session = MutableStateFlow<XmppSession?>(null)
    private val _stanzas = MutableSharedFlow<Element>(extraBufferCapacity = policy.stanzaBuffer)
    private val _confirmed = MutableSharedFlow<Element>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val opener = XmppSessionOpener(negotiator, policy.session) { confirmed -> onConfirmed(confirmed) }
    private val supervisor = ConnectionSupervisor(scope, ClientOpener(opener), policy.reconnect, keepalive = keepalive)

    @kotlin.concurrent.Volatile
    private var lastSession: XmppSession? = null
    private val persistence = Mutex()
    private var restored = false
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private val stateKey = "tether.state.$account"

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
            _foreground.collect { fg -> _session.value?.let { reportActivity(it, fg) } }
        }
        if (store != null) {
            scope.launch {
                for (request in saveRequests) {
                    delay(SAVE_DEBOUNCE_MILLIS) // one write for a burst of changes
                    runCatching { save() }
                }
            }
        }
    }

    /** Adds a feature. Install everything before [connect]. */
    public fun install(extension: ClientExtension) {
        check(state.value == ConnectionState.Idle) { "Install extensions before connecting" }
        extensions += extension
    }

    public suspend fun connect(): ConnectResult {
        restore()
        return supervisor.connect()
    }

    /** Call when a push notification arrives; see [ConnectionSupervisor.wakeForPush]. */
    public suspend fun wakeForPush(elapsedSincePushMillis: Long = 0): ConnectResult {
        restore()
        return supervisor.wakeForPush(elapsedSincePushMillis)
    }

    public suspend fun disconnect() {
        supervisor.disconnect()
        runCatching { save() }
    }

    /**
     * Writes the current state to the [store] now. Called automatically; apps
     * may also call it when the OS warns that the process may be killed.
     */
    public suspend fun save() {
        val store = store ?: return
        persistence.withLock {
            if (!restored) return // never overwrite saved state that has not been read yet
            val last = lastSession
            val snapshot = last?.smSnapshot()
            val resume = if (last != null && snapshot?.resumptionId != null) {
                ClientState.SavedResume(
                    last.jid,
                    snapshot,
                    last.capabilities.streamFeatures,
                    last.capabilities.server.features,
                    last.capabilities.account.features,
                )
            } else {
                null
            }
            val unsent = (if (resume == null) snapshot?.pending.orEmpty() else emptyList()) + sending.withLock { outbox.toList() }
            val learned = runCatching { keepalive?.snapshot() }.getOrNull().orEmpty()
            val state = ClientState(resume, unsent, learned)
            store.put(stateKey, if (resume == null && unsent.isEmpty() && learned.isEmpty()) null else state.encode())
        }
    }

    /** Loads what an earlier run saved, once, before the first connection. */
    private suspend fun restore() {
        val store = store
        persistence.withLock {
            if (restored) return
            restored = true
            val saved = store?.let { runCatching { it.get(stateKey) }.getOrNull() }?.let(ClientState::decode) ?: return
            sending.withLock {
                // Older than anything sent since start-up, so they go first.
                saved.unsent.asReversed().forEach { outbox.addFirst(it) }
            }
            saved.resume?.let { opener.restore(it.toRequest()) }
            keepalive?.restore(saved.keepalive)
        }
    }

    private fun saveSoon() {
        if (store != null) saveRequests.trySend(Unit)
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
        _foreground.value = foreground
        supervisor.setForeground(foreground)
        // The OS may kill a background app at any moment: save now, not later.
        if (!foreground && store != null) scope.launch { runCatching { save() } }
    }

    /**
     * Sends a message, presence or IQ. Returns once it is on its way: written to
     * the session, or kept in the outbox until the next one. Throws
     * [OutboxFullException] when offline and the outbox is full.
     */
    override suspend fun send(stanza: Element) {
        require(StreamManagement.isStanza(stanza)) { "Only message, presence and iq stanzas can be sent" }
        restore() // a send before the first connect must not be saved over what an earlier run left
        val stamped = withId(stanza)
        val written = sending.withLock {
            val live = _session.value
            if (live == null || !live.offer(stamped)) {
                if (outbox.size >= policy.maxOutbox) throw OutboxFullException(stamped)
                outbox.addLast(stamped)
                false
            } else {
                true
            }
        }
        saveSoon()
        // Not proof of delivery until the server confirms: check now rather than at the next idle probe.
        if (written && policy.confirmEachMessage && stamped.name == "message" && stamped.child("body", Ns.CLIENT) != null) {
            supervisor.checkConnection()
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
        saveSoon()
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
            lastSession = session
            val reader = scope.launch {
                launch { session.incoming.collect { dispatch(it) } }
                session.awaitClosed()
                _session.compareAndSet(session, null)
                saveSoon()
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
                // A new session starts active. A resumed one keeps whatever the server last
                // heard, which may be from before the app came back (or from an earlier run),
                // so it is told the current state either way.
                if (session.resumed || !foreground.value) reportActivity(session, foreground.value)
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
                if (!session.resumed) session.scramKeys?.let { keys -> runCatching { credentials?.saveKeys(account, keys) } }
                saveSoon()
                return session
            } catch (t: Throwable) {
                session.close()
                reader.cancel()
                throw t
            }
        }
    }

    internal companion object {
        const val SAVE_DEBOUNCE_MILLIS = 500L

        /** 128 random bits: unique without coordination, also across restarts. */
        fun newStanzaId(): String =
            Random.nextLong().toULong().toString(36) + Random.nextLong().toULong().toString(36)
    }
}
