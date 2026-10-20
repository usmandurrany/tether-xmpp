@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.ClientExtension
import io.github.usmandurrany.tether.core.ConnectResult
import io.github.usmandurrany.tether.core.ConnectionState
import io.github.usmandurrany.tether.core.CredentialVault
import io.github.usmandurrany.tether.core.KeyValueStore
import io.github.usmandurrany.tether.core.OutboxFullException
import io.github.usmandurrany.tether.core.SessionContext
import io.github.usmandurrany.tether.core.XmppClient
import io.github.usmandurrany.tether.xml.Element
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

/** Where a message is in its life, as the ticks in a chat show it. */
public enum class MessageStatus {
    /** Ours, queued on this device (also while offline): no tick. */
    SENDING,

    /** Ours, and the server has it: one tick. */
    SENT,

    /** Ours, and the contact's device has it: two ticks. */
    DELIVERED,

    /** Ours: the contact has read it. Theirs: we have read it, here or on another of our devices. */
    READ,

    /** Ours, and the server refused it; [Message.error] says why. */
    FAILED,

    /** Theirs, not read yet. */
    UNREAD,
}

/** One message in a chat, kept up to date by [TetherChat]. */
public data class Message(
    /** Stable for the life of the message; use it as the list key. */
    val id: String,
    /** The other side of the chat. */
    val peer: BareJid,
    /** We sent it (from this device or another of ours). */
    val outgoing: Boolean,
    val body: String,
    /** Milliseconds since the epoch: when it was sent, as the server recorded it when it knows. */
    val timestamp: Long,
    val status: MessageStatus,
    /** The server archive's id for it, when it has one. */
    val serverId: String? = null,
    /** Why it failed, for [MessageStatus.FAILED]: an XMPP error condition such as "item-not-found". */
    val error: String? = null,
    val thread: String? = null,
    /** The sender asked for a read marker (XEP-0333). */
    val markable: Boolean = false,
    /** Ours, but sent from another of our devices. */
    val fromOtherDevice: Boolean = false,
    /**
     * The app's own data sent with the message, such as a picture's link and
     * size. Read it with `message.payload(PictureType)`; see [PayloadType].
     * A [MessageStore] can keep these as one string with [Payloads.toXml].
     */
    val payloads: List<Element> = emptyList(),
)

/** One entry of the chat list. */
public data class Conversation(
    val peer: BareJid,
    /** The contact's name, or the user part of their address. */
    val name: String,
    val lastMessage: Message?,
    val unreadCount: Int,
    /** The contact is typing right now. */
    val typing: Boolean,
    val online: Boolean,
)

/** One entry of the contact list, with what we know of their presence. */
public data class Contact(
    val jid: BareJid,
    /** The name we gave them, or the user part of their address. */
    val name: String,
    val online: Boolean,
    /** Away, busy and so on, from their most important online device; null when plainly available. */
    val show: Show?,
    /** Their status text. */
    val status: String?,
    val subscription: Subscription,
    /** We asked to see their presence and they have not answered yet. */
    val pending: Boolean,
    val groups: Set<String>,
)

/**
 * Where [TetherChat] keeps messages between runs: back it with Room,
 * SQLDelight or a file. Without one, messages live in memory and chats are
 * refilled from the server archive after a restart.
 */
public interface MessageStore {
    /** Every saved message, in any order. */
    public suspend fun load(): List<Message>

    /** Adds the messages, or replaces those with the same [Message.peer], [Message.outgoing] and [Message.id]. */
    public suspend fun save(messages: List<Message>)

    /** Forgets every message, on sign-out. */
    public suspend fun clear()
}

