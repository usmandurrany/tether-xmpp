package io.github.usmandurrany.tether.debug

import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.StreamHeader
import io.github.usmandurrany.tether.xml.Text
import io.github.usmandurrany.tether.xml.XmlLimits
import io.github.usmandurrany.tether.xml.XmlWriter
import io.github.usmandurrany.tether.xml.XmppStreamHandler
import io.github.usmandurrany.tether.xml.XmppStreamParser

/** XML helpers for the debug log. Only used on logged text, never on the live stream. */
internal object XmlSupport {
    const val CLIENT = "jabber:client"

    /**
     * Parses one element as the core logs it: serialised inside a
     * `jabber:client` stream, so stanzas carry no `xmlns`. Returns null for
     * anything that is not exactly one element (stream headers, `</stream:stream>`).
     */
    fun parse(xml: String, limits: XmlLimits): Element? {
        var result: Element? = null
        var count = 0
        val parser = XmppStreamParser(
            object : XmppStreamHandler {
                override fun onStreamOpen(header: StreamHeader) {}
                override fun onElement(element: Element) {
                    count++
                    result = element
                }
                override fun onStreamClose() {}
            },
            limits,
        )
        val doc = "<stream:stream xmlns='$CLIENT' xmlns:stream='http://etherx.jabber.org/streams'>$xml</stream:stream>"
        return try {
            val bytes = doc.encodeToByteArray()
            parser.feed(bytes, 0, bytes.size)
            if (count == 1) result else null
        } catch (e: Exception) {
            null
        }
    }

    fun write(element: Element): String = XmlWriter.toXml(element, CLIENT)

    /** Shortens text nodes longer than [max] (avatars, file previews) so one stanza cannot flood the log. */
    fun truncateText(element: Element, max: Int): Element {
        var changed = false
        val children = element.children.map { child ->
            when (child) {
                is Element -> truncateText(child, max).also { if (it !== child) changed = true }
                is Text -> if (child.value.length > max) {
                    changed = true
                    // Never cut a surrogate pair in half: the writer would reject the lone half.
                    val cut = if (child.value[max - 1].isHighSurrogate()) max - 1 else max
                    Text(child.value.take(cut) + "…[${child.value.length - cut} more chars]")
                } else {
                    child
                }
            }
        }
        return if (changed) element.copy(children = children) else element
    }

    fun summarize(element: Element): StanzaSummary = StanzaSummary(
        name = element.name,
        type = element.attribute("type"),
        id = element.attribute("id"),
        from = element.attribute("from"),
        to = element.attribute("to"),
        payload = element.elements.firstOrNull()?.namespace?.takeIf { it != element.namespace },
    )

    /**
     * Indented XML for reading. Elements that hold only elements get one child
     * per line; anything with text stays on one line so whitespace in message
     * bodies is shown as sent.
     */
    fun pretty(element: Element, indent: String = "  "): String = write(indented(element, indent, 0))

    private fun indented(element: Element, indent: String, depth: Int): Element {
        if (element.children.isEmpty() || element.children.any { it is Text }) return element
        val inner = "\n" + indent.repeat(depth + 1)
        val children = buildList {
            element.elements.forEach {
                add(Text(inner))
                add(indented(it, indent, depth + 1))
            }
            add(Text("\n" + indent.repeat(depth)))
        }
        return element.copy(children = children)
    }
}
