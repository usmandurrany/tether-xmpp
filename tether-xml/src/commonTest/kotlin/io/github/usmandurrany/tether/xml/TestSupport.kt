package io.github.usmandurrany.tether.xml

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal const val HEADER =
    "<?xml version='1.0'?><stream:stream xmlns='jabber:client' " +
        "xmlns:stream='http://etherx.jabber.org/streams' from='example.com' id='abc' " +
        "version='1.0' xml:lang='en'>"

internal class Collector : XmppStreamHandler {
    val headers = ArrayList<StreamHeader>()
    val elements = ArrayList<Element>()
    var closed = 0

    override fun onStreamOpen(header: StreamHeader) {
        headers += header
    }

    override fun onElement(element: Element) {
        elements += element
    }

    override fun onStreamClose() {
        closed++
    }
}

/** Parses [input], feeding it in chunks of [chunkSize] bytes. */
internal fun parse(input: ByteArray, chunkSize: Int = input.size.coerceAtLeast(1), limits: XmlLimits = XmlLimits()): Collector {
    val collector = Collector()
    val parser = XmppStreamParser(collector, limits)
    var offset = 0
    while (offset < input.size) {
        val n = minOf(chunkSize, input.size - offset)
        parser.feed(input, offset, n)
        offset += n
    }
    return collector
}

internal fun parse(input: String, chunkSize: Int = Int.MAX_VALUE, limits: XmlLimits = XmlLimits()): Collector =
    parse(input.encodeToByteArray(), chunkSize.coerceAtMost(input.length.coerceAtLeast(1) * 4), limits)

/** Parses a single stanza inside a standard client stream header. */
internal fun parseStanza(stanza: String): Element = parse(HEADER + stanza).elements.single()

internal fun assertStreamError(condition: XmlErrorCondition, input: String, limits: XmlLimits = XmlLimits()) {
    val e = assertFailsWith<XmlStreamException>("expected ${condition.wireName} for: $input") { parse(input, limits = limits) }
    assertEquals(condition, e.condition, "wrong condition for: $input (${e.message})")
}

internal fun assertStreamError(condition: XmlErrorCondition, input: ByteArray) {
    val e = assertFailsWith<XmlStreamException> { parse(input) }
    assertEquals(condition, e.condition, e.message)
}
