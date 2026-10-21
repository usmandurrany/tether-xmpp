package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.transport.TlsVerificationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
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

/** An authenticated stream as the supervisor sees it. [XmppSession] implements it. */
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

    /**
     * Ends the stream politely (flush, then `</stream:stream>`) so the server
     * drops the session instead of keeping it resumable. Used by
     * [ConnectionSupervisor.disconnect]; the supervisor applies a timeout and
     * calls [close] afterwards either way.
     */
    public suspend fun closeGracefully() {
        close()
    }

    /**
     * Returns once the stream has been quiet for [quietMillis]: nothing came in
     * and no request is waiting for an answer. Used to end a push wake as soon
     * as the waiting messages are in. The default never returns, so the wake
     * then lasts until its deadline.
     */
    public suspend fun awaitQuiet(quietMillis: Long) {
        awaitCancellation()
    }
}

/** Opens one session. Must clean up after itself when cancelled or when it fails. */
public fun interface SessionOpener {
    public suspend fun open(): LiveSession
}

/**
 * The OS signals a connection reacts to. Platform glue (such as the Android
 * module) feeds these from the app lifecycle and the network monitor.
 */
public interface ConnectionControls {
    /** See [ConnectionSupervisor.setForeground]. */
    public fun setForeground(foreground: Boolean)

    /** See [ConnectionSupervisor.networkChanged]. */
    public fun networkChanged(available: Boolean, id: String? = null, kind: String? = id)

    /** See [ConnectionSupervisor.wake]. */
    public fun wake()
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

    /**
     * Wanted, but closed on purpose because the app is in the background
     * ([BackgroundMode.CLOSE] or [BackgroundMode.DETACH]). Reconnects when the
     * app returns to the foreground, or on an explicit [ConnectionSupervisor.connect].
     */
    public data object Paused : ConnectionState

    /** Stopped on an error retrying cannot fix (bad credentials, certificate, conflict). Only [ConnectionSupervisor.connect] restarts. */
    public data class Terminal(val error: Throwable) : ConnectionState
}

/** The outcome for one caller of [ConnectionSupervisor.connect]. */
public sealed interface ConnectResult {
    public data class Connected(val jid: FullJid) : ConnectResult
    public data class Failed(val error: Throwable) : ConnectResult {
        /**
         * The SHA-256 fingerprint of a certificate the server presented and TLS
         * refused (self-signed, say), or null for any other failure. To trust it,
         * show it to the user and connect again with it in
         * `TlsConfig(pinnedCertificateSha256 = setOf(...))`.
         */
        val untrustedCertificate: String?
            get() = generateSequence(error) { it.cause }.filterIsInstance<TlsVerificationException>().firstNotNullOfOrNull { it.peerCertificateSha256 }
    }
    /**
     * [ConnectionSupervisor.disconnect] was called before the connection came
     * up, or the app was in the background and the connection was closed for it
     * first (see [BackgroundMode]).
     */
    public data object Cancelled : ConnectResult
}

/** What happens to the connection while the app is in the background. */
public enum class BackgroundMode {
    /**
     * End the session cleanly (`</stream:stream>`). The server marks the account
     * offline at once, so new messages go to offline storage and push
     * notifications (XEP-0357) on every server. Coming back is a fresh login.
     */
    CLOSE,

    /**
     * Cut the socket but keep the XEP-0198 session resumable. The server sees
     * the drop at once and holds the session for its resume window; coming back
     * resumes without a login and replays what was missed. Whether the server
     * sends push notifications for a held session depends on the server.
     * Some (Tigase, for one) hold messages for it without a push until the
     * window ends, so with push notifications prefer [CLOSE].
     */
    DETACH,

