package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

internal object ImNs {
    const val CLIENT = "jabber:client"
    const val ROSTER = "jabber:iq:roster"
    const val ROSTER_VER = "urn:xmpp:features:rosterver"
    const val CARBONS = "urn:xmpp:carbons:2"
    const val FORWARD = "urn:xmpp:forward:0"
    const val DELAY = "urn:xmpp:delay"
    const val RECEIPTS = "urn:xmpp:receipts"
    const val CHAT_STATES = "http://jabber.org/protocol/chatstates"
    const val MARKERS = "urn:xmpp:chat-markers:0"
    const val SID = "urn:xmpp:sid:0"
    const val HINTS = "urn:xmpp:hints"
    const val MAM = "urn:xmpp:mam:2"
    const val RSM = "http://jabber.org/protocol/rsm"
    const val DATA_FORMS = "jabber:x:data"
    const val PUSH = "urn:xmpp:push:0"
    const val COMMANDS = "http://jabber.org/protocol/commands"
    const val STANZAS = "urn:ietf:params:xml:ns:xmpp-stanzas"
    const val RETRACT = "urn:xmpp:message-retract:1"
    const val FALLBACK = "urn:xmpp:fallback:0"
    const val LAST = "jabber:iq:last"
    const val VCARD = "vcard-temp"
    const val VCARD_UPDATE = "vcard-temp:x:update"
}

/** The bare part of an address from the wire, or null when it is missing or malformed. */
internal fun bareOf(address: String?): BareJid? =
    address?.let { runCatching { BareJid.parse(it) }.getOrNull() }

/** Resource part, or null for a bare address. */
internal fun resourceOf(address: String?): String? =
    address?.substringAfter('/', "")?.ifEmpty { null }

internal fun newId(): String = Random.nextLong().toULong().toString(36) + Random.nextLong().toULong().toString(36)

internal fun iq(type: String, to: String? = null, id: String = newId(), block: io.github.usmandurrany.tether.xml.ElementBuilder.() -> Unit = {}): Element =
    element("iq", ImNs.CLIENT) {
        attribute("type", type)
        attribute("id", id)
        attribute("to", to)
        block()
    }

/** The defined condition of an error stanza (RFC 6120 §8.3), such as "item-not-found". */
internal fun errorCondition(stanza: Element): String? =
    stanza.child("error", ImNs.CLIENT)?.elements?.firstOrNull { it.namespace == ImNs.STANZAS }?.name

/** An IQ that came back as an error. */
public class XmppStanzaException(public val condition: String?, public val stanza: Element) :
    Exception("Request failed: ${condition ?: "unknown error"}")

internal fun Element.requireResult(): Element {
    if (attribute("type") != "result") throw XmppStanzaException(errorCondition(this), this)
    return this
}

/** True when an IQ result or push really comes from our own account (absent `from` means the same). */
internal fun fromOwnAccount(stanza: Element, own: BareJid?): Boolean {
    val from = stanza.attribute("from") ?: return true
    return own != null && from == own.toString()
}

/**
 * A map changed one entry at a time and read as a [StateFlow] of snapshots.
 * With a [scope], snapshots are published by a coroutine of their own, so a
 * burst of changes (thousands of presences at login) is copied a few times
 * instead of once per change. Without one, every change publishes at once.
 */
internal class BatchedMap<K, V>(private val scope: CoroutineScope?) {
    private val live = HashMap<K, V>()
    private val lock = Mutex()
    private val _flow = MutableStateFlow<Map<K, V>>(emptyMap())
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    val flow: StateFlow<Map<K, V>> = _flow.asStateFlow()

    init {
        scope?.launch { for (signal in dirty) publish() }
    }

    /** Changes the map; [change] returns whether anything changed. */
    suspend fun update(change: (MutableMap<K, V>) -> Boolean) {
        val changed = lock.withLock { change(live) }
        if (!changed) return
        if (scope == null) publish() else dirty.trySend(Unit)
    }

    private suspend fun publish() {
        _flow.value = lock.withLock { HashMap(live) }
    }
}
