package io.github.usmandurrany.tether.im

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun p2GivesANodeAndASecretInOneStep() = runTest {
        val sender = FakeSender { iq ->
            if (iq.child("command", ImNs.COMMANDS) != null) {
                FakeSender.result(iq) {
                    element("command", ImNs.COMMANDS) {
                        attribute("node", "register-push-fcm")
                        attribute("status", "completed")
                        form("jid" to "p2.example.net", "node" to "n-1", "secret" to "s-1")
                    }
                }
            } else {
                FakeSender.result(iq)
            }
        }
        val push = Push(sender)
        push.onSessionStarted(FakeSession(sender, accountFeatures = setOf(ImNs.PUSH)))
        val target = push.register(PushAppServer.p2("p2.example.net", "fcm-token", "install-7"))
        assertEquals(PushTarget("p2.example.net", "n-1", mapOf("secret" to "s-1")), target)

        val command = sender.requests[0].child("command", ImNs.COMMANDS)!!
        assertEquals("p2.example.net", sender.requests[0].attribute("to"))
        assertEquals("register-push-fcm", command.attribute("node"))
        assertEquals("execute", command.attribute("action"))
        assertEquals(mapOf("token" to "fcm-token", "android-id" to "install-7"), fields(command))
        assertEquals("n-1", sender.requests[1].child("enable", ImNs.PUSH)!!.attribute("node"))
        assertEquals(PushState.On(target), push.state.value)
    }

    @Test
    fun aServerThatAnswersWithABlankFormGetsItSubmitted() = runTest {
        val sender = FakeSender { iq ->
            val command = iq.child("command", ImNs.COMMANDS)
            when (command?.attribute("action")) {
                "execute" -> FakeSender.result(iq) {
                    element("command", ImNs.COMMANDS) {
                        attribute("node", "register-device")
                        attribute("sessionid", "sess-9")
                        attribute("status", "executing")
                    }
                }
                "complete" -> FakeSender.result(iq) {
                    element("command", ImNs.COMMANDS) {
                        attribute("status", "completed")
                        form("node" to "tigase-node")
                    }
                }
                else -> FakeSender.result(iq)
            }
        }
        val push = Push(sender)
        push.onSessionStarted(FakeSession(sender, accountFeatures = setOf(ImNs.PUSH)))
        val target = push.register(PushAppServer.tigase("push.example.net", "tok"))
        assertEquals(PushTarget("push.example.net", "tigase-node"), target)
        val second = sender.requests[1].child("command", ImNs.COMMANDS)!!
        assertEquals("sess-9", second.attribute("sessionid"))
        assertEquals(mapOf("provider" to "fcm-xmpp-api", "device-token" to "tok"), fields(second))
    }

    @Test
    fun aRefusedCommandThrowsAndRegistersNothing() = runTest {
        val sender = FakeSender { FakeSender.error(it, "forbidden") }
        val push = Push(sender)
        push.onSessionStarted(FakeSession(sender, accountFeatures = setOf(ImNs.PUSH)))
        val error = assertFailsWith<PushRegistrationException> { push.register(PushAppServer.p2("p2.example.net", "t", "d")) }
        assertEquals("forbidden", error.condition)
        assertEquals(1, sender.requests.size)
        assertEquals(PushState.Off, push.state.value)
    }

    private fun fields(command: io.github.usmandurrany.tether.xml.Element): Map<String, String> =
        command.child("x", ImNs.DATA_FORMS)!!.children("field", ImNs.DATA_FORMS)
            .associate { it.attribute("var")!! to it.child("value", ImNs.DATA_FORMS)!!.text }

    private fun io.github.usmandurrany.tether.xml.ElementBuilder.form(vararg values: Pair<String, String>) {
        element("x", ImNs.DATA_FORMS) {
            attribute("type", "result")
            values.forEach { (name, value) ->
                element("field") {
                    attribute("var", name)
                    element("value") { text(value) }
                }
            }
        }
    }
}