    /** Keep the connection; the app should mark it inactive with CSI (XEP-0352). */
    STAY_CONNECTED,
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
    /** In the background the radio is costlier and the OS stricter: wait longer between retries. */
    val backgroundMaxDelayMillis: Long = 600_000,
    /** Background networks are often slower to wake; give probes longer. */
    val backgroundProbeTimeoutMillis: Long = 20_000,
    /** Upper bound for [ConnectionSupervisor.disconnect]'s polite close before the socket is cut. */
    val gracefulCloseMillis: Long = 5_000,
    val backgroundMode: BackgroundMode = BackgroundMode.CLOSE,
    /**
     * How long the app must stay in the background before the connection is
     * closed, so a quick switch to the camera or share sheet does not cause a
     * disconnect, a push, and a reconnect. 0 closes immediately.
     */
    val backgroundCloseDelayMillis: Long = 5_000,
    /**
     * The longest a [ConnectionSupervisor.wakeForPush] keeps the connection up
     * while the app stays in the background, counted from the push. Later
     * pushes during the same wake do not extend it. The session is then closed
     * cleanly, so the server marks the account offline and pushes again.
     *
     * It counts from the push because the OS gives a background app network
     * access only briefly after one: about 30 s for a high-priority FCM message
     * on Android. A close that comes after that cannot reach the server, which
     * then keeps a resumable session and holds new messages without pushing.
     * Not used with [BackgroundMode.STAY_CONNECTED].
     */
    val pushWakeMillis: Long = 20_000,
    /**
     * A push wake closes early once the stream has been quiet this long (see
     * [LiveSession.awaitQuiet]): the waiting messages are in, so the server can
     * go back to pushing sooner. 0 keeps the connection up for [pushWakeMillis].
     */
    val pushQuietMillis: Long = 3_000,
) {
    init {
        require(baseDelayMillis > 0 && maxDelayMillis >= baseDelayMillis) { "Invalid backoff bounds" }
        require(backgroundMaxDelayMillis >= baseDelayMillis) { "Invalid background backoff" }
        require(probeTimeoutMillis > 0 && backgroundProbeTimeoutMillis > 0 && stableSessionMillis >= 0) { "Invalid timeouts" }
        require(gracefulCloseMillis >= 0 && backgroundCloseDelayMillis >= 0 && pushWakeMillis > 0 && pushQuietMillis >= 0)
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
 * While online, an idle probe runs on the interval [AdaptiveKeepalive] learned
 * for the current network, so a dead link is found even when nothing is sent.
 *
 * @param scope where the actor runs. Cancelling it stops the supervisor and closes the session.
 * @param keepalive learned idle intervals; null turns idle probing off.
 * @param clock monotonic milliseconds, injectable for tests.
 */
public class ConnectionSupervisor(
    private val scope: CoroutineScope,
    private val opener: SessionOpener,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val random: Random = Random.Default,
    private val clock: () -> Long = monotonicMillis(),
    private val keepalive: AdaptiveKeepalive? = AdaptiveKeepalive(),
) : ConnectionControls {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    public val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val intents = Channel<Intent>(Channel.UNLIMITED)

    private sealed interface Intent {
        class Connect(val result: CompletableDeferred<ConnectResult>) : Intent
        class Disconnect(val done: CompletableDeferred<Unit>) : Intent
        data object Wake : Intent
        data object Check : Intent
        class Network(val available: Boolean, val id: String?, val kind: String?) : Intent
        class AttemptDone(val epoch: Long, val session: LiveSession?, val error: Throwable?) : Intent
        class SessionEnded(val epoch: Long, val cause: Throwable?) : Intent
        class ProbeDone(val epoch: Long, val ok: Boolean) : Intent
        class TimerFired(val token: Long) : Intent
        class KeepaliveDue(val token: Long) : Intent
        class Foreground(val foreground: Boolean) : Intent
        class BackgroundDue(val token: Long) : Intent
        class PushWake(val result: CompletableDeferred<ConnectResult>, val elapsedMillis: Long) : Intent
        class PushQuiet(val token: Long, val epoch: Long) : Intent
    }

    // Everything below is touched only by the actor coroutine.
    private var wanted = false
    private var epoch = 0L
    private var failures = 0
    private var networkAvailable = true
    private var networkId: String? = null
    private var networkKind: String? = null
    private var attemptNetworkId: String? = null
    /** The network the live session was opened on. */
    private var sessionNetworkId: String? = null
    private var attemptJob: Job? = null
    /** Cancelled attempts that have not finished yet (a blocking connect may ignore cancellation). */
    private val draining = ArrayList<Job>()
    private var session: LiveSession? = null
    private var onlineSince = 0L
    private var watchJob: Job? = null
    private var probeJob: Job? = null
    private var timerJob: Job? = null
    private var timerToken = 0L
    private var keepaliveJob: Job? = null
    private var keepaliveToken = 0L
    private var probeIsIdle = false
    private var foreground = true
    private var backgroundJob: Job? = null
    private var backgroundToken = 0L
    /** The background timer is a [wakeForPush] deadline, fixed from the first push of the wake. */
    private var pushHold = false
    /** The current push wake has been online, so a lost connection ends it instead of retrying. */
    private var pushOnline = false
    private var quietJob: Job? = null
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

    /**
     * Handles a push notification: connects (also in the background, and also
     * when [connect] was never called, as in a process the push just started),
     * so messages waiting on the server arrive.
     *
     * While the app stays in the background the session is closed cleanly, as
     * [BackgroundMode] asks, once the stream goes quiet
     * ([ReconnectPolicy.pushQuietMillis]) or at the latest
     * [ReconnectPolicy.pushWakeMillis] after the push, so the server goes back
     * to sending pushes. Further pushes during the wake do not extend it, and a
     * connection lost after coming online is not retried until the next push or
     * the foreground: the OS has most likely taken the network away. Coming to
     * the foreground meanwhile keeps the connection open.
     *
     * @param elapsedSincePushMillis how long ago the push arrived, when the app
     *   calls this late (for example after starting the process), so the wake
     *   still ends inside the OS's network window.
     */
    public suspend fun wakeForPush(elapsedSincePushMillis: Long = 0): ConnectResult {
        val result = CompletableDeferred<ConnectResult>()
        if (intents.trySend(Intent.PushWake(result, elapsedSincePushMillis.coerceAtLeast(0))).isFailure) return ConnectResult.Cancelled
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
    override fun wake() {
        intents.trySend(Intent.Wake)
    }

    /**
     * Reports the validated default network. [id] identifies the network (for
     * example the Android `Network` handle); a change of id means a network switch.
     * Callers should debounce bursts of OS callbacks before calling this.
     *
     * [kind] is what keepalive timing is learned for, such as "wifi" or
     * "cell:310260". It should stay the same when the same kind of network comes
     * back, whereas [id] changes on every reconnect. Defaults to [id].
     */
    override fun networkChanged(available: Boolean, id: String?, kind: String?) {
        intents.trySend(Intent.Network(available, id, kind))
    }

    /**
     * Tells the supervisor whether the app is in the foreground. Coming to the
     * foreground acts like [wake]; in the background, retries back off further
     * and probes get more time.
     */
    override fun setForeground(foreground: Boolean) {
        intents.trySend(Intent.Foreground(foreground))
    }

    /**
     * Checks a live session now: the probe gets the usual timeout, and a
     * session that does not answer is dropped and reconnected, so whatever it
     * had not confirmed goes out again on the next one. Does nothing offline.
     */
    public fun checkConnection() {
        intents.trySend(Intent.Check)
    }

    /** Stops the actor and closes the session. The supervisor cannot be reused. */
    public fun close() {
        intents.close()
        actor.cancel()
    }

    private fun handle(intent: Intent) {
        when (intent) {
            is Intent.Connect -> onConnect(intent.result)
            is Intent.Disconnect -> onDisconnect(intent.done)
            Intent.Wake -> if (wanted) onWake()
            Intent.Check -> if (wanted && session != null && _state.value is ConnectionState.Online) probe()
            is Intent.Network -> {
                networkKind = if (intent.available) intent.kind else null
                onNetwork(intent.available, intent.id)
            }
            is Intent.AttemptDone -> onAttemptDone(intent)
            is Intent.SessionEnded -> if (intent.epoch == epoch && session != null) onSessionEnded(intent.cause)
            is Intent.ProbeDone -> if (intent.epoch == epoch && session != null) onProbeDone(intent.ok)
            is Intent.TimerFired -> if (intent.token == timerToken && _state.value is ConnectionState.Backoff) {
                timerJob = null
                startAttempt()
            }
            is Intent.KeepaliveDue -> if (intent.token == keepaliveToken && session != null) {
                keepaliveJob = null
                probe(idle = true)
            }
            is Intent.Foreground -> onForeground(intent.foreground)
            is Intent.PushWake -> {
                onConnect(intent.result)
                holdForPush(intent.elapsedMillis)
            }
            is Intent.BackgroundDue -> if (intent.token == backgroundToken && !foreground && wanted) {
                backgroundJob = null
                endBackgroundWait()
            }
            is Intent.PushQuiet -> if (intent.token == backgroundToken && intent.epoch == epoch && pushHold && !foreground && wanted) {
                quietJob = null
                cancelBackgroundTimer()
                endBackgroundWait()
            }
        }
    }

    private fun onForeground(nowForeground: Boolean) {
        val was = foreground
        foreground = nowForeground
        if (was == nowForeground) return
        cancelBackgroundTimer()
        if (!wanted) return
        if (nowForeground) {
            when (_state.value) {
                ConnectionState.Paused -> {
                    failures = 0
                    startAttempt() // Resumes the held session when the server still has it.
                }
                else -> onWake()
            }
            return
        }
        if (policy.backgroundMode == BackgroundMode.STAY_CONNECTED) return
        if (policy.backgroundCloseDelayMillis == 0L) {
            pause()
            return
        }
        val token = backgroundToken
        backgroundJob = scope.launch {
            delay(policy.backgroundCloseDelayMillis)
            deliver(Intent.BackgroundDue(token))
        }
    }

    private fun cancelBackgroundTimer() {
        backgroundJob?.cancel()
        backgroundJob = null
        backgroundToken++
        pushHold = false
        pushOnline = false
        quietJob?.cancel()
        quietJob = null
    }

    private fun endBackgroundWait() {
        pushHold = false
        pushOnline = false
        if (_state.value != ConnectionState.Paused && _state.value !is ConnectionState.Terminal) pause()
    }

    /**
     * In the background, closes again [ReconnectPolicy.pushWakeMillis] after the
     * push, or sooner once the stream is quiet. A push during a wake that is
     * already running leaves its deadline alone: the OS's network window counts
     * from the first push, not from the latest.
     */
    private fun holdForPush(elapsedMillis: Long) {
        if (foreground || !wanted || policy.backgroundMode == BackgroundMode.STAY_CONNECTED) return
        if (pushHold && backgroundJob != null) return
        cancelBackgroundTimer()
        pushHold = true
        val token = backgroundToken
        val delayMillis = (policy.pushWakeMillis - elapsedMillis).coerceAtLeast(0)
        backgroundJob = scope.launch {
            delay(delayMillis)
            deliver(Intent.BackgroundDue(token))
        }
        if (_state.value is ConnectionState.Online) onPushOnline()
    }

    /** The push wake is online: from now on a loss ends it, and quiet closes it early. */
    private fun onPushOnline() {
        pushOnline = true
        val live = session ?: return
        if (policy.pushQuietMillis == 0L) return
        quietJob?.cancel()
        val token = backgroundToken
        val myEpoch = epoch
        quietJob = scope.launch {
            try {
                live.awaitQuiet(policy.pushQuietMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return@launch // The session ended; the supervisor hears about it on its own.
            }
            deliver(Intent.PushQuiet(token, myEpoch))
        }
    }

    /** Closes the connection on purpose for the background, keeping [wanted]. */
    private fun pause() {
        when (policy.backgroundMode) {
            BackgroundMode.CLOSE -> closeSessionGracefully(null)
            else -> dropSession() // DETACH: an abrupt close leaves the SM session resumable.
        }
        cancelAttempt()
        cancelTimer()
        set(ConnectionState.Paused)
        // Callers waiting on an attempt the background just stopped would otherwise wait for the foreground.
        completeWaiters(ConnectResult.Cancelled)
    }

    /**
     * In the background with a closing mode, a lost connection waits for the
     * foreground (or the next push) instead of retrying. That includes a push
     * wake that was already online: the OS has most likely taken the network
     * away, and retries could not reach the server before the wake ends anyway.
     */
    private val pausedByBackground: Boolean
        get() = !foreground && policy.backgroundMode != BackgroundMode.STAY_CONNECTED && (backgroundJob == null || pushOnline)

    /** Goes to [ConnectionState.Paused] after losing the connection in the background. */
    private fun pauseAfterLoss() {
        cancelBackgroundTimer() // Ends a push wake: nothing is left to close.
        set(ConnectionState.Paused)
    }

    private fun onDisconnect(done: CompletableDeferred<Unit>) {
        wanted = false
        pushHold = false
        pushOnline = false
        closeSessionGracefully(done)
        stopEverything()
        set(ConnectionState.Idle)
        completeWaiters(ConnectResult.Cancelled)
    }

    /**
     * Detaches the live session and ends it politely in the background. A new
     * attempt waits for this, so the old session is gone before a new one opens.
     */
    private fun closeSessionGracefully(done: CompletableDeferred<Unit>?) {
        val closing = session
        val closingWatch = watchJob
        // Detach the session so dropSession does not cut it; it is closed politely below.
        session = null
        watchJob = null
        dropSession()
        if (closing == null) {
            done?.complete(Unit)
            return
        }
        val job = scope.launch {
            try {
                withTimeoutOrNull(policy.gracefulCloseMillis) { closing.closeGracefully() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Closing anyway below.
            } finally {
                closing.close()
                closingWatch?.cancel()
                done?.complete(Unit)
            }
        }
        draining += job
    }

    private fun onConnect(result: CompletableDeferred<ConnectResult>) {
        wanted = true
        when (val s = _state.value) {
            is ConnectionState.Online -> {
                result.complete(ConnectResult.Connected(s.jid))
                probe()
            }
            is ConnectionState.Connecting, ConnectionState.WaitingForNetwork -> waiters += result
            // An explicit connect() works in the background too, for example to handle a push.
            is ConnectionState.Backoff, ConnectionState.Idle, ConnectionState.Paused, is ConnectionState.Terminal -> {
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
            is ConnectionState.Online -> when {
                // The OS reported no network while the probe from that loss is still
                // unanswered, and now a different network is here: the session's own
                // network is gone (Android gives a returning network a new id too), so
                // its socket cannot answer. Reconnect now instead of waiting out the probe.
                available && !wasAvailable && probeJob != null && id != sessionNetworkId -> {
                    dropSession()
                    failures = 0
                    if (pausedByBackground) pauseAfterLoss() else startAttempt()
                }
                !available || !wasAvailable || id != previousId -> probe()
            }
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
            ConnectionState.Idle, ConnectionState.Paused, is ConnectionState.Terminal -> Unit
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
            sessionNetworkId = attemptNetworkId
            onlineSince = clock()
            set(ConnectionState.Online(opened.jid))
            completeWaiters(ConnectResult.Connected(opened.jid))
            scheduleKeepalive()
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
            if (pushHold) onPushOnline()
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
            pausedByBackground -> pauseAfterLoss()
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
        val idle = probeIsIdle
        probeIsIdle = false
        if (ok) {
            if (idle) keepalive?.onIdleSuccess(networkKind)
            scheduleKeepalive() // Any answered probe proves the link; restart the idle clock.
            return
        }
        // A link that dies while idle tells us this network's NAT forgets us sooner.
        if (idle) keepalive?.onIdleDeath(networkKind)
        dropSession()
        failures = 0
        if (pausedByBackground) pauseAfterLoss() else startAttempt() // Waits for the network if there is none.
    }

    private fun probe(idle: Boolean = false) {
        val live = session ?: return
        if (probeJob != null) return // One probe at a time; its result answers every trigger.
        probeIsIdle = idle
        cancelKeepalive()
        val myEpoch = epoch
        val timeout = if (foreground) policy.probeTimeoutMillis else policy.backgroundProbeTimeoutMillis
        probeJob = scope.launch {
            val ok = try {
                withTimeoutOrNull(timeout) { live.probe() } ?: false
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

    private fun scheduleKeepalive() {
        val ka = keepalive ?: return
        if (session == null) return
        cancelKeepalive()
        val token = keepaliveToken
        val interval = ka.intervalFor(networkKind)
        keepaliveJob = scope.launch {
            delay(interval)
            deliver(Intent.KeepaliveDue(token))
        }
    }

    private fun cancelKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        keepaliveToken++
    }

    /** Full jitter: random in [0, min(cap, base·2^(n-2))]. */
    internal fun backoffDelay(failures: Int): Long {
        val exponent = (failures - 2).coerceIn(0, 30)
        val cap = if (foreground) policy.maxDelayMillis else policy.backgroundMaxDelayMillis
        val ceiling = minOf(cap, policy.baseDelayMillis shl exponent).coerceAtLeast(1)
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
        quietJob?.cancel()
        quietJob = null
        probeJob?.cancel()
        probeJob = null
        probeIsIdle = false
        cancelKeepalive()
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
        keepaliveJob?.cancel()
        backgroundJob?.cancel()
        quietJob?.cancel()
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
                is Intent.PushWake -> leftover.result.complete(ConnectResult.Cancelled)
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
