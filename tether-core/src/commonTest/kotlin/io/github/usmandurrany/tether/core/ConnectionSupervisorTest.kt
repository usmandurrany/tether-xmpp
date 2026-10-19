package io.github.usmandurrany.tether.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Records every session so tests can check the single-connection invariants. */
internal class FakeServer(private val scope: CoroutineScope) {
    var openDelayMillis = 100L
    /** Like a blocking socket connect: the open ignores cancellation and finishes anyway. */
    var blockingOpen: () -> Boolean = { false }
    var nextFailure: (() -> Throwable?) = { null }
    var probeAnswers: () -> Boolean = { true }
    var probeDelayMillis = 50L

    var opensStarted = 0
    var opensInFlight = 0
    var maxOpensInFlight = 0
    val sessions = ArrayList<FakeSession>()
    val live get() = sessions.filter { !it.closed }
    var maxLive = 0
    var probesInFlight = 0
    var maxProbesInFlight = 0
    /** Set by the test around disconnect() calls. */
    var disconnecting = false

    inner class FakeSession(val index: Int) : LiveSession {
        override val jid = FullJid.parse("juliet@example.com/res$index")
        val ended = CompletableDeferred<Throwable?>()
        var closed = false
        var closedByServer = false
        var probeFailed = false
        var closedDuringDisconnect = false
        /** Set when the supervisor reported this session as Online. */
        var wasOnline = false

        override suspend fun awaitClosed(): Throwable? = ended.await()

        override suspend fun probe(): Boolean {
            var ok = false
            probesInFlight++
            maxProbesInFlight = maxOf(maxProbesInFlight, probesInFlight)
            try {
                delay(probeDelayMillis)
                ok = !closed && probeAnswers()
                return ok
            } finally {
                probesInFlight--
                if (!ok) probeFailed = true // Includes a probe cut off by the timeout.
            }
        }

        override fun close() {
            if (!closed) closedDuringDisconnect = disconnecting
            closed = true
            ended.complete(null)
        }

        /** The server drops the stream. */
        fun drop(cause: Throwable? = null) {
            closedByServer = true
            closed = true
            ended.complete(cause)
        }
    }

    val opener = SessionOpener {
        opensStarted++
        opensInFlight++
        maxOpensInFlight = maxOf(maxOpensInFlight, opensInFlight)
        try {
            if (blockingOpen()) withContext(NonCancellable) { delay(openDelayMillis) } else delay(openDelayMillis)
            nextFailure()?.let { throw it }
            val s = FakeSession(sessions.size)
            sessions += s
            maxLive = maxOf(maxLive, live.size)
            s
        } finally {
            opensInFlight--
        }
    }
}

private fun lost() = XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, "reset")
private fun badPassword() = XmppNegotiationException(NegotiationFailure.AUTHENTICATION, "not-authorized")
private fun conflict() = XmppNegotiationException(NegotiationFailure.STREAM_ERROR, "conflict", streamError = "conflict")

private fun TestScope.supervisor(server: FakeServer, seed: Int = 1, policy: ReconnectPolicy = ReconnectPolicy()) =
    ConnectionSupervisor(backgroundScope, server.opener, policy, Random(seed)) { testScheduler.currentTime }

/**
 * The supervisor runs in backgroundScope, which advanceUntilIdle does not wait
 * for, so tests move virtual time forward explicitly.
 */
private fun TestScope.settle(millis: Long = 10 * 60_000) {
    advanceTimeBy(millis)
    runCurrent()
}

class ConnectionSupervisorTest {
    @Test
    fun concurrentConnectCallsShareOneAttempt() = runTest {
        val server = FakeServer(backgroundScope)
        val sup = supervisor(server)
        val callers = List(20) { async { sup.connect() } }
        settle()
        callers.forEach { assertIs<ConnectResult.Connected>(it.await()) }
        assertEquals(1, server.opensStarted)
        assertIs<ConnectionState.Online>(sup.state.value)
    }

