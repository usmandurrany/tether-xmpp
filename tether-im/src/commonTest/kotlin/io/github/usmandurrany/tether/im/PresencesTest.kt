package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresencesTest {
    private val romeo = BareJid.parse("romeo@example.net")

    @Test
    fun announcesOnEveryNewSessionButNotOnResume() = runTest {
        val sender = FakeSender()
        val presences = Presences(sender, OwnPresence(Show.AWAY, "busy", priority = 5))
        presences.onSessionStarted(FakeSession(sender))
        val p = sender.sent.single()
        assertEquals("presence", p.name)
        assertEquals("away", p.child("show")?.text)
        assertEquals("5", p.child("priority")?.text)
        presences.onSessionStarted(FakeSession(sender, resumed = true))
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun tracksContactResources() = runTest {
        val sender = FakeSender()
        val presences = Presences(sender)
        presences.onSessionStarted(FakeSession(sender))
        presences.onStanza(xml("<presence from='romeo@example.net/orchard'><show>dnd</show></presence>"))
        presences.onStanza(xml("<presence from='romeo@example.net/phone'/>"))
        assertEquals(2, presences.contacts.value[romeo]?.size)
        presences.onStanza(xml("<presence from='romeo@example.net/orchard' type='unavailable'/>"))
        assertEquals(listOf("phone"), presences.contacts.value[romeo]?.map { it.resource })
        presences.onStanza(xml("<presence from='romeo@example.net/phone' type='unavailable'/>"))
        assertNull(presences.contacts.value[romeo])
    }

    @Test
    fun aNewSessionForgetsStalePresence() = runTest {
        val sender = FakeSender()
        val presences = Presences(sender)
        presences.onStanza(xml("<presence from='romeo@example.net/orchard'/>"))
        presences.onSessionStarted(FakeSession(sender))
        assertTrue(presences.contacts.value.isEmpty())
    }

    @Test
    fun reportsSubscriptionRequests() = runTest {
        val sender = FakeSender()
        val presences = Presences(sender)
        val event = async { presences.subscriptions.first() }
        yield()
        presences.onStanza(xml("<presence from='romeo@example.net' type='subscribe'><status>hi</status></presence>"))
        assertEquals(SubscriptionEvent.Requested(romeo, "hi"), event.await())
        presences.approve(romeo)
        assertEquals("subscribed", sender.sent.last().attribute("type"))
        assertEquals("romeo@example.net", sender.sent.last().attribute("to"))
    }

    @Test
    fun hiddenAccountsStayHiddenAcrossSessions() = runTest {
        val sender = FakeSender()
        val presences = Presences(sender, announceOnConnect = false)
        presences.onSessionStarted(FakeSession(sender))
        assertTrue(sender.sent.isEmpty())
    }
}
