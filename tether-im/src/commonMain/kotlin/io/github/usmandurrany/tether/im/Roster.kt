package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.ClientExtension
import io.github.usmandurrany.tether.core.SessionContext
import io.github.usmandurrany.tether.core.StanzaSender
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/** RFC 6121 §2.1.2.5. */
public enum class Subscription(public val wire: String) {
    NONE("none"), TO("to"), FROM("from"), BOTH("both");

    internal companion object {
        fun parse(value: String?): Subscription = entries.firstOrNull { it.wire == value } ?: NONE
    }
}

public data class RosterItem(
    val jid: BareJid,
    val name: String? = null,
    val subscription: Subscription = Subscription.NONE,
    /** We asked to see their presence and they have not answered yet. */
    val pendingOut: Boolean = false,
    val groups: Set<String> = emptySet(),
)

/** A saved copy of the roster, for roster versioning (RFC 6121 §2.6). */
public data class RosterSnapshot(val version: String, val items: List<RosterItem>)

/** Where the roster is kept between runs, so a reconnect only fetches what changed. */
public interface RosterStore {
    public suspend fun load(): RosterSnapshot?
    public suspend fun save(snapshot: RosterSnapshot)
}

/**
 * The contact list (RFC 6121 §2). Fetched on every new session before presence
 * goes out (as §2.2 recommends), kept current from the server's pushes, and
 * versioned when the server supports it and a [store] is given.
 *
 * Pushes are only accepted from our own account (§2.1.6); anything else is
 * refused, so another user cannot rewrite the list.
 */
public class Roster(
    private val client: StanzaSender,
    private val store: RosterStore? = null,
) : ClientExtension {
    private val _items = MutableStateFlow<Map<BareJid, RosterItem>>(emptyMap())
    public val items: StateFlow<Map<BareJid, RosterItem>> = _items.asStateFlow()

    /** Serialises the initial fetch with pushes that arrive during it. */
    private val mutex = Mutex()

    @Volatile
    private var own: BareJid? = null
    private var version: String? = null
    private var loaded = false

    override suspend fun onSessionStarted(session: SessionContext) {
        own = session.jid.bare
        if (session.resumed) return // The server kept the session; pushes kept the list current.
        if (!loaded) {
            store?.load()?.let { snapshot ->
                version = snapshot.version
                _items.value = snapshot.items.associateBy { it.jid }
            }
            loaded = true
        }
        val versioning = store != null && session.capabilities.hasStreamFeature("ver", ImNs.ROSTER_VER)
        val answer = session.request(
            iq("get") {
                element("query", ImNs.ROSTER) {
                    if (versioning) attribute("ver", version.orEmpty())
                }
            },
        )
        if (answer.attribute("type") != "result" || !fromOwnAccount(answer, own)) return // No roster: carry on without one.
        // An empty result means "unchanged since your version"; pushes for changes follow.
        val query = answer.child("query", ImNs.ROSTER) ?: return
        mutex.withLock {
            _items.value = query.children("item", ImNs.ROSTER).mapNotNull(::parseItem)
                .filter { !it.removed }.associate { it.item.jid to it.item }
            if (versioning) query.attribute("ver")?.let { saveVersion(it) } else version = null
        }
    }

    override suspend fun onStanza(stanza: Element): Boolean {
        if (stanza.name != "iq" || stanza.attribute("type") != "set") return false
        val query = stanza.child("query", ImNs.ROSTER) ?: return false
        if (!fromOwnAccount(stanza, own)) {
            client.send(errorReply(stanza, "service-unavailable"))
            return true
        }
        // §2.1.6: a push carries exactly one item.
        val item = query.children("item", ImNs.ROSTER).singleOrNull()?.let(::parseItem)
        if (item == null) {
            client.send(errorReply(stanza, "bad-request"))
            return true
        }
        mutex.withLock {
            _items.update { if (item.removed) it - item.item.jid else it + (item.item.jid to item.item) }
            query.attribute("ver")?.let { saveVersion(it) }
        }
        client.send(iq("result", to = stanza.attribute("from"), id = stanza.attribute("id") ?: newId()))
        return true
    }

    /** Adds or updates a contact. The change arrives through a push and then shows in [items]. */
    public suspend fun set(jid: BareJid, name: String? = null, groups: Set<String> = emptySet()) {
        client.request(
            iq("set") {
                element("query", ImNs.ROSTER) {
                    element("item") {
                        attribute("jid", jid.toString())
                        attribute("name", name)
                        groups.forEach { g -> element("group") { text(g) } }
                    }
                }
            },
        ).requireResult()
    }

    /** Removes a contact; the server also cancels the presence subscriptions both ways. */
    public suspend fun remove(jid: BareJid) {
        client.request(
            iq("set") {
                element("query", ImNs.ROSTER) {
                    element("item") {
                        attribute("jid", jid.toString())
                        attribute("subscription", "remove")
                    }
                }
            },
        ).requireResult()
    }

    private suspend fun saveVersion(newVersion: String) {
        version = newVersion
        store?.save(RosterSnapshot(newVersion, _items.value.values.toList()))
    }

    private class Parsed(val item: RosterItem, val removed: Boolean)

    private fun parseItem(item: Element): Parsed? {
        val jid = bareOf(item.attribute("jid")) ?: return null
        val subscription = item.attribute("subscription")
        val parsed = RosterItem(
            jid = jid,
            name = item.attribute("name"),
            subscription = Subscription.parse(subscription),
            pendingOut = item.attribute("ask") == "subscribe",
            groups = item.children("group", ImNs.ROSTER).map { it.text }.filter { it.isNotEmpty() }.toSet(),
        )
        return Parsed(parsed, removed = subscription == "remove")
    }
}

internal fun errorReply(request: Element, condition: String): Element =
    element("iq", ImNs.CLIENT) {
        attribute("type", "error")
        attribute("id", request.attribute("id"))
        attribute("to", request.attribute("from"))
        element("error") {
            attribute("type", if (condition == "bad-request") "modify" else "cancel")
            element(condition, ImNs.STANZAS)
        }
    }
