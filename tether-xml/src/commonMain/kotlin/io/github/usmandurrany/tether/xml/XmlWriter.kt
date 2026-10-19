package io.github.usmandurrany.tether.xml

/**
 * Serialises [Element] trees to XML text.
 *
 * Output is always well-formed: names are validated, text and attribute values
 * are escaped, and characters XML cannot carry (control characters, lone
 * surrogates) are rejected with [IllegalArgumentException] instead of being sent.
 * A namespace is declared only where it differs from the inherited default.
 */
public object XmlWriter {

    /**
     * Serialises [element]. [inheritedNamespace] is the default namespace already in
     * scope on the wire: pass `jabber:client` for stanzas inside a client stream so
     * no redundant `xmlns` is written.
     */
    public fun toXml(element: Element, inheritedNamespace: String = ""): String =
        buildString { write(element, this, inheritedNamespace) }

    public fun write(element: Element, out: Appendable, inheritedNamespace: String = "") {
        writeElement(element, out, inheritedNamespace)
    }

    private fun writeElement(element: Element, out: Appendable, inherited: String) {
        require(isNcName(element.name)) { "Invalid element name '${element.name}'" }
        out.append('<').append(element.name)
        if (element.namespace != inherited) {
            out.append(" xmlns='")
            escapeAttribute(element.namespace, out)
            out.append('\'')
        }
        var extraPrefixes: MutableMap<String, String>? = null
        for (attribute in element.attributes) {
            require(isNcName(attribute.name)) { "Invalid attribute name '${attribute.name}'" }
            out.append(' ')
            when (attribute.namespace) {
                "" -> Unit
                Namespaces.XML -> out.append("xml:")
                else -> {
                    val prefixes = extraPrefixes ?: LinkedHashMap<String, String>().also { extraPrefixes = it }
                    val prefix = prefixes.getOrPut(attribute.namespace) { "ns${prefixes.size}" }
                    out.append(prefix).append(':')
                }
            }
            out.append(attribute.name).append("='")
            escapeAttribute(attribute.value, out)
            out.append('\'')
        }
        extraPrefixes?.forEach { (namespace, prefix) ->
            out.append(" xmlns:").append(prefix).append("='")
            escapeAttribute(namespace, out)
            out.append('\'')
        }
        if (element.children.isEmpty()) {
            out.append("/>")
            return
        }
        out.append('>')
        for (child in element.children) {
            when (child) {
                is Element -> writeElement(child, out, element.namespace)
                is Text -> escapeText(child.value, out)
            }
        }
        out.append("</").append(element.name).append('>')
    }

    /** Escapes character data. `>` is escaped too so `]]>` can never appear. */
    public fun escapeText(value: String, out: Appendable) {
        forEachCodePoint(value) { cp ->
            when (cp) {
                '&'.code -> out.append("&amp;")
                '<'.code -> out.append("&lt;")
                '>'.code -> out.append("&gt;")
                0xD -> out.append("&#13;") // would otherwise be normalised to LF by the receiver
                else -> appendCodePoint(out, cp)
            }
        }
    }

    /** Escapes an attribute value for single or double quotes, preserving whitespace exactly. */
    public fun escapeAttribute(value: String, out: Appendable) {
        forEachCodePoint(value) { cp ->
            when (cp) {
                '&'.code -> out.append("&amp;")
                '<'.code -> out.append("&lt;")
                '>'.code -> out.append("&gt;")
                '\''.code -> out.append("&apos;")
                '"'.code -> out.append("&quot;")
                0x9 -> out.append("&#9;")
                0xA -> out.append("&#10;")
                0xD -> out.append("&#13;")
                else -> appendCodePoint(out, cp)
            }
        }
    }

    private inline fun forEachCodePoint(value: String, action: (Int) -> Unit) {
        var i = 0
        while (i < value.length) {
            val cp = codePointAt(value, i)
            require(cp != null && isXmlChar(cp)) { "Character at index $i cannot be represented in XML" }
            action(cp)
            i += if (cp >= 0x10000) 2 else 1
        }
    }

    private fun appendCodePoint(out: Appendable, cp: Int) {
        if (cp < 0x10000) {
            out.append(cp.toChar())
        } else {
            val v = cp - 0x10000
            out.append((0xD800 + (v ushr 10)).toChar())
            out.append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }
}
