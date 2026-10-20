package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.XmlWriter
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PayloadsTest {
    data class Picture(val url: String, val size: Long, val width: Int? = null, val tags: List<String> = emptyList(), val thumb: Thumb? = null)
    data class Thumb(val url: String, val width: Int)

    private val pictureType = payloadType<Picture>(
        name = "picture",
        namespace = "urn:example:picture:0",
        write = {
            put("url", it.url)
            put("size", it.size)
            put("width", it.width)
            putAll("tag", it.tags)
            it.thumb?.let { t -> put("thumb") { put("url", t.url); put("width", t.width) } }
        },
        read = {
            Picture(
                url = string("url") ?: return@payloadType null,
                size = long("size") ?: 0,
                width = int("width"),
                tags = strings("tag"),
                thumb = child("thumb")?.let { t -> Thumb(t.string("url") ?: return@let null, t.int("width") ?: 0) },
            )
        },
    )

    private val romeo = BareJid.parse("romeo@example.net")

    @Test
    fun fieldsBecomeChildElementsAndReadBack() {
        val picture = Picture("https://img.example/1.jpg", 48213, width = 1024, tags = listOf("beach", "sunset"), thumb = Thumb("https://img.example/1s.jpg", 128))
        val element = pictureType.write(picture)
        assertEquals(
            "<picture xmlns='urn:example:picture:0'><url>https://img.example/1.jpg</url><size>48213</size><width>1024</width>" +
                "<tag>beach</tag><tag>sunset</tag><thumb><url>https://img.example/1s.jpg</url><width>128</width></thumb></picture>",
            XmlWriter.toXml(element),
        )
        assertEquals(picture, pictureType.read(xml(XmlWriter.toXml(element))))
    }

    @Test
    fun nullFieldsAreLeftOut() {
        val element = pictureType.write(Picture("u", 1))
        assertNull(element.child("width"))
        assertNull(pictureType.read(element)!!.width)
    }

    @Test
    fun malformedPayloadsReadAsNull() {
        // Missing the required url.
        assertNull(pictureType.read(xml("<picture xmlns='urn:example:picture:0'><size>1</size></picture>")))
        // Another type with the same name.
        assertNull(pictureType.read(xml("<picture xmlns='urn:other:0'><url>u</url></picture>")))
        // A number that is not one reads as missing.
        assertEquals(0, pictureType.read(xml("<picture xmlns='urn:example:picture:0'><url>u</url><size>big</size></picture>"))!!.size)
    }

    @Test
    fun aTypeThatThrowsDoesNotBreakTheMessage() {
        val broken = object : PayloadType<String> {
            override val name = "x"
            override val namespace = "urn:example:x"
            override fun write(value: String): Element = element(name, namespace)
            override fun read(element: Element): String = error("bad")
        }
        val stanza = xml("<message from='romeo@example.net/o' type='chat'><body>hi</body><x xmlns='urn:example:x'/></message>")
        val chat = chatMessageOf(stanza, romeo, outgoing = false, carbon = false, delay = null, serverId = null, own = null)!!
        assertNull(chat.payload(broken))
    }

    @Test
    fun onlyTheAppsOwnElementsArePayloads() {
        val stanza = xml(
            "<message from='romeo@example.net/o' type='chat' id='m1'><body>Sunset</body>" +
                "<request xmlns='urn:xmpp:receipts'/><markable xmlns='urn:xmpp:chat-markers:0'/><active xmlns='http://jabber.org/protocol/chatstates'/>" +
                "<origin-id xmlns='urn:xmpp:sid:0' id='m1'/><thread>t</thread>" +
                "<picture xmlns='urn:example:picture:0'><url>https://img.example/1.jpg</url><size>5</size></picture>" +
                "<x xmlns='jabber:x:oob'><url>https://img.example/1.jpg</url></x></message>",
        )
        val chat = chatMessageOf(stanza, romeo, outgoing = false, carbon = false, delay = null, serverId = null, own = null)!!
        assertEquals(listOf("picture", "x"), chat.payloads.map { it.name })
        assertEquals("https://img.example/1.jpg", chat.payload(pictureType)?.url)
    }

    @Test
    fun reservedNamespacesAndBadNamesAreRefused() {
        assertFailsWith<IllegalArgumentException> { payloadType<String>("receipt", ImNs.RECEIPTS, { put("a", it) }, { string("a") }) }
        assertFailsWith<IllegalArgumentException> { payloadType<String>("body", ImNs.CLIENT, { put("a", it) }, { string("a") }) }
        assertFailsWith<IllegalArgumentException> { payloadType<String>("1st", "urn:example:a", { put("a", it) }, { string("a") }) }
        val bad = payloadType<String>("ok", "urn:example:a", { put("has space", it) }, { string("a") })
        assertFailsWith<IllegalArgumentException> { bad.write("v") }
    }

    @Test
    fun messagingSendsPayloadsInTheSameStanza() = runTest {
        val sender = FakeSender()
        val messaging = Messaging(sender).also { it.onSessionStarted(FakeSession(sender)) }
        messaging.send(romeo, "Sunset", payloads = listOf(pictureType.write(Picture("https://img.example/1.jpg", 5))))
        val m = sender.sent.single()
        assertEquals("Sunset", m.child("body")?.text)
        assertEquals("https://img.example/1.jpg", m.child("picture", "urn:example:picture:0")?.child("url")?.text)
        assertNull(m.child("store", ImNs.HINTS))

        assertFailsWith<IllegalArgumentException> {
            messaging.send(romeo, "x", payloads = listOf(element("received", ImNs.RECEIPTS)))
        }
    }

    @Test
    fun dataWithoutABodyAsksTheServerToKeepIt() = runTest {
        val sender = FakeSender()
        val messaging = Messaging(sender).also { it.onSessionStarted(FakeSession(sender)) }
        messaging.send(romeo, "", payloads = listOf(pictureType.write(Picture("u", 1))))
        val m = sender.sent.single()
        assertNotNull(m.child("body"))
        assertNotNull(m.child("store", ImNs.HINTS))
    }

    @Test
    fun payloadsSurviveAStoreRoundTrip() {
        val payloads = listOf(pictureType.write(Picture("https://img.example/1.jpg?a=1&b=2", 5)), element("x", "jabber:x:oob") { element("url") { text("u") } })
        val saved = Payloads.toXml(payloads)
        assertEquals(payloads, Payloads.fromXml(saved))
        assertEquals("", Payloads.toXml(emptyList()))
        assertTrue(Payloads.fromXml("").isEmpty())
        assertTrue(Payloads.fromXml("<not xml").isEmpty())
    }

    @Test
    fun messageHelpersFindPayloadsByType() {
        val m = Message(
            id = "1", peer = romeo, outgoing = false, body = "", timestamp = 0, status = MessageStatus.UNREAD,
            payloads = listOf(pictureType.write(Picture("a", 1)), pictureType.write(Picture("b", 2))),
        )
        assertTrue(m.has(pictureType))
        assertEquals("a", m.payload(pictureType)?.url)
        assertEquals(listOf("a", "b"), m.payloads(pictureType).map { it.url })
        assertFalse(m.copy(payloads = emptyList()).has(pictureType))
    }
}
