package io.github.usmandurrany.tether.im

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PushTest {
    private val ejabberd = PushTarget("push.example.com", "device-42", mapOf("secret" to "s3cr3t"))
    private val mongoose = PushTarget("pubsub.example.com", "phone-node", mapOf("device_id" to "fcm-token", "service" to "fcm"), createNode = true)

    @Test
    fun enablesWithPublishOptionsOnANewSession() = runTest {
        val sender = FakeSender()
        val push = Push(sender)
        push.register(ejabberd)
        assertIs<PushState.Pending>(push.state.value)
        push.onSessionStarted(FakeSession(sender, accountFeatures = setOf(ImNs.PUSH)))
        val enable = assertNotNull(sender.requests.single().child("enable", ImNs.PUSH))
        assertEquals("push.example.com", enable.attribute("jid"))
        assertEquals("device-42", enable.attribute("node"))
        val fields = enable.child("x", ImNs.DATA_FORMS)!!.children("field", ImNs.DATA_FORMS)
            .associate { it.attribute("var") to it.child("value", ImNs.DATA_FORMS)?.text }
        assertEquals("http://jabber.org/protocol/pubsub#publish-options", fields["FORM_TYPE"])
        assertEquals("s3cr3t", fields["secret"])
        assertEquals(PushState.On(ejabberd), push.state.value)
    }

    @Test
    fun mongooseImNodesAreCreatedFirstAndAnExistingNodeIsFine() = runTest {
        val sender = FakeSender { iq ->
            if (iq.child("pubsub", "http://jabber.org/protocol/pubsub") != null) FakeSender.error(iq, "conflict") else FakeSender.result(iq)
        }
        val push = Push(sender)
        push.register(mongoose)
        push.onSessionStarted(FakeSession(sender, serverFeatures = setOf(ImNs.PUSH)))
        val create = sender.requests[0].child("pubsub", "http://jabber.org/protocol/pubsub")!!.child("create", "http://jabber.org/protocol/pubsub")!!
        assertEquals("push", create.attribute("type"))
        assertEquals("pubsub.example.com", sender.requests[0].attribute("to"))
        assertNotNull(sender.requests[1].child("enable", ImNs.PUSH))
        assertEquals(PushState.On(mongoose), push.state.value)
    }

    @Test
    fun nothingIsSentWhenTheServerHasNoPush() = runTest {
        val sender = FakeSender()
        val push = Push(sender)
        push.register(ejabberd)
        push.onSessionStarted(FakeSession(sender))
        assertTrue(sender.requests.isEmpty())
        assertEquals(PushState.Unsupported, push.state.value)
    }

    @Test
    fun registeringWhileOnlineEnablesAtOnceAndRefusalsAreReported() = runTest {
        val sender = FakeSender { FakeSender.error(it, "not-acceptable") }
        val push = Push(sender)
        push.onSessionStarted(FakeSession(sender, accountFeatures = setOf(ImNs.PUSH)))
        push.register(ejabberd)
        assertEquals(PushState.Failed(ejabberd, "not-acceptable"), push.state.value)
    }

    @Test
    fun unregisterDisablesTheNode() = runTest {
        val sender = FakeSender()
        val push = Push(sender)
        push.register(ejabberd)
        push.onSessionStarted(FakeSession(sender, accountFeatures = setOf(ImNs.PUSH)))
        push.unregister()
        val disable = assertNotNull(sender.requests.last().child("disable", ImNs.PUSH))
        assertEquals("device-42", disable.attribute("node"))
        assertEquals(PushState.Off, push.state.value)
    }
}
