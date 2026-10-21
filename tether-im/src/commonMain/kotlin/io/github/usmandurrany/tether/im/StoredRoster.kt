package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.KeyValueStore
import io.github.usmandurrany.tether.xml.XmlParse
import io.github.usmandurrany.tether.xml.XmlWriter
import io.github.usmandurrany.tether.xml.element

/** A [RosterStore] in a [KeyValueStore], one entry per account, in the roster's own XML form. */
public class StoredRoster(private val store: KeyValueStore, account: BareJid) : RosterStore {
    private val key = "tether.roster.$account"

    override suspend fun load(): RosterSnapshot? {
        val text = store.get(key) ?: return null
        return runCatching {
            val query = XmlParse.element(text)
            RosterSnapshot(
                version = query.attribute("ver")!!,
                items = query.children("item", ImNs.ROSTER).mapNotNull { item ->
                    val jid = bareOf(item.attribute("jid")) ?: return@mapNotNull null
                    RosterItem(
                        jid = jid,
                        name = item.attribute("name"),
                        subscription = Subscription.parse(item.attribute("subscription")),
                        pendingOut = item.attribute("ask") == "subscribe",
                        groups = item.children("group", ImNs.ROSTER).map { it.text }.toSet(),
                    )
                },
            )
        }.getOrNull() // unreadable: fetch the whole roster again
    }

    /** Forgets the saved roster, for example on sign-out. */
    public suspend fun clear() {
        store.put(key, null)
    }

    internal suspend fun clearIfPresent() {
        if (store.get(key) != null) store.put(key, null)
    }

    override suspend fun save(snapshot: RosterSnapshot) {
        val query = element("query", ImNs.ROSTER) {
            attribute("ver", snapshot.version)
            snapshot.items.forEach { item ->
                element("item") {
                    attribute("jid", item.jid.toString())
                    attribute("name", item.name)
                    attribute("subscription", item.subscription.wire)
                    if (item.pendingOut) attribute("ask", "subscribe")
                    item.groups.forEach { g -> element("group") { text(g) } }
                }
            }
        }
        store.put(key, XmlWriter.toXml(query))
    }
}