    @Test
    fun connectWhileOnlineProbesInsteadOfReconnecting() = runTest {
        val server = FakeServer(backgroundScope)
        val sup = supervisor(server)
        sup.connect()
        repeat(5) { assertIs<ConnectResult.Connected>(sup.connect()) }
        sup.wake()
        settle()
        assertEquals(1, server.opensStarted)
        assertEquals(1, server.live.size)
    }

    @Test
    fun failedProbeReconnects() = runTest {
        val server = FakeServer(backgroundScope)
        val sup = supervisor(server)
        sup.connect()
        server.probeAnswers = { false }
        sup.wake()
        settle()
        assertEquals(2, server.opensStarted)
        assertTrue(server.sessions[0].closed)
        assertEquals(listOf(server.sessions[1]), server.live)
    }

    @Test
    fun silentServerFailsTheProbeByTimeout() = runTest {
        val server = FakeServer(backgroundScope)
        val sup = supervisor(server, policy = ReconnectPolicy(probeTimeoutMillis = 1_000))
        sup.connect()
        server.probeDelayMillis = 60_000
        sup.wake()
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(server.sessions[0].closed)
        assertEquals(2, server.opensStarted)
    }

    @Test
    fun retriesWithGrowingJitteredBackoff() = runTest {
        val server = FakeServer(backgroundScope)
        var failuresLeft = 5
        server.nextFailure = { if (failuresLeft-- > 0) lost() else null }
        val sup = supervisor(server)
        val delays = ArrayList<Long>()
        backgroundScope.launch {
            sup.state.collect { if (it is ConnectionState.Backoff) delays += it.delayMillis }
        }
        val result = async { sup.connect() }
        settle()
        assertIs<ConnectResult.Connected>(result.await())
        assertEquals(6, server.opensStarted)
        // First retry immediate, then random waits under 1 s, 2 s, 4 s, 8 s.
        assertEquals(4, delays.size)
        delays.forEachIndexed { i, d -> assertTrue(d in 1..(1_000L shl i), "delay $i was $d") }
    }

    @Test
    fun badPasswordStopsWithoutRetrying() = runTest {
        val server = FakeServer(backgroundScope)
        server.nextFailure = { badPassword() }
        val sup = supervisor(server)
        val result = sup.connect()
        assertIs<ConnectResult.Failed>(result)
        assertIs<ConnectionState.Terminal>(sup.state.value)
        sup.wake()
        sup.networkChanged(true, "wifi-2")
        settle()
        assertEquals(1, server.opensStarted)

        // Only an explicit connect starts again.
        server.nextFailure = { null }
        assertIs<ConnectResult.Connected>(sup.connect())
        assertEquals(2, server.opensStarted)
    }

    @Test
    fun conflictFromServerIsTerminal() = runTest {
        val server = FakeServer(backgroundScope)
        val sup = supervisor(server)
        sup.connect()
        advanceTimeBy(60_000)
        server.sessions[0].drop(conflict())
        settle()
        assertIs<ConnectionState.Terminal>(sup.state.value)
        assertEquals(1, server.opensStarted)
    }

    @Test
    fun droppedStableSessionReconnectsImmediately() = runTest {
        val server = FakeServer(backgroundScope)
        val sup = supervisor(server)
        sup.connect()
        advanceTimeBy(60_000)
        server.sessions[0].drop(lost())
        runCurrent()
        assertEquals(ConnectionState.Connecting(1), sup.state.value)
        settle()
        assertIs<ConnectionState.Online>(sup.state.value)
    }

    @Test
    fun serverThatKicksImmediatelyGetsBackoff() = runTest {
        val server = FakeServer(backgroundScope)
        val sup = supervisor(server)
        val backoffs = ArrayList<Long>()
        backgroundScope.launch {
            sup.state.collect { if (it is ConnectionState.Backoff) backoffs += it.delayMillis }
        }
        sup.connect()
        repeat(6) {
            val count = server.sessions.size
            server.sessions.last().drop(lost())
            while (server.sessions.size == count || sup.state.value !is ConnectionState.Online) settle(100)
        }
        // Each session lived only moments, so these count as failures: the first
        // retry is immediate and later ones wait, instead of a tight loop.
        assertEquals(5, backoffs.size)
        assertTrue(testScheduler.currentTime > 1_000)
    }

