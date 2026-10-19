package io.github.usmandurrany.tether.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XmlWriterTest {

    private val message = element("message", "jabber:client") {
        attribute("to", "juliet@example.com")
        attribute("id", "a'b\"c<d>&e\tf\ng\rh")
        attribute("type", null) // skipped
        attribute("lang", "en", Namespaces.XML)
        element("body") { text("1 < 2 & 3 > 2 ]]> \r\n done 😀") }
        element("x", "urn:custom") {
            attribute("k", "v", "urn:attr-ns")
            element("y")
        }
        element("plain", "")
    }

    @Test
    fun omitsInheritedNamespace() {
        val xml = XmlWriter.toXml(element("presence", "jabber:client"), inheritedNamespace = "jabber:client")
        assertEquals("<presence/>", xml)
        assertEquals("<presence xmlns='jabber:client'/>", XmlWriter.toXml(element("presence", "jabber:client")))
    }

    @Test
    fun escapesTextAndAttributes() {
        val xml = XmlWriter.toXml(element("a", "") { attribute("v", "'\"<&"); text("<&>") })
        assertEquals("<a v='&apos;&quot;&lt;&amp;'>&lt;&amp;&gt;</a>", xml)
    }

    @Test
    fun roundTripsThroughTheParser() {
        val wire = XmlWriter.toXml(message, inheritedNamespace = "jabber:client")
        assertEquals(message, parseStanza(wire))
    }

    @Test
    fun roundTripsWhenSplitIntoSingleBytes() {
        val wire = HEADER + XmlWriter.toXml(message, inheritedNamespace = "jabber:client")
        assertEquals(message, parse(wire, chunkSize = 1).elements.single())
    }

    @Test
    fun rejectsContentXmlCannotCarry() {
        assertFailsWith<IllegalArgumentException> { XmlWriter.toXml(element("a", "") { text("\u0000") }) }
        assertFailsWith<IllegalArgumentException> { XmlWriter.toXml(element("a", "") { text("\uD800") }) }
        assertFailsWith<IllegalArgumentException> { XmlWriter.toXml(element("a", "") { attribute("v", "\u0007") }) }
        assertFailsWith<IllegalArgumentException> { XmlWriter.toXml(element("bad name", "")) }
        assertFailsWith<IllegalArgumentException> { XmlWriter.toXml(element("a", "") { attribute("p:x", "1") }) }
    }

    @Test
    fun toStringNeverThrows() {
        val s = element("a", "") { text("\u0000") }.toString()
        assertEquals(true, s.startsWith("Element(name=a"))
    }
}

class XmlParseTest {
    @Test
    fun roundTripsThroughTheWriter() {
        val original = element("message", "jabber:client") {
            attribute("to", "romeo@example.net")
            element("body") { text("a < b & 'c'") }
            element("request", "urn:xmpp:receipts")
        }
        val xml = XmlWriter.toXml(original)
        kotlin.test.assertEquals(original, XmlParse.element(xml))
    }

    @Test
    fun defaultNamespaceApplies() {
        kotlin.test.assertEquals("jabber:client", XmlParse.element("<presence/>", "jabber:client").namespace)
    }
}