/**
 * A complete one-to-one chat client on top of an [XmppClient]: the simple way
 * to use Tether. Turn features on or off with [ChatFeatures]; the rest is
 * handled for you.
 *
 * ```
 * val chat = TetherAndroid.chat(context, jid, password)   // or TetherJvm.chat(...)
 * chat.connect()
 *
 * chat.conversations.collect { showChatList(it) }
 * chat.messages(bob).collect { showMessages(it) }
 * chat.send(bob, "Hi Bob")
 * chat.openChat(bob)   // the chat is on screen: marks it read, loads history
 * chat.typing(bob)     // on every keystroke
 * chat.closeChat(bob)  // the chat left the screen
 * ```
 *
 * What it does for you:
 * - Keeps every message's status current: SENDING, SENT, DELIVERED, READ or
 *   FAILED for ours, UNREAD or READ for theirs. Status only ever moves forward,
 *   whatever order the network delivers the news in.
 * - Merges live messages, copies from our other devices and archived ones, so
 *   the same message never shows twice.
 * - Sends read markers for messages in a chat on screen ([openChat]) while the app is in the
 *   foreground, and marks messages read when another of our devices read them.
 * - Sends typing notifications from [typing] calls, with the pause after a
 *   few idle seconds, and clears a contact's "typing" when they go quiet.
 * - Rebuilds recent chats from the server archive on a new device, and catches
 *   up on what was sent and received elsewhere after being offline.
 * - Queues everything while offline; it goes out on the next connection.
 *
 * Every function is safe to call from any thread, including the UI thread:
 * the non-suspending ones return at once, and the suspending ones never block.
 * All flows are [StateFlow]s, so nothing is lost by collecting late.
 *
 * Create it before [connect], once per account. [im] and [client] give the
 * protocol-level API for anything this class does not cover.
 */
