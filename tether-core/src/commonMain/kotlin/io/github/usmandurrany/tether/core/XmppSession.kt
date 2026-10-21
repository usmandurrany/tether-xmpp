package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.crypto.Crypto
import io.github.usmandurrany.tether.xml.Attribute
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.time.TimeSource

/** Saved XEP-0198 state. [resumptionId] is null when the session cannot be resumed. */
internal class SmSnapshot(
    val resumptionId: String?,
    val location: String?,
    val maxResumeSeconds: Int?,
    val inbound: Long,
    val acknowledged: Long,
    val pending: List<Element>,
)

/** Tuning for a live session. */
public data class SessionPolicy(
    /** With SM on, ask the server to confirm after this many unconfirmed stanzas (XEP-0198 §4). */
    val requestAckEvery: Int = 5,
    /**
     * Incoming stanzas buffered before reading pauses. Unlimited by default:
     * while reading is paused, the server's `<r/>` goes unanswered, and
     * servers that cap unconfirmed stanzas (MongooseIM's default is 100) end
     * the stream with `<resource-constraint/>`, as seen during a burst of
     * redelivered messages. A limit trades that for bounded memory.
     */
    val incomingBuffer: Int = Channel.UNLIMITED,
) {
    init {
        require(requestAckEvery >= 1 && incomingBuffer >= 1)
    }
}

/**
 * An authenticated, bound stream that is being read. Created by
 * [XmppSessionOpener]; the [ConnectionSupervisor] drives it through [LiveSession].
 *
 * - With Stream Management, every sent stanza stays queued until the server
 *   confirms it, and the queue carries over to the next session (resumed or
 *   fresh), so nothing is lost when the connection drops.
 * - Reading happens inside [awaitClosed], which the supervisor calls once.
 * - The server's XEP-0199 pings are answered here. Other incoming IQ requests
 *   are delivered on [incoming] and must be answered by the app (RFC 6120 §8.2.3).
 */
