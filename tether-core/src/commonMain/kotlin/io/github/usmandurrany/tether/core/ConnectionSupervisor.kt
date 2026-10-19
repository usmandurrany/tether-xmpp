package io.github.usmandurrany.tether.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * An authenticated stream as the supervisor sees it. The stream layer (plain
 * session today, Stream Management next) implements this.
 */
public interface LiveSession {
    public val jid: FullJid

    /**
     * Suspends until the stream ends. Returns null when the server closed it
     * cleanly, or the error that ended it. A stream error is reported as an
     * [XmppNegotiationException] with [XmppNegotiationException.streamError] set.
     */
    public suspend fun awaitClosed(): Throwable?

    /** Checks the server is still answering (SM `<r/>` or XEP-0199 ping). The supervisor applies the timeout. */
    public suspend fun probe(): Boolean

    /** Closes the stream. Must be idempotent and must not suspend. */
    public fun close()
}

/** Opens one session. Must clean up after itself when cancelled or when it fails. */
public fun interface SessionOpener {
    public suspend fun open(): LiveSession
}

/** What the connection is doing. Observe [ConnectionSupervisor.state]. */
public sealed interface ConnectionState {
    /** Not wanted: [ConnectionSupervisor.connect] has not been called, or [ConnectionSupervisor.disconnect] was. */
    public data object Idle : ConnectionState

    /** Wanted, but the OS reports no network. Nothing is attempted until one appears. */
    public data object WaitingForNetwork : ConnectionState

    /** An attempt is running. [attempt] counts from 1 since the last success. */
    public data class Connecting(val attempt: Int) : ConnectionState

    public data class Online(val jid: FullJid) : ConnectionState

    /** Waiting before the next attempt. */
    public data class Backoff(val attempt: Int, val delayMillis: Long, val lastError: Throwable?) : ConnectionState

    /** Stopped on an error retrying cannot fix (bad credentials, certificate, conflict). Only [ConnectionSupervisor.connect] restarts. */
    public data class Terminal(val error: Throwable) : ConnectionState
}

/** The outcome for one caller of [ConnectionSupervisor.connect]. */
public sealed interface ConnectResult {
    public data class Connected(val jid: FullJid) : ConnectResult
    public data class Failed(val error: Throwable) : ConnectResult
    /** [ConnectionSupervisor.disconnect] was called before the connection came up. */
    public data object Cancelled : ConnectResult
}

/** Timing for reconnects and liveness checks (design §4.3, §4.4). */
public data class ReconnectPolicy(
    /** Backoff base: the n-th retry waits a random time in [0, min(max, base·2^(n-2))]. The first retry is immediate. */
    val baseDelayMillis: Long = 1_000,
    val maxDelayMillis: Long = 120_000,
    /** A probe that gets no answer in this time counts as failed. */
    val probeTimeoutMillis: Long = 10_000,
    /**
     * A session that drops sooner than this after coming up counts as a failed
     * attempt (with backoff), so a server that accepts and immediately kicks us
     * cannot cause a tight reconnect loop. Later drops reconnect immediately.
     */
    val stableSessionMillis: Long = 30_000,
) {
    init {
        require(baseDelayMillis > 0 && maxDelayMillis >= baseDelayMillis) { "Invalid backoff bounds" }
        require(probeTimeoutMillis > 0 && stableSessionMillis >= 0) { "Invalid timeouts" }
    }
}

/**
 * Keeps one account connected (design §4.1, §4.1a).
 *
 * Every trigger (API calls, network changes, push wake-ups, timers, socket and
 * probe results) is an intent sent to a single actor coroutine, which alone
 * owns the state. That gives these guarantees:
 *
 * - At most one attempt runs and at most one session is live, so overlapping
 *   triggers can never open a second connection.
 * - Each attempt has an epoch. Results from an older epoch are dropped, and a
 *   session they carry is closed, so a late event can never tear down the
 *   current connection.
 * - An online session is closed only when a probe fails, the server ends it,
 *   or [disconnect] is called. Wake-ups and network changes only trigger a probe.
 * - Terminal errors stop retrying, which also stops two clients with the same
 *   resource from kicking each other in a loop.
 *
 * Use one supervisor per account per process; [ConnectionRegistry] enforces that.
 *
 * @param scope where the actor runs. Cancelling it stops the supervisor and closes the session.
 * @param clock monotonic milliseconds, injectable for tests.
 */
