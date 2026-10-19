package io.github.usmandurrany.tether.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {
    private fun logged(xml: String, config: DebugConfig = DebugConfig()): String {
        val memory = MemorySink()
        TetherDebug(config, listOf(memory)).trafficLogger().sent(xml)
        return memory.entries.value.single().text
    }

    @Test
    fun saslAndSasl2Payloads() {
        assertEquals(
            "<auth xmlns='urn:ietf:params:xml:ns:xmpp-sasl' mechanism='PLAIN'>[redacted]</auth>",
            logged("<auth xmlns='urn:ietf:params:xml:ns:xmpp-sasl' mechanism='PLAIN'>AGFsaWNlAHNlY3JldA==</auth>"),
        )
        val sasl2 = logged(
            "<authenticate xmlns='urn:xmpp:sasl:2' mechanism='SCRAM-SHA-256'>" +
                "<initial-response>biwsbj1hbGljZQ==</initial-response><user-agent id='x'><software>App</software></user-agent></authenticate>",
        )
        assertFalse("biwsbj1hbGljZQ" in sasl2)
        assertTrue("<software>App</software>" in sasl2)
    }

    @Test
    fun fastTokens() {
        val text = logged("<success xmlns='urn:xmpp:sasl:2'><token xmlns='urn:xmpp:fast:0' expiry='2027-01-01T00:00:00Z' token='s3cr3t'/></success>")
        assertFalse("s3cr3t" in text)
        assertTrue("token='[redacted]'" in text)
        assertTrue("expiry='2027-01-01T00:00:00Z'" in text)
    }

    @Test
    fun registrationPasswords() {
        val text = logged(
            "<iq type='set' id='p1'><query xmlns='jabber:iq:register'><username>alice</username><password>hunter2</password></query></iq>",
        )
        assertEquals(
            "<iq type='set' id='p1'><query xmlns='jabber:iq:register'><username>alice</username><password>[redacted]</password></query></iq>",
            text,
        )
    }

    @Test
    fun pushSecretsInDataForms() {
        val text = logged(
            "<iq type='set' id='e1'><enable xmlns='urn:xmpp:push:0' jid='push.example.com' node='n1'>" +
                "<x xmlns='jabber:x:data' type='submit'>" +
                "<field var='FORM_TYPE'><value>http://jabber.org/protocol/pubsub#publish-options</value></field>" +
                "<field var='secret'><value>abc123</value></field>" +
                "</x></enable></iq>",
        )
        assertFalse("abc123" in text)
        assertTrue("publish-options" in text)
    }

    @Test
    fun uploadSlotAuthHeaders() {
        val text = logged(
            "<iq type='result' id='u1'><slot xmlns='urn:xmpp:http:upload:0'>" +
                "<put url='https://up.example.com/f'><header name='Authorization'>Bearer xyz</header><header name='Expires'>60</header></put>" +
                "</slot></iq>",
        )
        assertFalse("Bearer xyz" in text)
        assertTrue("<header name='Expires'>60</header>" in text)
    }

    @Test
    fun bodiesOnlyWhenAsked() {
        val xml = "<message to='bob@example.com'><body>see you at 5</body></message>"
        assertTrue("see you at 5" in logged(xml))
        assertEquals("<message to='bob@example.com'><body>[body: 12 chars]</body></message>", logged(xml, DebugConfig(redactBodies = true)))
    }

    @Test
    fun bodiesInsideCarbonsAndArchives() {
        val text = logged(
            "<message><result xmlns='urn:xmpp:mam:2' id='1'><forwarded xmlns='urn:xmpp:forward:0'>" +
                "<message xmlns='jabber:client' from='bob@example.com'><body>private</body></message>" +
                "</forwarded></result></message>",
            DebugConfig(redactBodies = true),
        )
        assertFalse("private" in text)
    }

    @Test
    fun appRedactorRunsAfterBuiltIn() {
        val text = logged(
            "<message><token xmlns='urn:example:app'>t0k</token></message>",
            DebugConfig(redactor = { it.copy(children = emptyList()) }),
        )
        assertEquals("<message/>", text)
    }
}