public class XmppSession private constructor(
    private val negotiated: NegotiatedSession,
    private val policy: SessionPolicy,
) : LiveSession {
    override val jid: FullJid get() = negotiated.jid
    public val capabilities: ServerCapabilities get() = negotiated.capabilities
    public val streamManagement: StreamManagement? get() = negotiated.streamManagement

    /** True when this session continued the previous one through XEP-0198 resumption. */
    public val resumed: Boolean get() = negotiated.resumed

    private val reader = negotiated.reader
    private val transport = negotiated.transport
    private val sm = negotiated.streamManagement

    /** Guards writes and all SM, IQ and probe bookkeeping, so counters match what went on the wire. */
    private val lock = Mutex()
    private class PendingIq(val answer: CompletableDeferred<Element>, val ordered: Boolean)

    /** An ordered answer travels with the incoming stanzas, so it is seen after everything that came before it. */
    private class OrderedAnswer(val waiter: CompletableDeferred<Element>, val answer: Element)

    private val pendingIqs = HashMap<String, PendingIq>()
    private val ackWaiters = ArrayList<CompletableDeferred<Unit>>()
    private val incomingChannel = Channel<Any>(policy.incomingBuffer)
    private val ended = CompletableDeferred<Throwable?>()
    private var loopStarted = false
    private var idCounter = 0
    private val idPrefix = Base64.UrlSafe.encode(Crypto.randomBytes(6))

    @Volatile
    private var closed = false

    /** When something last came in or a stanza went out. */
    @Volatile
    private var lastActivity = TimeSource.Monotonic.markNow()

    /** Stanzas from the server that the app should handle (messages, presence, IQ requests). */
    public val incoming: Flow<Element> = incomingChannel.receiveAsFlow().transform { item ->
        if (item is OrderedAnswer) item.waiter.complete(item.answer) else emit(item as Element)
    }

    /**
     * Sends a message, presence or IQ. An `id` is added when missing, so a
     * stanza re-sent after a reconnect can be recognised as a duplicate.
     * With SM the stanza is queued before writing, so it survives a failed write.
     */
    public suspend fun send(stanza: Element) {
        require(StreamManagement.isStanza(stanza)) { "Only message, presence and iq stanzas can be sent" }
        lock.withLock { sendLocked(withId(stanza)) }
    }

    /** Keys derived at login (null for a resumed session or PLAIN); store them to log in without the password. */
    public val scramKeys: io.github.usmandurrany.tether.sasl.ScramKeys? get() = negotiated.scramKeys

    /** Set once this session sent `</stream:stream>`: the server drops it, so it cannot be resumed. */
    @Volatile
    internal var endedGracefully: Boolean = false
        private set

    /** The SM state to save, so a restarted app can resume or re-send. Null without SM. */
    internal suspend fun smSnapshot(): SmSnapshot? {
        val sm = sm ?: return null
        return lock.withLock {
            SmSnapshot(sm.resumptionId?.takeUnless { endedGracefully }, sm.location, sm.maxResumeSeconds, sm.inbound, sm.acknowledged, sm.pending)
        }
    }

    /** False once the session has ended or been closed. */
    public val isOpen: Boolean get() = !closed

    /**
     * Like [send], but reports instead of throwing whether the stanza was taken:
     * with SM, queued (so it survives a dropped connection); without SM, written.
     * False means it was not sent at all and the caller still owns it.
     */
    internal suspend fun offer(stanza: Element): Boolean = lock.withLock {
        if (closed) return@withLock false
        if (sm == null) {
            try {
                write(withId(stanza))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }
        } else {
            try {
                sendLocked(withId(stanza))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Already in the SM queue: the next session sends it again.
            }
            true
        }
    }

    /** Writes a top-level element that is not a stanza (CSI, for example). SM does not count it. */
    internal suspend fun sendNonza(element: Element) {
        lock.withLock {
            if (closed) throw XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, "Session is closed")
            write(element)
        }
    }

    /** Sends an IQ get/set and waits for its result or error. */
    public suspend fun request(iq: Element, timeoutMillis: Long = 30_000): Element = request(iq, timeoutMillis, ordered = false)

    /**
     * With [ordered], the answer is handed over only once every stanza received
     * before it has been taken from [incoming], for queries whose results arrive
     * as separate stanzas first (XEP-0313). Needs a collector of [incoming].
     */
    internal suspend fun request(iq: Element, timeoutMillis: Long, ordered: Boolean): Element {
        require(iq.name == "iq" && iq.namespace == Ns.CLIENT && iq.attribute("type") in setOf("get", "set"))
        val stanza = withId(iq)
        val id = stanza.attribute("id")!!
        val answer = CompletableDeferred<Element>()
        lock.withLock {
            pendingIqs[id] = PendingIq(answer, ordered)
            sendLocked(stanza)
        }
        try {
            return withTimeoutOrNull(timeoutMillis) { answer.await() }
                ?: throw XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, "No answer to IQ $id within $timeoutMillis ms")
        } finally {
            lock.withLock { pendingIqs.remove(id) }
        }
    }

    /** SM `<r/>` when SM is on, otherwise an XEP-0199 ping to the server. Any answer counts as alive. */
    override suspend fun probe(): Boolean {
        if (sm != null) {
            val acked = CompletableDeferred<Unit>()
            lock.withLock {
                ackWaiters += acked
                write(element("r", Ns.SM))
            }
            acked.await()
            return true
        }
        request(
            element("iq", Ns.CLIENT) {
                attribute("type", "get")
                attribute("to", jid.bare.domain)
                element("ping", Ns.PING)
            },
        )
        return true // A result or an error both prove the server is there.
    }

    override suspend fun awaitClosed(): Throwable? {
        val first = lock.withLock {
            val start = !loopStarted && !closed
            loopStarted = true
            start
        }
        if (!first) return ended.await()
        var cause: Throwable? = null
        try {
            while (!closed) {
                val element = reader.nextLive() ?: break // Clean close by the server.
                if (!closed) handle(element)
            }
        } catch (e: CancellationException) {
            cause = e
            throw e
        } catch (e: Throwable) {
            cause = if (closed) null else e
        } finally {
            finish(cause)
        }
        return cause
    }

    /** Closes the connection now. With SM the server keeps the session resumable for a while. */
    override fun close() {
        closed = true
        transport.close()
        // If reading never started, nobody else will complete these.
        if (lock.tryLock()) {
            try {
                if (!loopStarted) finishLocked(null)
            } finally {
                lock.unlock()
            }
        }
    }

    /**
     * Ends the session cleanly, without leaving anything behind on either side:
     * 1. With CSI (XEP-0352), reports active, so the server sends what it held
     *    back while the app was in the background. A server that buffers for
     *    inactive clients (MongooseIM does) would otherwise keep those stanzas
     *    past the close and only deliver them minutes later.
     * 2. One round trip (SM `<r/>`, or a ping without SM): everything the
     *    server sent before its answer has then been read and counted, and
     *    what we sent is confirmed.
     * 3. Confirms what we received, sends `</stream:stream>`, and waits
     *    briefly for the server's own close. The server then treats the
     *    account as offline at once (RFC 6120 §4.4), which is what routes new
     *    messages to push.
     * Stanzas still unconfirmed stay queued for the next session.
     */
    override suspend fun closeGracefully() {
        if (closed) return
        try {
            val csi = capabilities.hasStreamFeature("csi", Ns.CSI)
            if (csi) runCatching { sendNonza(element("active", Ns.CSI)) }
            if (loopStarted && (sm != null || csi)) withTimeoutOrNull(2_000) { probe() }
            lock.withLock {
                if (!closed) {
                    // Confirm what we received, so the server does not treat it as undelivered on close.
                    if (sm != null) write(element("a", Ns.SM) { attribute("h", sm.inbound.toString()) })
                    reader.sendStreamClose()
                    endedGracefully = true
                }
            }
            // The server answers with its own </stream:stream>; the read loop then ends.
            if (loopStarted) withTimeoutOrNull(2_000) { ended.await() }
        } finally {
            close()
        }
    }

    /**
     * Returns once, for [quietMillis] in a row, nothing came in, nothing was
     * sent and no IQ is waiting for its answer. A catch-up query (XEP-0313)
     * counts as busy until its result arrives, so this also means it is done.
     * Throws when the session ends first.
     */
    override suspend fun awaitQuiet(quietMillis: Long) {
        require(quietMillis > 0)
        while (true) {
            if (closed) throw XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, "Session is closed")
            val busy = lock.withLock { pendingIqs.isNotEmpty() }
            val wait = if (busy) minOf(quietMillis, BUSY_POLL_MILLIS) else quietMillis - lastActivity.elapsedNow().inWholeMilliseconds
            if (!busy && wait <= 0) return
            delay(wait)
        }
    }

    /** Waits (bounded) until reading has stopped, so the SM state is final. */
    internal suspend fun awaitEnded(timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis) { ended.await() }
    }

    private suspend fun handle(element: Element) {
        lastActivity = TimeSource.Monotonic.markNow()
        if (element.namespace == Ns.SM) {
            handleSm(element)
            return
        }
        if (!StreamManagement.isStanza(element)) return // Unknown top-level elements are ignored.
        deliver(element)
        if (sm != null) lock.withLock { sm.onInboundStanza() }
    }

    private suspend fun deliver(stanza: Element) {
        if (stanza.name == "iq") {
            when (stanza.attribute("type")) {
                "result", "error" -> {
                    val waiter = lock.withLock { pendingIqs.remove(stanza.attribute("id")) } ?: return
                    if (waiter.ordered) incomingChannel.send(OrderedAnswer(waiter.answer, stanza)) else waiter.answer.complete(stanza)
                    return
                }
                "get" -> if (stanza.child("ping", Ns.PING) != null) {
                    lock.withLock {
                        sendLocked(
                            element("iq", Ns.CLIENT) {
                                attribute("type", "result")
                                attribute("id", stanza.attribute("id"))
                                attribute("to", stanza.attribute("from"))
                            },
                        )
                    }
                    return
                }
            }
        }
        incomingChannel.send(stanza)
    }

    private suspend fun handleSm(element: Element) {
        if (sm == null) return
        when (element.name) {
            "r" -> lock.withLock { write(element("a", Ns.SM) { attribute("h", sm.inbound.toString()) }) }
            "a" -> {
                val h = StreamManagement.parseH(element.attribute("h"))
                    ?: throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "<a/> without a valid h")
                lock.withLock {
                    if (!sm.onAck(h)) {
                        // XEP-0198 §4: the server claims more than we sent. Its state is broken; start over.
                        throw XmppNegotiationException(
                            NegotiationFailure.STREAM_ERROR,
                            "Server acknowledged $h stanzas but only ${sm.sent} were sent",
                            streamError = "undefined-condition",
                        )
                    }
                    ackWaiters.forEach { it.complete(Unit) }
                    ackWaiters.clear()
                }
            }
        }
    }

    /** Caller holds [lock]. */
    private suspend fun sendLocked(stanza: Element) {
        if (closed) throw XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, "Session is closed")
        lastActivity = TimeSource.Monotonic.markNow()
        if (sm == null) {
            write(stanza)
            return
        }
        sm.onSent(stanza)
        write(stanza)
        if (sm.unackedCount % policy.requestAckEvery == 0) write(element("r", Ns.SM))
    }

    private suspend fun write(element: Element) {
        reader.send(element)
    }

    private suspend fun finish(cause: Throwable?) {
        closed = true
        transport.close()
        // Runs even when the read loop was cancelled, so waiters and the next opener are never left hanging.
        withContext(NonCancellable) { lock.withLock { finishLocked(cause) } }
    }

    private fun finishLocked(cause: Throwable?) {
        val error = XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, "Session closed", cause = cause)
        pendingIqs.values.forEach { it.answer.completeExceptionally(error) }
        pendingIqs.clear()
        ackWaiters.forEach { it.completeExceptionally(error) }
        ackWaiters.clear()
        incomingChannel.close()
        ended.complete(cause)
    }

    private fun withId(stanza: Element): Element {
        if (stanza.attribute("id") != null) return stanza
        return stanza.copy(attributes = stanza.attributes + Attribute("id", "$idPrefix-${++idCounter}"))
    }

    internal companion object {
        /** How often [awaitQuiet] looks again while a request is waiting for its answer. */
        private const val BUSY_POLL_MILLIS = 250L

        /**
         * Starts a session: first re-sends what the previous session never got
         * confirmed, then queues stanzas that arrived during negotiation.
         */
        suspend fun start(
            negotiated: NegotiatedSession,
            policy: SessionPolicy,
            onConfirmed: ((List<Element>) -> Unit)? = null,
        ): XmppSession {
            val session = XmppSession(negotiated, policy)
            negotiated.streamManagement?.onConfirmed = onConfirmed
            val carried = negotiated.carryOver?.drainPending().orEmpty()
            session.lock.withLock { carried.forEach { session.sendLocked(it) } }
            negotiated.reader.takeUnclaimed().forEach { session.deliver(it) }
            return session
        }
    }
}