public class TetherChat(
    /** The connection. Prefer [connect], [disconnect] and [close] here to calling it directly. */
    public val client: XmppClient,
    public val features: ChatFeatures = ChatFeatures(),
    /** Keeps the contact list between runs; normally the same store the client keeps its state in. */
    private val store: KeyValueStore? = null,
    private val messageStore: MessageStore? = null,
    presence: OwnPresence = OwnPresence(),
    /** Closed with the chat, for platform wiring such as lifecycle observers. */
    private val resources: AutoCloseable? = null,
) {
    private val rosterStore = store?.let { StoredRoster(it, client.account) }

    /** The protocol-level features, for advanced use. */
    public val im: InstantMessaging = InstantMessaging(client, rosterStore, presence, features)

    private val scope = client.coroutineScope

    /** Our own account. */
    public val account: BareJid get() = client.account

    /** Online, connecting, waiting for the network, or stopped on an error the user must fix. */
    public val connection: StateFlow<ConnectionState> get() = client.state

    /** Tell contacts when we read their messages; a privacy switch that can change at any time. */
    @Volatile
    public var sendReadMarkers: Boolean = features.sendReadMarkers

    private val threads = MutableStateFlow<Map<BareJid, List<Message>>>(emptyMap())
    private val typingTokens = MutableStateFlow<Map<BareJid, Long>>(emptyMap())
    private val openChats = MutableStateFlow<Set<BareJid>>(emptySet())
    private val requests = MutableStateFlow<Set<BareJid>>(emptySet())

    /** Every message, per contact, oldest first. */
    public val allMessages: StateFlow<Map<BareJid, List<Message>>> = threads.asStateFlow()

    /** Contacts typing right now. */
    public val typingContacts: StateFlow<Set<BareJid>> =
        typingTokens.map { it.keys }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** The chat list, most recent first. */
    public val conversations: StateFlow<List<Conversation>> =
        combine(threads, typingContacts, im.roster.items, im.presences.contacts) { all, typing, roster, online ->
            all.filterValues { it.isNotEmpty() }.map { (peer, messages) ->
                Conversation(
                    peer = peer,
                    name = displayName(peer, roster[peer]?.name),
                    lastMessage = messages.lastOrNull(),
                    unreadCount = messages.count { it.status == MessageStatus.UNREAD },
                    typing = peer in typing,
                    online = !online[peer].isNullOrEmpty(),
                )
            }.sortedByDescending { it.lastMessage?.timestamp ?: 0 }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Unread messages in every chat, for an app badge. */
    public val unreadCount: StateFlow<Int> =
        threads.map { all -> all.values.sumOf { list -> list.count { it.status == MessageStatus.UNREAD } } }
            .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, 0)

    /** The contact list, sorted by name. Empty when [ChatFeatures.contacts] is off. */
    public val contacts: StateFlow<List<Contact>> =
        combine(im.roster.items, im.presences.contacts) { roster, online ->
            roster.values.map { item ->
                val best = online[item.jid].orEmpty().maxByOrNull { it.priority }
                Contact(
                    jid = item.jid,
                    name = displayName(item.jid, item.name),
                    online = best != null,
                    show = best?.show,
                    status = best?.status,
                    subscription = item.subscription,
                    pending = item.pendingOut,
                    groups = item.groups,
                )
            }.sortedBy { it.name.lowercase() }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** People asking to see our presence: answer with [approve] or [deny]. */
    public val contactRequests: StateFlow<Set<BareJid>> = requests.asStateFlow()

    /** Outgoing work (messages, markers, typing), one at a time, so it goes out in order. */
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val saves = Channel<Message>(Channel.UNLIMITED)

    // Typing we send, touched only from the work queue.
    private val composing = HashSet<BareJid>()
    private val pauseTimers = HashMap<BareJid, Job>()
    private var typingCounter = 0L

    private val historyMutex = Mutex()
    private val cursors = HashMap<BareJid, Cursor>()

    private class Cursor(val before: String?, val complete: Boolean)

    init {
        client.install(SessionWatcher())
        // UNDISPATCHED: subscribed before the constructor returns, so no event is missed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { im.messaging.events.collect(::onEvent) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            im.presences.subscriptions.collect { event ->
                when (event) {
                    is SubscriptionEvent.Requested -> requests.update { it + event.from }
                    else -> requests.update { it - event.from }
                }
            }
        }
        scope.launch {
            for (job in work) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // One failed send must not stop the queue.
                }
            }
        }
        if (messageStore != null) {
            scope.launch {
                runCatching { messageStore.load() }.getOrNull()?.let { merge(it) { old, new -> old.mergedWith(new) } }
                for (first in saves) {
                    val batch = arrayListOf(first)
                    while (true) batch += saves.tryReceive().getOrNull() ?: break
                    runCatching { messageStore.save(batch) }
                }
            }
        }
        // Read what is on screen while the app is in the foreground.
        scope.launch {
            combine(threads, openChats, client.foreground) { _, open, foreground -> if (foreground) open else emptySet() }
                .collect { visible -> visible.forEach(::markRead) }
        }
    }

    // --- Connection -------------------------------------------------------------------

    /**
     * Connects and returns once the first attempt finishes. After that the
     * connection is kept up on its own (reconnecting, resuming, following the
     * network and the app's lifecycle) until [disconnect] or [close].
     */
    public suspend fun connect(): ConnectResult = client.connect()

    /** Goes offline, keeping everything for the next [connect]. */
    public suspend fun disconnect(): Unit = client.disconnect()

    /** Stops for good. Create a new [TetherChat] to sign in again. */
    public fun close() {
        runCatching { resources?.close() }
        client.close()
    }

    /**
     * Signs out: stops push, disconnects, and forgets the saved login, contact
     * list and messages, so the next sign-in needs the password.
     */
    public suspend fun signOut() {
        runCatching { im.push.unregister() }
        runCatching { client.disconnect() }
        store?.let { runCatching { CredentialVault(it).clear(account) } }
        runCatching { rosterStore?.clear() }
        runCatching { messageStore?.clear() }
        threads.value = emptyMap()
        close()
    }

    // --- Messages ---------------------------------------------------------------------

    /** One chat's messages, oldest first. */
    public fun messages(peer: BareJid): StateFlow<List<Message>> =
        threads.map { it[peer].orEmpty() }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(), threads.value[peer].orEmpty())

    /**
     * Sends [text] to [to] and returns the message at once, as SENDING. Works
     * offline: it waits on the device and goes out on the next connection.
     *
     * [payloads] carry the app's own data in the same message, such as
     * `PictureType.write(picture)` with the caption as [text]; see
     * [PayloadType]. [text] may be empty when the data says it all, but it is
     * what other XMPP apps show, so a caption or a link is a better fallback.
     *
     * Throws [IllegalArgumentException] when a payload uses a namespace the
     * SDK reserves.
     */
    public fun send(to: BareJid, text: String, vararg payloads: Element): Message {
        val extra = payloads.toList()
        Payloads.requireSendable(extra)
        val message = Message(
            id = newId(),
            peer = to,
            outgoing = true,
            body = text,
            timestamp = now(),
            status = MessageStatus.SENDING,
            markable = features.readMarkers,
            payloads = extra,
        )
        merge(listOf(message)) { old, _ -> old }
        work.trySend {
            stopComposing(to)
            try {
                im.messaging.send(to, text, id = message.id, payloads = extra)
                // Without stream management the server never confirms, so on its way is the best we know.
                val session = client.session.value
                if (session != null && session.streamManagement == null) setStatus(message.id, MessageStatus.SENT)
            } catch (e: OutboxFullException) {
                setStatus(message.id, MessageStatus.FAILED, error = "outbox-full")
            }
        }
        return message
    }

    /**
     * Sends a failed message again, as a new message with the same text and
     * payloads. The failed one is removed.
     */
    public fun retry(message: Message): Message? {
        if (!message.outgoing || message.status != MessageStatus.FAILED) return null
        remove(message)
        return send(message.peer, message.body, *message.payloads.toTypedArray())
    }

    /**
     * The chat with [peer] is on screen. While it is, and the app is in the
     * foreground, its messages are marked read (telling the contact, when
     * read markers are on). The first time, the newest page of history loads.
     * Call [closeChat] when it leaves the screen.
     */
    public fun openChat(peer: BareJid) {
        openChats.update { it + peer }
        if (features.history) scope.launch { runCatching { loadNewest(peer) } }
    }

    /** The chat with [peer] left the screen. */
    public fun closeChat(peer: BareJid) {
        openChats.update { it - peer }
        work.trySend { stopComposing(peer, tell = true) }
    }

    /**
     * Call on every change of the text the user is typing to [peer]. Sends
     * "typing", and "paused" after five idle seconds. Sending the message ends it.
     */
    public fun typing(peer: BareJid) {
        if (!features.typingIndicators) return
        work.trySend {
            if (composing.add(peer)) im.messaging.sendChatState(peer, ChatState.COMPOSING)
            pauseTimers.remove(peer)?.cancel()
            pauseTimers[peer] = scope.launch {
                delay(TYPING_PAUSE_MILLIS)
                work.trySend { if (composing.remove(peer)) im.messaging.sendChatState(peer, ChatState.PAUSED) }
            }
        }
    }

    /**
     * Loads the page of history before the oldest message shown for [peer].
     * Returns false when there is nothing older (or no archive on the server).
     */
    public suspend fun loadOlder(peer: BareJid): Boolean {
        if (!features.history) return false
        awaitOnline()
        if (!im.history.available.value) return false
        return historyMutex.withLock {
            val cursor = cursors[peer]
            if (cursor?.complete == true) return@withLock false
            val page = im.history.fetch(with = peer, before = cursor?.before, max = PAGE)
            importPage(page, unread = false)
            cursors[peer] = Cursor(page.first ?: cursor?.before, page.complete || page.messages.isEmpty())
            !page.complete && page.messages.isNotEmpty()
        }
    }

    // --- Contacts and presence --------------------------------------------------------

    /**
     * Adds [jid] to the contact list (or renames them) and asks to see their
     * presence. Needs a connection; throws [XmppStanzaException] if the server refuses.
     */
    public suspend fun addContact(jid: BareJid, name: String? = null) {
        im.roster.set(jid, name = name?.ifBlank { null })
        if (features.presence && im.roster.items.value[jid]?.subscription.let { it != Subscription.TO && it != Subscription.BOTH }) {
            im.presences.request(jid)
        }
    }

    /** Removes [jid] from the contact list; neither side sees the other's presence any more. */
    public suspend fun removeContact(jid: BareJid) {
        im.roster.remove(jid)
    }

    /** Lets [jid] see our presence, and asks to see theirs if we do not already. */
    public suspend fun approve(jid: BareJid) {
        im.presences.approve(jid)
        val subscription = im.roster.items.value[jid]?.subscription
        if (subscription != Subscription.TO && subscription != Subscription.BOTH) im.presences.request(jid)
        requests.update { it - jid }
    }

    /** Refuses [jid]'s request to see our presence. */
    public suspend fun deny(jid: BareJid) {
        im.presences.deny(jid)
        requests.update { it - jid }
    }

    /** Changes what contacts see: away, busy, a status text. */
    public suspend fun setPresence(show: Show? = null, status: String? = null) {
        im.presences.set(OwnPresence(show = show, status = status))
    }

    // --- Push -------------------------------------------------------------------------

    /** Registers this device for push notifications; see [PushTarget] for the fields per server. */
    public suspend fun registerPush(target: PushTarget): Unit = im.push.register(target)

    /** Stops push notifications to this device. */
    public suspend fun unregisterPush(): Unit = im.push.unregister()

    // --- Internals --------------------------------------------------------------------

    private fun onEvent(event: MessageEvent) {
        when (event) {
            is MessageEvent.Received -> {
                val m = event.message.toMessage(unread = true) ?: return
                merge(listOf(m)) { old, new -> old.mergedWith(new) }
                if (!m.outgoing) typingTokens.update { it - m.peer }
            }
            is MessageEvent.SentToServer -> setStatus(event.id, MessageStatus.SENT)
            is MessageEvent.Delivered -> setStatus(event.id, MessageStatus.DELIVERED, peer = event.peer)
            is MessageEvent.Displayed -> if (features.readMarkers) markUpTo(event.peer, event.id, outgoing = true)
            is MessageEvent.DisplayedElsewhere -> markUpTo(event.peer, event.id, outgoing = false)
            is MessageEvent.Failed -> setStatus(event.id, MessageStatus.FAILED, peer = event.peer, error = event.condition ?: "error")
            is MessageEvent.ChatStateChanged -> if (features.typingIndicators) {
                if (event.state == ChatState.COMPOSING) {
                    val token = ++typingCounter
                    typingTokens.update { it + (event.peer to token) }
                    // A contact who stops without saying so (closed app, lost network) stops "typing" here too.
                    scope.launch {
                        delay(TYPING_TIMEOUT_MILLIS)
                        typingTokens.update { if (it[event.peer] == token) it - event.peer else it }
                    }
                } else {
                    typingTokens.update { it - event.peer }
                }
            }
        }
    }

    private suspend fun stopComposing(peer: BareJid, tell: Boolean = false) {
        pauseTimers.remove(peer)?.cancel()
        if (composing.remove(peer) && tell) im.messaging.sendChatState(peer, ChatState.PAUSED)
    }

    /** Marks [peer]'s unread messages read and tells them, if they asked and we may. */
    private fun markRead(peer: BareJid) {
        var newestMarkable: Message? = null
        update(peer) { list ->
            if (list.none { it.status == MessageStatus.UNREAD }) return@update list
            newestMarkable = list.lastOrNull { !it.outgoing && it.status == MessageStatus.UNREAD && it.markable }
            list.map { if (it.status == MessageStatus.UNREAD) it.copy(status = MessageStatus.READ) else it }
        }
        val marker = newestMarkable ?: return
        if (features.readMarkers && sendReadMarkers) {
            work.trySend { im.messaging.markDisplayed(peer, marker.id, marker.thread) }
        }
    }

    /** A marker for [id] covers it and every earlier message in the same direction. */
    private fun markUpTo(peer: BareJid, id: String, outgoing: Boolean) {
        update(peer) { list ->
            val index = list.indexOfFirst { it.id == id && it.outgoing == outgoing }
            if (index < 0) return@update list
            list.mapIndexed { i, m -> if (i <= index && m.outgoing == outgoing) m.raisedTo(MessageStatus.READ) else m }
        }
    }

    private fun setStatus(id: String, status: MessageStatus, peer: BareJid? = null, error: String? = null) {
        val peers = peer?.let(::listOf) ?: threads.value.filterValues { list -> list.any { it.id == id } }.keys
        for (p in peers) {
            update(p) { list ->
                list.map { m ->
                    if (m.outgoing && m.id == id) {
                        m.raisedTo(status).let { if (it.status == MessageStatus.FAILED && error != null) it.copy(error = error) else it }
                    } else {
                        m
                    }
                }
            }
        }
    }

    private fun remove(message: Message) {
        update(message.peer) { list -> list.filterNot { it.id == message.id && it.outgoing == message.outgoing } }
    }

    /** Changes one chat and saves what changed. */
    private fun update(peer: BareJid, change: (List<Message>) -> List<Message>) {
        var changed = emptyList<Message>()
        threads.update { all ->
            val before = all[peer].orEmpty()
            val after = change(before)
            changed = if (after === before || messageStore == null) emptyList() else after.filter { it !in before }
            if (after === before) all else all + (peer to after)
        }
        changed.forEach { saves.trySend(it) }
    }

    /** Adds [incoming] messages, combining each with one already known through [combine]. */
    private fun merge(incoming: List<Message>, combine: (old: Message, new: Message) -> Message) {
        for ((peer, batch) in incoming.groupBy { it.peer }) {
            update(peer) { list ->
                var result = list
                for (m in batch) {
                    val index = result.indexOfFirst { it.sameAs(m) }
                    result = if (index >= 0) {
                        val merged = combine(result[index], m)
                        if (merged == result[index]) result else result.toMutableList().also { it[index] = merged }
                    } else {
                        // Keep the list in time order; equal times keep arrival order.
                        val at = result.indexOfLast { it.timestamp <= m.timestamp } + 1
                        result.toMutableList().also { it.add(at, m) }
                    }
                }
                result
            }
        }
    }

    private suspend fun loadNewest(peer: BareJid) {
        awaitOnline()
        if (!im.history.available.value) return
        historyMutex.withLock {
            if (cursors.containsKey(peer)) return
            val page = im.history.fetch(with = peer, max = PAGE)
            importPage(page, unread = false)
            cursors[peer] = Cursor(page.first, page.complete)
        }
    }

    /**
     * After a new login: fetches what the archive has since the newest message
     * we know, so messages sent and read on other devices show here too. On a
     * device that knows none, fills the chat list with the newest messages.
     */
    private suspend fun catchUp() {
        awaitOnline()
        if (!im.history.available.value) return
        val newest = threads.value.values.flatten().filter { it.serverId != null }.maxByOrNull { it.timestamp }?.serverId
        if (newest == null) {
            importPage(im.history.fetch(max = PAGE), unread = false)
            return
        }
        var after: String = newest
        repeat(CATCH_UP_PAGES) {
            val page = try {
                im.history.fetch(after = after, max = PAGE)
            } catch (e: XmppStanzaException) {
                // The archive no longer has our newest message: start from its newest page.
                importPage(im.history.fetch(max = PAGE), unread = false)
                return
            }
            importPage(page, unread = true)
            after = page.last ?: return
            if (page.complete) return
        }
    }

    internal fun importPage(page: HistoryPage, unread: Boolean) {
        merge(page.messages.mapNotNull { it.toMessage(unread) }) { old, new -> old.mergedWith(new) }
        for (marker in page.markers) {
            when {
                // The contact read or got ours.
                !marker.outgoing && marker.displayed -> if (features.readMarkers) markUpTo(marker.peer, marker.id, outgoing = true)
                !marker.outgoing -> setStatus(marker.id, MessageStatus.DELIVERED, peer = marker.peer)
                // We read theirs on some device.
                marker.displayed -> markUpTo(marker.peer, marker.id, outgoing = false)
            }
        }
    }

    private suspend fun awaitOnline() {
        client.state.first { it is ConnectionState.Online }
    }

    private fun ChatMessage.toMessage(unread: Boolean): Message? {
        val text = body ?: return null
        val outgoing = direction == Direction.OUTGOING
        return Message(
            id = id ?: serverId ?: newId(),
            peer = peer,
            outgoing = outgoing,
            body = text,
            timestamp = delayedAt?.let(::parseStamp) ?: now(),
            status = when {
                outgoing -> MessageStatus.SENT
                unread -> MessageStatus.UNREAD
                else -> MessageStatus.READ
            },
            serverId = serverId,
            thread = thread,
            markable = markable,
            fromOtherDevice = outgoing && carbon,
            payloads = payloads,
        )
    }

    /** Starts the archive catch-up on every new login (not on a resumed session, which missed nothing). */
    private inner class SessionWatcher : ClientExtension {
        private var first = true

        override suspend fun onSessionStarted(session: SessionContext) {
            val firstInProcess = first
            first = false
            if (!features.history || (session.resumed && !firstInProcess)) return
            scope.launch { runCatching { catchUp() } }
        }
    }

    internal companion object {
        const val PAGE = 50
        const val CATCH_UP_PAGES = 10
        const val TYPING_PAUSE_MILLIS = 5_000L
        const val TYPING_TIMEOUT_MILLIS = 30_000L

        fun displayName(jid: BareJid, name: String?): String = name?.ifBlank { null } ?: jid.local ?: jid.toString()

        fun now(): Long = Clock.System.now().toEpochMilliseconds()

        /** XEP-0082 date-time, such as `2026-10-19T21:37:16Z` or with an offset or fraction. */
        fun parseStamp(stamp: String): Long? = runCatching { Instant.parse(stamp).toEpochMilliseconds() }.getOrNull()

        private fun rank(status: MessageStatus): Int = when (status) {
            MessageStatus.SENDING, MessageStatus.UNREAD -> 0
            MessageStatus.FAILED -> 1
            MessageStatus.SENT -> 1
            MessageStatus.DELIVERED -> 2
            MessageStatus.READ -> 3
        }

        /** Status only moves forward. A failure only replaces "not there yet"; a later receipt beats a failure. */
        fun Message.raisedTo(next: MessageStatus): Message {
            val moved = when {
                next == status -> status
                next == MessageStatus.FAILED -> if (status == MessageStatus.SENDING || status == MessageStatus.SENT) next else status
                status == MessageStatus.FAILED -> if (next == MessageStatus.DELIVERED || next == MessageStatus.READ) next else status
                next == MessageStatus.UNREAD -> status
                rank(next) > rank(status) -> next
                else -> status
            }
            return if (moved == status) this else copy(status = moved, error = if (moved == MessageStatus.FAILED) error else null)
        }

        fun Message.sameAs(other: Message): Boolean =
            peer == other.peer && (
                (serverId != null && serverId == other.serverId) ||
                    (id == other.id && outgoing == other.outgoing)
                )

        /**
         * The same message seen again (a carbon, an archive copy, a re-send):
         * keep what we knew, add what is new. Only a read marker, never a copy,
         * makes one of theirs read: otherwise we would not tell them we read it.
         */
        fun Message.mergedWith(other: Message): Message = (if (outgoing) raisedTo(other.status) else this).copy(
            serverId = serverId ?: other.serverId,
            markable = markable || other.markable,
            thread = thread ?: other.thread,
            payloads = payloads.ifEmpty { other.payloads },
        )
    }
}
