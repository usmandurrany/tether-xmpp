package io.github.usmandurrany.tether.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TetherDebugTest {
    private val memory = MemorySink()
    private fun debug(config: DebugConfig = DebugConfig()) = TetherDebug(config, listOf(memory), clock = { 1_000 })

    @Test
    fun logsStanzasBothWaysWithSummary() {
        val logger = debug().trafficLogger("alice@example.com/phone")
        logger.sent("<message to='bob@example.com' type='chat' id='m1'><body>hi</body></message>")
        logger.received("<iq type='result' id='r1' from='example.com'><query xmlns='jabber:iq:roster'/></iq>")

        val (sent, received) = memory.entries.value
        assertEquals(Direction.SENT, sent.direction)
        assertEquals("<message to='bob@example.com' type='chat' id='m1'><body>hi</body></message>", sent.text)
        assertEquals(StanzaSummary("message", "chat", "m1", null, "bob@example.com"), sent.stanza)
        assertEquals("alice@example.com/phone", sent.account)
        assertEquals(Direction.RECEIVED, received.direction)
        assertEquals("jabber:iq:roster", received.stanza?.payload)
        assertEquals(listOf(1L, 2L), memory.entries.value.map { it.seq })
    }

    @Test
    fun learnsTheAccountFromTheStream() {
        val logger = debug().trafficLogger()
        logger.sent("<?xml version='1.0'?><stream:stream xmlns='jabber:client' to='example.com' from='alice@example.com' version='1.0'>")
        logger.received(
            "<iq type='result' id='b1'><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'><jid>alice@example.com/tether-1</jid></bind></iq>",
        )
        logger.sent("<presence/>")

        assertEquals(
            listOf("alice@example.com", "alice@example.com/tether-1", "alice@example.com/tether-1"),
            memory.entries.value.map { it.account },
        )
    }

    @Test
    fun unparseableTextIsNeverLoggedRaw() {
        debug().trafficLogger().sent("<message><body>secret")
        assertEquals("[unparsed XML, 21 chars]", memory.entries.value.single().text)
    }

    @Test
    fun nonzasCanBeHidden() {
        val logger = debug(DebugConfig(nonzas = false)).trafficLogger()
        logger.sent("<r xmlns='urn:xmpp:sm:3'/>")
        logger.received("<a xmlns='urn:xmpp:sm:3' h='1'/>")
        logger.sent("<presence/>")
        assertEquals(listOf("presence"), memory.entries.value.map { it.stanza?.name })
    }

    @Test
    fun switchesOffAndFiltersByLevel() {
        val debug = debug(DebugConfig(minLevel = LogLevel.INFO))
        debug.log(LogLevel.DEBUG, "app", "dropped")
        debug.log(LogLevel.WARN, "app", "kept")
        debug.trafficLogger().sent("<presence/>") // stanzas are DEBUG
        debug.enabled = false
        debug.log(LogLevel.ERROR, "app", "off")
        assertEquals(listOf("kept"), memory.entries.value.map { it.text })
    }

    @Test
    fun brokenSinkDoesNotStopOthers() {
        val debug = TetherDebug(sinks = listOf(LogSink { error("boom") }, memory))
        debug.log(LogLevel.INFO, "app", "still here")
        assertEquals(1, memory.entries.value.size)
    }

    @Test
    fun failingAppRedactorFailsClosed() {
        val logger = debug(DebugConfig(redactor = { error("bug") })).trafficLogger()
        logger.sent("<message id='m1' to='bob@example.com'><body>hi</body></message>")
        assertEquals("<message id='m1'/>", memory.entries.value.single().text)
    }

    @Test
    fun longTextIsTruncated() {
        val logger = debug(DebugConfig(maxTextChars = 16)).trafficLogger()
        logger.received("<message><body>${"a".repeat(15)}😀tail</body></message>")
        val text = memory.entries.value.single().text
        assertEquals("<message><body>${"a".repeat(15)}…[6 more chars]</body></message>", text)
    }

    @Test
    fun memorySinkKeepsTheNewest() {
        val sink = MemorySink(capacity = 2)
        val debug = TetherDebug(sinks = listOf(sink))
        repeat(3) { debug.log(LogLevel.INFO, "app", "e$it") }
        assertEquals(listOf("e1", "e2"), sink.entries.value.map { it.text })
    }

    @Test
    fun formatsLines() {
        val logger = debug().trafficLogger("alice@example.com")
        logger.sent("<iq type='get' id='1'><query xmlns='jabber:iq:roster'/></iq>")
        val entry = memory.entries.value.single()
        assertEquals(
            "1970-01-01T00:00:01Z D stanza alice@example.com → <iq type='get' id='1'><query xmlns='jabber:iq:roster'/></iq>",
            LogFormatter().format(entry),
        )
        assertEquals(
            "1970-01-01T00:00:01Z D stanza alice@example.com → \n<iq type='get' id='1'>\n  <query xmlns='jabber:iq:roster'/>\n</iq>",
            LogFormatter(pretty = true).format(entry),
        )
        assertTrue(entry.isStanza)
        assertFalse(LogEntry(1, 0, LogLevel.INFO, "app", null, "x").isStanza)
        assertNull(LogEntry(1, 0, LogLevel.INFO, "app", null, "x").stanza)
    }
}
