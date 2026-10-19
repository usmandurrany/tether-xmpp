package io.github.usmandurrany.tether.xml

/** Parses standalone XML, for example an element saved with [XmlWriter.toXml]. */
public object XmlParse {
    /**
     * Parses one element. Unprefixed names without an `xmlns` get [defaultNamespace].
     * Uses the stream parser, so the same XMPP restrictions apply (no DTDs, no
     * comments, no external entities). Throws [XmlStreamException] on bad input.
     */
    public fun element(xml: String, defaultNamespace: String = "", limits: XmlLimits = XmlLimits()): Element {
        var result: Element? = null
        val parser = XmppStreamParser(
            object : XmppStreamHandler {
                override fun onStreamOpen(header: StreamHeader) {}
                override fun onElement(element: Element) {
                    if (result == null) result = element
                }
                override fun onStreamClose() {}
            },
            limits,
        )
        val ns = if (defaultNamespace.isEmpty()) "" else " xmlns='${defaultNamespace.replace("'", "&apos;")}'"
        val doc = "<stream:stream xmlns:stream='http://etherx.jabber.org/streams'$ns>$xml</stream:stream>"
        val bytes = doc.encodeToByteArray()
        parser.feed(bytes, 0, bytes.size)
        return result ?: throw IllegalArgumentException("No element in input")
    }
}