    @Test
    fun noAttemptsWithoutNetwork() = runTest {
        val server = FakeServer(backgroundScope)
        val sup = supervisor(server)
        sup.networkChanged(false)
        val result = async { sup.connect() }
        advanceTimeBy(600_000)
        assertEquals(ConnectionState.WaitingForNetwork, sup.state.value)
        assertEquals(0, server.opensStarted)
        sup.networkChanged(true, "wifi")
        settle()
        assertIs<ConnectResult.Connected>(result.await())
    }

    @Test
    fun networkSwitchDuringAttemptRestartsAndClosesTheLateSession() = runTest {
        val server = FakeServer(backgroundScope)
        server.openDelayMillis = 1_000
        val sup = supervisor(server)
        sup.networkChanged(true, "wifi")
        val result = async { sup.connect() }
        advanceTimeBy(500)
        sup.networkChanged(true, "cellular")
        settle()
        assertIs<ConnectResult.Connected>(result.await())
        assertEquals(1, server.live.size)
        assertEquals(1, server.maxLive)
    }

    @Test
    fun blockingAttemptIsAwaitedAndItsLateSessionClosed() = runTest {
        val server = FakeServer(backgroundScope)
        server.openDelayMillis = 1_000
        server.blockingOpen = { true }
        val sup = supervisor(server)
        sup.networkChanged(true, "wifi")
        val result = async { sup.connect() }
        advanceTimeBy(500)
        sup.networkChanged(true, "cellular")
        settle()
        assertIs<ConnectResult.Connected>(result.await())
        assertEquals(2, server.opensStarted)
        assertEquals(1, server.maxOpensInFlight)
        assertTrue(server.sessions[0].closed, "the late session from the cancelled attempt must be closed")
        assertEquals(listOf(server.sessions[1]), server.live)
    }

    @Test
    fun networkLossWhileOnlineOnlyProbes() = runTest {
        val server = FakeServer(backgroundScope)
        val sup = supervisor(server)
        sup.connect()
        sup.networkChanged(false)
        settle()
        // The probe answered, so the session survives a flaky OS callback.
        assertIs<ConnectionState.Online>(sup.state.value)
        assertEquals(1, server.opensStarted)

        server.probeAnswers = { false }
        sup.networkChanged(false)
        settle()
        assertEquals(ConnectionState.WaitingForNetwork, sup.state.value)
        assertEquals(0, server.live.size)
    }

    @Test
    fun wakeSkipsTheBackoffWait() = runTest {
        val server = FakeServer(backgroundScope)
        var fail = true
        server.nextFailure = { if (fail) lost() else null }
        val sup = supervisor(server, policy = ReconnectPolicy(baseDelayMillis = 60_000, maxDelayMillis = 60_000))
        backgroundScope.launch { sup.connect() }
        while (sup.state.value !is ConnectionState.Backoff) advanceTimeBy(100)
        fail = false
        sup.wake()
        advanceTimeBy(200)
        runCurrent()
        assertIs<ConnectionState.Online>(sup.state.value)
    }

    @Test
    fun disconnectCancelsWaitersAndClosesEverything() = runTest {
        val server = FakeServer(backgroundScope)
        server.openDelayMillis = 1_000
        val sup = supervisor(server)
        val result = async { sup.connect() }
        advanceTimeBy(500)
        sup.disconnect()
        assertEquals(ConnectResult.Cancelled, result.await())
        settle()
        assertEquals(ConnectionState.Idle, sup.state.value)
        assertEquals(0, server.live.size)
    }

