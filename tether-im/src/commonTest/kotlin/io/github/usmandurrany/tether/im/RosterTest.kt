package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RosterTest {
    private val romeo = BareJid.parse("romeo@example.net")

    private fun rosterResult(ver: String? = null, vararg items: String) = { iq: io.github.usmandurrany.tether.xml.Element ->
        xml("<iq type='result' id='${iq.attribute("id")}'><query xmlns='jabber:iq:roster'${ver?.let { " ver='$it'" } ?: ""}>${items.joinToString("")}</query></iq>")
    }

    private class MemoryStore(var snapshot: RosterSnapshot? = null) : RosterStore {
        override suspend fun load() = snapshot
        override suspend fun save(snapshot: RosterSnapshot) { this.snapshot = snapshot }
    }

    @Test
    fun fetchesTheRosterOnANewSession() = runTest {
        val sender = FakeSender(rosterResult(null, "<item jid='romeo@example.net' name='Romeo' subscription='both'><group>Friends</group></item>", "<item jid='nurse@example.com' subscription='none' ask='subscribe'/>"))
        val roster = Roster(sender)
        roster.onSessionStarted(FakeSession(sender))
        assertEquals(RosterItem(romeo, "Romeo", Subscription.BOTH, false, setOf("Friends")), roster.items.value[romeo])
        assertTrue(roster.items.value[BareJid.parse("nurse@example.com")]!!.pendingOut)
        assertNull(sender.requests.single().child("query", ImNs.ROSTER)!!.attribute("ver"), "no versioning without a store")
    }

    @Test
    fun aResumedSessionKeepsTheListWithoutFetching() = runTest {
        val sender = FakeSender()
        Roster(sender).onSessionStarted(FakeSession(sender, resumed = true))
        assertTrue(sender.requests.isEmpty())
    }

    @Test
    fun pushesFromTheAccountUpdateTheListAndAreAnswered() = runTest {
        val sender = FakeSender(rosterResult())
        val roster = Roster(sender)
        roster.onSessionStarted(FakeSession(sender))
        assertTrue(roster.onStanza(xml("<iq type='set' id='push1'><query xmlns='jabber:iq:roster'><item jid='romeo@example.net' subscription='to'/></query></iq>")))
        assertEquals(Subscription.TO, roster.items.value[romeo]?.subscription)
        assertEquals("result", sender.sent.last().attribute("type"))
        assertEquals("push1", sender.sent.last().attribute("id"))

        roster.onStanza(xml("<iq type='set' id='push2' from='juliet@example.com'><query xmlns='jabber:iq:roster'><item jid='romeo@example.net' subscription='remove'/></query></iq>"))
        assertNull(roster.items.value[romeo])
    }

    @Test
    fun pushesFromAnyoneElseAreRefused() = runTest {
        val sender = FakeSender(rosterResult())
        val roster = Roster(sender)
        roster.onSessionStarted(FakeSession(sender))
        roster.onStanza(xml("<iq type='set' id='evil' from='mallory@evil.example'><query xmlns='jabber:iq:roster'><item jid='mallory@evil.example' subscription='both'/></query></iq>"))
        assertTrue(roster.items.value.isEmpty())
        assertEquals("error", sender.sent.last().attribute("type"))
    }

    @Test
    fun versioningAsksOnlyForChangesAndKeepsTheStoredList() = runTest {
        val store = MemoryStore(RosterSnapshot("v1", listOf(RosterItem(romeo, "Romeo", Subscription.BOTH))))
        val features = element("features", "http://etherx.jabber.org/streams") { element("ver", ImNs.ROSTER_VER) }
        // An empty result: nothing changed since v1.
        val sender = FakeSender { iq -> FakeSender.result(iq) }
        val roster = Roster(sender, store)
        roster.onSessionStarted(FakeSession(sender, streamFeatures = features))
        assertEquals("v1", sender.requests.single().child("query", ImNs.ROSTER)!!.attribute("ver"))
        assertEquals("Romeo", roster.items.value[romeo]?.name)

        roster.onStanza(xml("<iq type='set' id='p'><query xmlns='jabber:iq:roster' ver='v2'><item jid='tybalt@example.org' subscription='none'/></query></iq>"))
        assertEquals("v2", store.snapshot!!.version)
        assertEquals(2, store.snapshot!!.items.size)
    }

    @Test
    fun aServerWithoutARosterDoesNotStopTheSession() = runTest {
        val sender = FakeSender { FakeSender.error(it) }
        Roster(sender).onSessionStarted(FakeSession(sender))
    }
}

class StoredRosterTest {
    @Test
    fun roundTrips() = kotlinx.coroutines.test.runTest {
        val store = StoredRoster(io.github.usmandurrany.tether.core.MemoryKeyValueStore(), BareJid.parse("juliet@example.com"))
        assertNull(store.load())
        val snapshot = RosterSnapshot("v9", listOf(RosterItem(BareJid.parse("romeo@example.net"), "Romeo & co", Subscription.BOTH, true, setOf("A", "B"))))
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }
}
