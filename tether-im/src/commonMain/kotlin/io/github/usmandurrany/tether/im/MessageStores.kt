package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.Text
import io.github.usmandurrany.tether.xml.XmlParse
import io.github.usmandurrany.tether.xml.XmlWriter
import io.github.usmandurrany.tether.xml.element

/**
 * Turns a [Message] into one string and back, every field included (status,
 * deleted and hidden flags, custom data). The built-in stores use it; a store
 * of your own can keep a message as this one text column plus whatever
 * columns it wants to query.
 */
public object MessageCodec {
    private const val NS = "urn:tether:store:message:0"

    /** The whole message as one string. */
    public fun encode(message: Message): String = XmlWriter.toXml(
        element("m", NS) {
            attribute("id", message.id)
            attribute("peer", message.peer.toString())
            attribute("out", message.outgoing.toString())
            attribute("ts", message.timestamp.toString())
            attribute("status", message.status.name)
            attribute("sid", message.serverId)
            attribute("error", message.error)
            attribute("thread", message.thread)
            if (message.markable) attribute("markable", "true")
            if (message.fromOtherDevice) attribute("other", "true")
            if (message.deleted) attribute("deleted", "true")
            if (message.hidden) attribute("hidden", "true")
            element("body") { text(message.body) }
            if (message.payloads.isNotEmpty()) element("payloads") { message.payloads.forEach(::child) }
        },
    )

    /** The message [encode] wrote, or null for a string it cannot read. */
    public fun decode(text: String): Message? = runCatching {
        val e = XmlParse.element(text)
        if (e.name != "m" || e.namespace != NS) return null
        Message(
            id = e.attribute("id")!!,
            peer = BareJid.parse(e.attribute("peer")!!),
            outgoing = e.attribute("out") == "true",
            body = e.child("body")?.let { b -> buildString { b.children.forEach { if (it is Text) append(it.value) } } }.orEmpty(),
            timestamp = e.attribute("ts")!!.toLong(),
            status = MessageStatus.valueOf(e.attribute("status")!!),
            serverId = e.attribute("sid"),
            error = e.attribute("error"),
            thread = e.attribute("thread"),
            markable = e.attribute("markable") == "true",
            fromOtherDevice = e.attribute("other") == "true",
            deleted = e.attribute("deleted") == "true",
            hidden = e.attribute("hidden") == "true",
            payloads = e.child("payloads")?.elements.orEmpty(),
        )
    }.getOrNull()

    /** The key a store replaces rows by: peer, direction and id together. */
    public fun keyOf(message: Message): String = "${message.peer}|${if (message.outgoing) "out" else "in"}|${message.id}"
}

/**
 * Checks that a [MessageStore] of your own behaves the way [TetherChat]
 * needs, so ticks, read state, deletions and custom data survive restarts.
 * Run it once from a test against an empty store:
 *
 * ```
 * @Test fun roomStoreWorks() = runTest { MessageStoreCheck.run(RoomMessageStore(inMemoryDb)) }
 * ```
 *
 * Throws [IllegalStateException] saying what is wrong. Leaves the store empty.
 */
public object MessageStoreCheck {
    public suspend fun run(store: MessageStore) {
        store.clear()
        check(store.load().isEmpty()) { "clear() left messages behind" }
        val bob = BareJid.parse("bob@example.com")
        val carol = BareJid.parse("carol@example.com")
        val photo = MessageData("photo", "url" to "https://example.com/a.jpg?x=1&y=2", "size" to 48213).toElement()
        val ours = Message("m1", bob, outgoing = true, body = "hi <there> & \"you\"\nsecond line", timestamp = 1_000, status = MessageStatus.SENDING, payloads = listOf(photo))
        val theirs = Message("m1", bob, outgoing = false, body = "same id, other direction", timestamp = 2_000, status = MessageStatus.UNREAD, markable = true)
        val other = Message("c1", carol, outgoing = false, body = "carol", timestamp = 3_000, status = MessageStatus.UNREAD, serverId = "arch-1", thread = "t")

        store.save(listOf(ours, theirs, other))
        expect(store, listOf(ours, theirs, other), "after the first save")

        // A status change replaces the row with the same peer, direction and id.
        val delivered = ours.copy(status = MessageStatus.DELIVERED, serverId = "arch-2")
        val deleted = other.copy(deleted = true, body = "", status = MessageStatus.READ)
        store.save(listOf(delivered, deleted))
        expect(store, listOf(delivered, theirs, deleted), "after saving changed messages (replace by peer, outgoing and id)")

        val hidden = theirs.copy(hidden = true, status = MessageStatus.READ)
        store.save(listOf(hidden))
        expect(store, listOf(delivered, hidden, deleted), "after hiding a message (hidden must be kept, not dropped)")

        store.delete(listOf(delivered))
        expect(store, listOf(hidden, deleted), "after delete()")

        store.clear()
        check(store.load().isEmpty()) { "clear() left messages behind" }
    }

    private suspend fun expect(store: MessageStore, wanted: List<Message>, what: String) {
        val loaded = store.load().sortedBy(MessageCodec::keyOf)
        val sorted = wanted.sortedBy(MessageCodec::keyOf)
        check(loaded.size == sorted.size) { "Wrong number of messages $what: ${loaded.size}, expected ${sorted.size}" }
        for ((got, want) in loaded.zip(sorted)) {
            check(got == want) { "Message ${want.id} came back changed $what.\n  saved:  $want\n  loaded: $got" }
        }
    }
}