    @Test
    fun registryReturnsOneSupervisorPerAccount() = runTest {
        val registry = ConnectionRegistry()
        val server = FakeServer(backgroundScope)
        var created = 0
        val account = BareJid.parse("juliet@example.com")
        val all = List(10) { async { registry.getOrCreate(account) { created++; supervisor(server) } } }.map { it.await() }
        assertEquals(1, created)
        assertTrue(all.all { it === all[0] })
    }

    /**
     * Design §4.1a rule 9: random interleavings of every trigger, with a
     * randomly unreliable server, under virtual time. Invariants checked:
     * never two opens at once, never two live sessions, no online session
     * closed by the client without a failed probe or a disconnect, and every
     * connect() caller gets an answer.
     */
    @Test
    fun randomInterleavingsKeepTheInvariants() {
        repeat(300) { seed -> runTest { interleaving(seed) } }
    }

    private suspend fun TestScope.interleaving(seed: Int) {
        val rnd = Random(seed)
        val server = FakeServer(backgroundScope)
        server.openDelayMillis = rnd.nextLong(10, 3_000)
        server.blockingOpen = { rnd.nextBoolean() }
        server.nextFailure = {
            when (rnd.nextInt(10)) {
                0, 1, 2 -> lost()
                3 -> if (rnd.nextInt(8) == 0) badPassword() else null
                else -> null
            }
        }
        server.probeAnswers = { rnd.nextInt(4) != 0 }
        server.probeDelayMillis = rnd.nextLong(1, 15_000)
        val sup = ConnectionSupervisor(
            backgroundScope, server.opener, ReconnectPolicy(probeTimeoutMillis = 5_000), Random(seed),
        ) { testScheduler.currentTime }

        backgroundScope.launch {
            sup.state.collect { s ->
                if (s is ConnectionState.Online) server.sessions.first { it.jid == s.jid }.wasOnline = true
            }
        }
        val callers = ArrayList<kotlinx.coroutines.Deferred<ConnectResult>>()
        val networks = listOf("wifi", "cellular", "wifi-2")

        repeat(120) {
            when (rnd.nextInt(9)) {
                0, 1 -> callers += async { sup.connect() }
                2 -> sup.wake()
                3 -> sup.networkChanged(rnd.nextInt(5) != 0, networks.random(rnd))
                4 -> server.live.randomOrNull(rnd)?.drop(if (rnd.nextInt(10) == 0) conflict() else lost())
                5 -> if (rnd.nextInt(6) == 0) {
                    server.disconnecting = true
                    sup.disconnect()
                    server.disconnecting = false
                }
                else -> Unit
            }
            advanceTimeBy(rnd.nextLong(0, 4_000))
            runCurrent()
            check(server)
        }

        // Settle: a reliable server and network must bring every caller to an answer.
        server.nextFailure = { null }
        server.probeAnswers = { true }
        sup.networkChanged(true, "final")
        callers += async { sup.connect() }
        advanceTimeBy(30 * 60_000)
        runCurrent()
        check(server)
        callers.forEachIndexed { i, c ->
            if (!c.isCompleted) fail("seed $seed: caller $i got no answer; state ${sup.state.value}")
        }
        assertIs<ConnectionState.Online>(sup.state.value, "seed $seed")
        assertEquals(1, server.live.size, "seed $seed")
        sup.disconnect()
        assertEquals(0, server.live.size, "seed $seed")
    }

    private fun check(server: FakeServer) {
        assertTrue(server.maxOpensInFlight <= 1, "two attempts ran at once")
        assertTrue(server.maxLive <= 1, "two sessions were live at once")
        assertTrue(server.maxProbesInFlight <= 1, "two probes ran at once")
        for (s in server.sessions) {
            if (s.closed && s.wasOnline && !s.closedByServer && !s.probeFailed && !s.closedDuringDisconnect) {
                // Closing an online session is allowed only for a failed probe, a server drop, or disconnect().
                fail("session ${s.index} was torn down without cause")
            }
        }
    }
}