public class ConnectionSupervisor(
    private val scope: CoroutineScope,
    private val opener: SessionOpener,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val random: Random = Random.Default,
    private val clock: () -> Long = monotonicMillis(),
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    public val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val intents = Channel<Intent>(Channel.UNLIMITED)

    private sealed interface Intent {
        class Connect(val result: CompletableDeferred<ConnectResult>) : Intent
        class Disconnect(val done: CompletableDeferred<Unit>) : Intent
        data object Wake : Intent
        class Network(val available: Boolean, val id: String?) : Intent
        class AttemptDone(val epoch: Long, val session: LiveSession?, val error: Throwable?) : Intent
        class SessionEnded(val epoch: Long, val cause: Throwable?) : Intent
        class ProbeDone(val epoch: Long, val ok: Boolean) : Intent
        class TimerFired(val token: Long) : Intent
    }

    // Everything below is touched only by the actor coroutine.
    private var wanted = false
    private var epoch = 0L
    private var failures = 0
    private var networkAvailable = true
    private var networkId: String? = null
    private var attemptNetworkId: String? = null
    private var attemptJob: Job? = null
    /** Cancelled attempts that have not finished yet (a blocking connect may ignore cancellation). */
    private val draining = ArrayList<Job>()
    private var session: LiveSession? = null
    private var onlineSince = 0L
    private var watchJob: Job? = null
    private var probeJob: Job? = null
    private var timerJob: Job? = null
    private var timerToken = 0L
    private val waiters = ArrayList<CompletableDeferred<ConnectResult>>()

    private val actor = scope.launch {
        try {
            for (intent in intents) handle(intent)
        } finally {
            shutdown()
        }
    }

    /**
     * Asks to be connected and waits for the outcome. Concurrent callers share
     * the same attempt. Returns immediately when already online (and checks the
     * connection in the background). Keeps waiting through retries and while
     * there is no network; wrap in a timeout if needed.
     */
    public suspend fun connect(): ConnectResult {
        val result = CompletableDeferred<ConnectResult>()
        if (intents.trySend(Intent.Connect(result)).isFailure) return ConnectResult.Cancelled
        return result.await()
    }

    /** Stops retrying and closes the session. Pending [connect] callers get [ConnectResult.Cancelled]. */
    public suspend fun disconnect() {
        val done = CompletableDeferred<Unit>()
        if (intents.trySend(Intent.Disconnect(done)).isFailure) return
        done.await()
    }

    /**
     * A hint that now is a good time to be connected: app came to the
     * foreground, or a push arrived. Probes a live session, or skips the
     * backoff wait. Ignored unless [connect] was called.
     */
    public fun wake() {
        intents.trySend(Intent.Wake)
    }

    /**
     * Reports the validated default network. [id] identifies the network (for
     * example the Android `Network` handle); a change of id means a network switch.
     * Callers should debounce bursts of OS callbacks before calling this.
     */
    public fun networkChanged(available: Boolean, id: String? = null) {
        intents.trySend(Intent.Network(available, id))
    }

    /** Stops the actor and closes the session. The supervisor cannot be reused. */
    public fun close() {
        intents.close()
        actor.cancel()
    }

    private fun handle(intent: Intent) {
        when (intent) {
            is Intent.Connect -> onConnect(intent.result)
            is Intent.Disconnect -> {
                wanted = false
                stopEverything()
                set(ConnectionState.Idle)
                completeWaiters(ConnectResult.Cancelled)
                intent.done.complete(Unit)
            }
            Intent.Wake -> if (wanted) onWake()
            is Intent.Network -> onNetwork(intent.available, intent.id)
            is Intent.AttemptDone -> onAttemptDone(intent)
            is Intent.SessionEnded -> if (intent.epoch == epoch && session != null) onSessionEnded(intent.cause)
            is Intent.ProbeDone -> if (intent.epoch == epoch && session != null) onProbeDone(intent.ok)
            is Intent.TimerFired -> if (intent.token == timerToken && _state.value is ConnectionState.Backoff) {
                timerJob = null
                startAttempt()
            }
        }
    }

    private fun onConnect(result: CompletableDeferred<ConnectResult>) {
        wanted = true
        when (val s = _state.value) {
            is ConnectionState.Online -> {
                result.complete(ConnectResult.Connected(s.jid))
                probe()
            }
            is ConnectionState.Connecting, ConnectionState.WaitingForNetwork -> waiters += result
            is ConnectionState.Backoff, ConnectionState.Idle, is ConnectionState.Terminal -> {
                waiters += result
                failures = 0
                cancelTimer()
                startAttempt()
            }
        }
    }

    private fun onWake() {
        when (_state.value) {
            is ConnectionState.Online -> probe()
            is ConnectionState.Backoff -> {
                failures = 0
                cancelTimer()
                startAttempt()
            }
            // Joining an attempt, waiting for network, or stopped: a wake changes nothing.
            else -> Unit
        }
    }

    private fun onNetwork(available: Boolean, id: String?) {
        val wasAvailable = networkAvailable
        val previousId = networkId
        networkAvailable = available
        networkId = if (available) id else null
        if (!wanted) return
        when (_state.value) {
            // Never close a live session on suspicion; check it. A failed probe
            // then waits for the network or reconnects.
            is ConnectionState.Online -> if (!available || !wasAvailable || id != previousId) probe()
            is ConnectionState.Connecting -> when {
                !available -> {
                    cancelAttempt()
                    set(ConnectionState.WaitingForNetwork)
                }
                // The attempt was started on a network that is gone; start over on the new one.
                id != attemptNetworkId -> {
                    cancelAttempt()
                    startAttempt()
                }
            }
            is ConnectionState.Backoff -> if (!available) {
                cancelTimer()
                set(ConnectionState.WaitingForNetwork)
            } else if (!wasAvailable || id != previousId) {
                failures = 0
                cancelTimer()
                startAttempt()
            }
            ConnectionState.WaitingForNetwork -> if (available) {
                failures = 0
                startAttempt()
            }
            ConnectionState.Idle, is ConnectionState.Terminal -> Unit
        }
    }

    private fun startAttempt() {
        check(attemptJob == null && session == null) { "An attempt or session is already active" }
        if (!networkAvailable) {
            set(ConnectionState.WaitingForNetwork)
            return
        }
        val myEpoch = ++epoch
        attemptNetworkId = networkId
        set(ConnectionState.Connecting(failures + 1))
        draining.removeAll { it.isCompleted }
        val previous = draining.toList()
        attemptJob = scope.launch {
            // Never overlap with a cancelled attempt that is still running.
            previous.joinAll()
            val outcome = try {
                Intent.AttemptDone(myEpoch, opener.open(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Intent.AttemptDone(myEpoch, null, e)
            }
            // No suspension point since open() returned, so this runs even if the attempt
            // was cancelled meanwhile; the actor then closes the session as stale.
            deliver(outcome)
        }
    }

    private fun onAttemptDone(done: Intent.AttemptDone) {
        if (done.epoch != epoch || _state.value !is ConnectionState.Connecting) {
            done.session?.close() // Stale: a newer attempt or a disconnect superseded it.
            return
        }
        attemptJob = null
        val opened = done.session
        if (opened != null) {
            session = opened
            onlineSince = clock()
            set(ConnectionState.Online(opened.jid))
            completeWaiters(ConnectResult.Connected(opened.jid))
            val myEpoch = epoch
            watchJob = scope.launch {
                val cause = try {
                    opened.awaitClosed()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    e
                }
                deliver(Intent.SessionEnded(myEpoch, cause))
            }
            return
        }
        val error = done.error ?: IllegalStateException("Attempt returned neither a session nor an error")
        if (isTerminal(error)) {
            stopTerminal(error)
        } else {
            failures++
            scheduleRetry(error)
        }
    }

    private fun onSessionEnded(cause: Throwable?) {
        val livedMillis = clock() - onlineSince
        dropSession()
        when {
            cause != null && isTerminal(cause) -> stopTerminal(cause)
            livedMillis < policy.stableSessionMillis -> {
                failures++
                scheduleRetry(cause)
            }
            else -> {
                failures = 0
                startAttempt()
            }
        }
    }

    private fun onProbeDone(ok: Boolean) {
        probeJob = null
        if (ok) return
        dropSession()
        failures = 0
        startAttempt() // Waits for the network instead if there is none.
    }

    private fun probe() {
        val live = session ?: return
        if (probeJob != null) return // One probe at a time; its result answers every trigger.
        val myEpoch = epoch
        probeJob = scope.launch {
            val ok = try {
                withTimeoutOrNull(policy.probeTimeoutMillis) { live.probe() } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
            deliver(Intent.ProbeDone(myEpoch, ok))
        }
    }

    /** `failures` is the number of consecutive failed attempts so far (at least 1 here). */
    private fun scheduleRetry(error: Throwable?) {
        val delayMillis = if (failures <= 1) 0L else backoffDelay(failures)
        if (delayMillis == 0L) {
            startAttempt()
            return
        }
        set(ConnectionState.Backoff(failures + 1, delayMillis, error))
        val token = ++timerToken
        timerJob = scope.launch {
            delay(delayMillis)
            deliver(Intent.TimerFired(token))
        }
    }

    /** Full jitter: random in [0, min(cap, base·2^(n-2))]. */
    internal fun backoffDelay(failures: Int): Long {
        val exponent = (failures - 2).coerceIn(0, 30)
        val ceiling = minOf(policy.maxDelayMillis, policy.baseDelayMillis shl exponent).coerceAtLeast(1)
        return random.nextLong(ceiling + 1).coerceAtLeast(1)
    }

    private fun stopTerminal(error: Throwable) {
        stopEverything()
        set(ConnectionState.Terminal(error))
        completeWaiters(ConnectResult.Failed(error))
    }

    private fun dropSession() {
        watchJob?.cancel()
        watchJob = null
        probeJob?.cancel()
        probeJob = null
        session?.close()
        session = null
        epoch++ // Anything still in flight for the old session is now stale.
    }

    private fun cancelAttempt() {
        attemptJob?.let {
            it.cancel() // If it still opens a session, the result arrives and is closed as stale.
            draining += it
        }
        attemptJob = null
        epoch++
    }

    private fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        timerToken++
    }

    private fun stopEverything() {
        cancelAttempt()
        cancelTimer()
        dropSession()
    }

    private fun shutdown() {
        attemptJob?.cancel()
        timerJob?.cancel()
        watchJob?.cancel()
        probeJob?.cancel()
        session?.close()
        session = null
        completeWaiters(ConnectResult.Cancelled)
        // Close sessions from attempts that finish after shutdown.
        intents.close()
        while (true) {
            val leftover = intents.tryReceive().getOrNull() ?: break
            when (leftover) {
                is Intent.AttemptDone -> leftover.session?.close()
                is Intent.Connect -> leftover.result.complete(ConnectResult.Cancelled)
                is Intent.Disconnect -> leftover.done.complete(Unit)
                else -> Unit
            }
        }
    }

    private fun deliver(intent: Intent) {
        if (intents.trySend(intent).isFailure && intent is Intent.AttemptDone) intent.session?.close()
    }

    private fun completeWaiters(result: ConnectResult) {
        waiters.forEach { it.complete(result) }
        waiters.clear()
    }

    private fun set(state: ConnectionState) {
        _state.value = state
    }
}

/**
 * Errors retrying cannot fix. Certificate and login failures need the user;
 * `conflict` means another client took our resource, and retrying would make
 * the two kick each other forever.
 */
internal fun isTerminal(error: Throwable): Boolean {
    if (error !is XmppNegotiationException) return false
    val condition = error.streamError
    if (condition != null) return condition in TERMINAL_STREAM_ERRORS
    return !error.failure.retryable
}

private val TERMINAL_STREAM_ERRORS = setOf(
    "conflict",
    "not-authorized",
    "policy-violation",
    "host-unknown",
    "improper-addressing",
    "unsupported-version",
    "invalid-namespace",
)

private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