/**
 * The [SessionOpener] for real servers: negotiates a stream and, when the
 * previous session had Stream Management, resumes it.
 *
 * The supervisor never runs two opens at once, so the hand-over from one
 * session to the next needs no further locking.
 */
public class XmppSessionOpener(
    private val negotiator: StreamNegotiator,
    private val policy: SessionPolicy = SessionPolicy(),
    /**
     * Called with stanzas the server confirmed through XEP-0198, oldest first,
     * on the reading coroutine with the session lock held: hand them off, don't block.
     */
    private val onConfirmed: ((List<Element>) -> Unit)? = null,
) : SessionOpener {
    private var previous: XmppSession? = null
    private var restored: ResumeRequest? = null

    /** Resumes a session saved by an earlier run, on the next open, if no session has been opened since. */
    internal fun restore(request: ResumeRequest) {
        if (previous == null) restored = request
    }

    override suspend fun open(): XmppSession {
        val last = previous
        // The old read loop must have stopped so its SM counters are final.
        last?.awaitEnded(timeoutMillis = 5_000)
        // A saved session is tried until an attempt gets through, whatever its outcome then.
        // After a clean close the server has dropped the session: asking to resume it only costs a round trip.
        val resume = if (last == null) {
            restored
        } else {
            last.streamManagement?.let { ResumeRequest(it, last.jid, last.capabilities, tryResume = !last.endedGracefully) }
        }
        val session = XmppSession.start(negotiator.negotiate(resume), policy, onConfirmed)
        previous = session
        restored = null
        return session
    }
}
