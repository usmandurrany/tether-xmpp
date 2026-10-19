package io.github.usmandurrany.tether.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class XmppStreamParserTest {

    private val sample = HEADER +
        "<message to='juliet@example.com' type='chat' id='m1'><body>Wherefore art thou?</body></message>" +
        "   \n " + // whitespace keepalive between stanzas
        "<iq type='get' id='p1'><ping xmlns='urn:xmpp:ping'/></iq>" +
        "</stream:stream>"

    @Test
    fun parsesHeaderStanzasAndClose() {
        val c = parse(sample)
        val header = c.headers.single()
        assertEquals("example.com", header.from)
        assertEquals("abc", header.id)
        assertEquals("1.0", header.version)
        assertEquals("en", header.lang)
        assertEquals("jabber:client", header.defaultNamespace)

        assertEquals(2, c.elements.size)
        val message = c.elements[0]
        assertEquals("message", message.name)
        assertEquals("jabber:client", message.namespace)
        assertEquals("chat", message.attribute("type"))
        assertEquals("Wherefore art thou?", message.child("body")?.text)

        val ping = c.elements[1].child("ping", "urn:xmpp:ping")
        assertEquals("urn:xmpp:ping", ping?.namespace)
        assertEquals(1, c.closed)
    }

    @Test
    fun resultIsIndependentOfHowBytesAreSplit() {
        val expected = parse(sample).elements
        for (chunk in 1..17) {
            assertEquals(expected, parse(sample, chunkSize = chunk).elements, "chunk size $chunk")
        }
    }

    @Test
    fun decodesMultiByteUtf8SplitAcrossChunks() {
        val text = "héllo ✓ 😀 日本"
        val c = parse(HEADER + "<message><body>$text</body></message>", chunkSize = 1)
        assertEquals(text, c.elements.single().child("body")?.text)
    }

    @Test
    fun decodesPredefinedEntitiesAndCharacterReferences() {
        val body = parseStanza("<message><body>&lt;a&gt; &amp; &quot;q&quot; &apos;s&apos; &#65;&#x42;&#x1F600;</body></message>")
            .child("body")!!.text
        assertEquals("<a> & \"q\" 's' AB😀", body)
        val attr = parseStanza("<message id='a&amp;b&#x20;c'/>").attribute("id")
        assertEquals("a&b c", attr)
    }

    @Test
    fun handlesCdataAsText() {
        val body = parseStanza("<message><body>x<![CDATA[<not> & a ]] tag]]>y</body></message>").child("body")!!.text
        assertEquals("x<not> & a ]] tagy", body)
    }

    @Test
    fun normalisesLineEndingsAndAttributeWhitespace() {
        val e = parseStanza("<message id='a\tb\nc'><body>1\r\n2\r3</body></message>")
        assertEquals("a b c", e.attribute("id"))
        assertEquals("1\n2\n3", e.child("body")!!.text)
        // Character references are not normalised.
        assertEquals("a\r\nb", parseStanza("<message><body>a&#13;&#10;b</body></message>").child("body")!!.text)
    }

    @Test
    fun resolvesNamespacesAndPrefixes() {
        val e = parseStanza(
            "<message xmlns:x='urn:x'><x:data x:kind='k' plain='p'><inner/></x:data>" +
                "<other xmlns='urn:other'><deep/></other><body xml:lang='de'>Hallo</body></message>",
        )
        val data = e.child("data", "urn:x")!!
        assertEquals("k", data.attribute("kind", "urn:x"))
        assertEquals("p", data.attribute("plain"))
        assertNull(data.attribute("kind"))
        // Unprefixed children inherit the default namespace, not the parent's prefix.
        assertEquals("jabber:client", data.elements.single().namespace)
        assertEquals("urn:other", e.child("other", "urn:other")!!.elements.single().namespace)
        assertEquals("de", e.child("body")!!.attribute("lang", Namespaces.XML))
    }

    @Test
    fun streamPrefixedNonzasResolve() {
        val features = parseStanza("<stream:features><starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'><required/></starttls></stream:features>")
        assertEquals(Namespaces.STREAMS, features.namespace)
        assertEquals("features", features.name)
        assertEquals("urn:ietf:params:xml:ns:xmpp-tls", features.elements.single().namespace)
    }

    @Test
    fun acceptsStreamWithoutXmlDeclarationAndWithBom() {
        val noDecl = HEADER.removePrefix("<?xml version='1.0'?>")
        assertEquals(1, parse("$noDecl<a/>").elements.size)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + (HEADER + "<a/>").encodeToByteArray()
        assertEquals(1, parse(withBom).elements.size)
    }

    @Test
    fun resetAllowsAStreamRestart() {
        val c = Collector()
        val parser = XmppStreamParser(c)
        parser.feed((HEADER + "<proceed xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>").encodeToByteArray())
        parser.reset() // e.g. after STARTTLS
        parser.feed((HEADER + "<a/>").encodeToByteArray())
        assertEquals(2, c.headers.size)
        assertEquals(listOf("proceed", "a"), c.elements.map { it.name })
    }

    @Test
    fun refusesInputAfterAFailureUntilReset() {
        val parser = XmppStreamParser(Collector())
        assertFailsWith<XmlStreamException> { parser.feed("<!-- x -->".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { parser.feed(HEADER.encodeToByteArray()) }
        parser.reset()
        parser.feed(HEADER.encodeToByteArray())
        assertEquals(1, parser.depth)
    }

    // Restricted XML (RFC 6120 §11.1)

    @Test
    fun rejectsComments() = assertStreamError(XmlErrorCondition.RESTRICTED_XML, "$HEADER<message><!-- hi --></message>")

    @Test
    fun rejectsCommentsBetweenStanzas() = assertStreamError(XmlErrorCondition.RESTRICTED_XML, "$HEADER<!-- hi -->")

    @Test
    fun rejectsDoctype() = assertStreamError(
        XmlErrorCondition.RESTRICTED_XML,
        "<?xml version='1.0'?><!DOCTYPE lolz [<!ENTITY lol 'lol'>]><stream:stream/>",
    )

    @Test
    fun rejectsProcessingInstructions() {
        assertStreamError(XmlErrorCondition.RESTRICTED_XML, "$HEADER<message><?php x ?></message>")
        assertStreamError(XmlErrorCondition.RESTRICTED_XML, "<?php x ?>$HEADER")
        // An XML declaration is only allowed as the very first thing in the stream.
        assertStreamError(XmlErrorCondition.RESTRICTED_XML, " <?xml version='1.0'?><stream:stream/>")
    }

    @Test
    fun rejectsCustomEntities() = assertStreamError(XmlErrorCondition.RESTRICTED_XML, "$HEADER<message><body>&lol;</body></message>")

    @Test
    fun rejectsNonUtf8Declaration() = assertStreamError(
        XmlErrorCondition.UNSUPPORTED_ENCODING,
        "<?xml version='1.0' encoding='ISO-8859-1'?><stream:stream/>",
    )

    // UTF-8 validation

    @Test
    fun rejectsInvalidUtf8() {
        val prefix = (HEADER + "<message><body>").encodeToByteArray()
        val bad = listOf(
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()), // overlong '/'
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0xAF.toByte()), // overlong
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), // surrogate U+D800
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), // above U+10FFFF
            byteArrayOf(0xC3.toByte(), 0x41), // bad continuation
            byteArrayOf(0xFF.toByte()), // invalid lead byte
        )
        for (seq in bad) assertStreamError(XmlErrorCondition.NOT_WELL_FORMED, prefix + seq)
    }

    @Test
    fun rejectsControlCharacters() {
        assertStreamError(XmlErrorCondition.NOT_WELL_FORMED, "$HEADER<message><body>\u0001</body></message>")
        assertStreamError(XmlErrorCondition.NOT_WELL_FORMED, "$HEADER<message><body>&#0;</body></message>")
        assertStreamError(XmlErrorCondition.NOT_WELL_FORMED, "$HEADER<message><body>&#xD800;</body></message>")
    }

    // Well-formedness

    @Test
    fun rejectsMalformedMarkup() {
        val cases = listOf(
            "$HEADER<message></iq>",
            "$HEADER<message id='1' id='2'/>",
            "$HEADER<message id='1'type='chat'/>",
            "$HEADER<message id=1/>",
            "$HEADER<message id='a<b'/>",
            "$HEADER<x:message/>",
            "$HEADER<message><body>a]]>b</body></message>",
            "$HEADER<message xmlns:a='urn:1' xmlns:b='urn:1' a:k='1' b:k='2'/>",
            "$HEADER<message xmlns:p=''/>",
            "$HEADER<message><body>&#xZZ;</body></message>",
            "$HEADER</stream:stream>junk",
            "hello$HEADER",
        )
        for (input in cases) assertStreamError(XmlErrorCondition.NOT_WELL_FORMED, input)
    }

    @Test
    fun rejectsWrongRoot() {
        assertStreamError(XmlErrorCondition.INVALID_NAMESPACE, "<stream:stream xmlns:stream='urn:wrong'>")
        assertStreamError(XmlErrorCondition.INVALID_NAMESPACE, "<stream xmlns='jabber:client'>")
        assertStreamError(XmlErrorCondition.BAD_FORMAT, "<stream:session xmlns:stream='http://etherx.jabber.org/streams'>")
        assertStreamError(XmlErrorCondition.BAD_FORMAT, "<stream:stream xmlns:stream='http://etherx.jabber.org/streams'/>")
    }

    @Test
    fun rejectsTextBetweenStanzas() {
        assertStreamError(XmlErrorCondition.BAD_FORMAT, "${HEADER}hello")
        assertStreamError(XmlErrorCondition.BAD_FORMAT, "$HEADER&amp;")
        assertStreamError(XmlErrorCondition.BAD_FORMAT, "$HEADER<![CDATA[x]]>")
    }

    // Limits

    @Test
    fun enforcesStanzaSizeLimit() {
        val limits = XmlLimits(maxStanzaBytes = 100)
        parse("$HEADER<message><body>${"a".repeat(60)}</body></message>", limits = limits)
        assertStreamError(XmlErrorCondition.POLICY_VIOLATION, "$HEADER<message><body>${"a".repeat(100)}</body></message>", limits)
        // The limit is per stanza, not per stream.
        val many = (1..20).joinToString("") { "<message><body>${"a".repeat(60)}</body></message>" }
        assertEquals(20, parse(HEADER + many, limits = limits).elements.size)
    }

    @Test
    fun whitespaceBetweenStanzasDoesNotCountTowardsTheLimit() {
        val limits = XmlLimits(maxStanzaBytes = 50)
        assertEquals(1, parse(HEADER + " ".repeat(10_000) + "<a/>", limits = limits).elements.size)
    }

    @Test
    fun enforcesHeaderSizeLimit() =
        assertStreamError(XmlErrorCondition.POLICY_VIOLATION, " ".repeat(5000) + HEADER)

    @Test
    fun enforcesDepthLimit() {
        val limits = XmlLimits(maxDepth = 5)
        parse("$HEADER<a><b><c><d/></c></b></a>", limits = limits)
        assertStreamError(XmlErrorCondition.POLICY_VIOLATION, "$HEADER<a><b><c><d><e/></d></c></b></a>", limits)
    }

    @Test
    fun enforcesAttributeAndNameLimits() {
        val limits = XmlLimits(maxAttributes = 3, maxNameLength = 8, maxNamespaceDeclarations = 1)
        assertStreamError(XmlErrorCondition.POLICY_VIOLATION, "$HEADER<a b='1' c='2' d='3' e='4'/>", limits)
        assertStreamError(XmlErrorCondition.POLICY_VIOLATION, "$HEADER<abcdefghij/>", limits)
        assertStreamError(XmlErrorCondition.POLICY_VIOLATION, "$HEADER<a xmlns:p='urn:p' xmlns:q='urn:q'/>", limits)
    }

    @Test
    fun handlerExceptionsAlsoStopTheParser() {
        val parser = XmppStreamParser(object : XmppStreamHandler {
            override fun onStreamOpen(header: StreamHeader) = Unit
            override fun onElement(element: Element) = error("boom")
            override fun onStreamClose() = Unit
        })
        assertFailsWith<IllegalStateException> { parser.feed("$HEADER<a/>".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { parser.feed("<a/>".encodeToByteArray()) }
    }
}
