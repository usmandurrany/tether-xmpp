package io.github.usmandurrany.tether.xml

/** The opening `<stream:stream>` tag, delivered once per stream (and again after each restart). */
public data class StreamHeader(
    public val attributes: List<Attribute>,
    /** Default namespace declared on the header, normally `jabber:client`. Empty if none. */
    public val defaultNamespace: String,
) {
    public fun attribute(name: String, namespace: String = ""): String? =
        attributes.firstOrNull { it.name == name && it.namespace == namespace }?.value

    public val from: String? get() = attribute("from")
    public val to: String? get() = attribute("to")
    public val id: String? get() = attribute("id")
    public val version: String? get() = attribute("version")
    public val lang: String? get() = attribute("lang", Namespaces.XML)
}

/** Receives parse results. Called synchronously from [XmppStreamParser.feed]. */
public interface XmppStreamHandler {
    public fun onStreamOpen(header: StreamHeader)

    /** A complete top-level element: a stanza (`<message/>`, `<iq/>`, ...) or a nonza (`<r/>`, `<features/>`, ...). */
    public fun onElement(element: Element)

    /** The peer sent `</stream:stream>`. */
    public fun onStreamClose()
}

/**
 * Push parser for an XMPP stream (RFC 6120 §4 and §11).
 *
 * Feed it raw bytes as they come off the socket, split at any position. It emits
 * the stream header, then each top-level element as a finished immutable tree.
 * Only the element currently being received is buffered, and only up to
 * [XmlLimits.maxStanzaBytes].
 *
 * It accepts only the XML subset XMPP allows. Comments, processing instructions
 * (other than an initial XML declaration), DTDs and entities other than the five
 * predefined ones fail the stream with [XmlErrorCondition.RESTRICTED_XML], so
 * XXE and entity-expansion attacks cannot happen.
 *
 * Not thread-safe: one parser belongs to one connection and is driven from one
 * coroutine. After any exception the parser refuses further input until [reset].
 */
public class XmppStreamParser(
    private val handler: XmppStreamHandler,
    private val limits: XmlLimits = XmlLimits(),
) {
    private enum class State {
        PROLOG, LT, PI, BANG, CDATA,
        START_NAME, IN_TAG, ATTR_NAME, AFTER_ATTR_NAME, BEFORE_ATTR_VALUE, ATTR_VALUE, EMPTY_TAG_SLASH,
        END_NAME, END_TAG_TRAILING,
        TEXT, ENTITY, AFTER_CLOSE,
    }

    private class OpenElement(
        val qname: String,
        val name: String,
        val namespace: String,
        val attributes: List<Attribute>,
        val declarations: Map<String, String>?,
    ) {
        val children = ArrayList<Node>(2)
    }

    private val decoder = Utf8Decoder()
    private val stack = ArrayList<OpenElement>()
    private var state = State.PROLOG
    private var failed = false

    // Position and size accounting.
    private var charIndex = 0L
    private var headerBytes = 0
    private var inStanza = false
    private var stanzaBytes = 0
    private var lastWasCr = false

    // Scratch buffers, reused across tags.
    private val nameBuf = StringBuilder()
    private val attrNameBuf = StringBuilder()
    private val valueBuf = StringBuilder()
    private val textBuf = StringBuilder()
    private val entityBuf = StringBuilder()
    private val markupBuf = StringBuilder()
    private val pendingAttrs = ArrayList<Pair<String, String>>()
    private var quote = 0
    private var needWhitespace = false
    private var entityInAttribute = false
    private var ltWasFirstChar = false
    private var bracketRun = 0

    /** Current nesting depth: 0 before the header, 1 between stanzas. */
    public val depth: Int get() = stack.size

    /** Parses [length] bytes of [bytes] starting at [offset]. */
    public fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        check(!failed) { "Parser failed earlier; call reset() before reusing it" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "Invalid range" }
        try {
            for (i in offset until offset + length) feedByte(bytes[i].toInt() and 0xFF)
        } catch (t: Throwable) {
            // Any failure, including one thrown by the handler, leaves state undefined.
            failed = true
            throw t
        }
    }

    /**
     * Forgets everything and expects a new stream header. Used for the stream
     * restarts after STARTTLS and SASL success (RFC 6120 §5.4.3.3, §6.4.6).
     */
    public fun reset() {
        decoder.reset()
        stack.clear()
        state = State.PROLOG
        failed = false
        charIndex = 0
        headerBytes = 0
        inStanza = false
        stanzaBytes = 0
        lastWasCr = false
        nameBuf.clear(); attrNameBuf.clear(); valueBuf.clear(); textBuf.clear()
        entityBuf.clear(); markupBuf.clear(); pendingAttrs.clear()
        quote = 0
        needWhitespace = false
        entityInAttribute = false
        ltWasFirstChar = false
        bracketRun = 0
    }

    private fun feedByte(b: Int) {
        accountByte()
        val cp = decoder.decode(b)
        if (cp < 0) return
        // A byte order mark is tolerated only as the very first character of a stream.
        if (cp == 0xFEFF && charIndex == 0L) return
        var c = cp
        // End-of-line normalisation (XML 1.0 §2.11): CRLF and lone CR become LF.
        if (c == 0xD) {
            lastWasCr = true
            c = 0xA
        } else if (c == 0xA && lastWasCr) {
            lastWasCr = false
            return
        } else {
            lastWasCr = false
        }
        if (!isXmlChar(c)) fail(XmlErrorCondition.NOT_WELL_FORMED, "Character U+${c.toString(16)} is not allowed in XML")
        process(c)
        charIndex++
    }

    private fun accountByte() {
        if (stack.isEmpty() && state != State.AFTER_CLOSE) {
            if (++headerBytes > limits.maxStreamHeaderBytes) {
                fail(XmlErrorCondition.POLICY_VIOLATION, "Stream header exceeds ${limits.maxStreamHeaderBytes} bytes")
            }
        } else if (inStanza) {
            if (++stanzaBytes > limits.maxStanzaBytes) {
                fail(XmlErrorCondition.POLICY_VIOLATION, "Element exceeds ${limits.maxStanzaBytes} bytes")
            }
        }
    }

    private fun process(c: Int) {
        when (state) {
            State.PROLOG -> when {
                isXmlWhitespace(c) -> Unit
                c == '<'.code -> {
                    ltWasFirstChar = charIndex == 0L
                    state = State.LT
                }
                else -> fail(XmlErrorCondition.NOT_WELL_FORMED, "Text before the stream header")
            }

            State.LT -> when {
                c == '/'.code -> {
                    if (stack.isEmpty()) fail(XmlErrorCondition.NOT_WELL_FORMED, "End tag before the stream header")
                    nameBuf.clear()
                    state = State.END_NAME
                }
                c == '!'.code -> {
                    markupBuf.clear()
                    state = State.BANG
                }
                c == '?'.code -> {
                    if (!(stack.isEmpty() && ltWasFirstChar)) {
                        fail(XmlErrorCondition.RESTRICTED_XML, "Processing instructions are not allowed")
                    }
                    markupBuf.clear()
                    state = State.PI
                }
                isNameStartChar(c) -> {
                    nameBuf.clear()
                    nameBuf.appendCodePoint(c)
                    pendingAttrs.clear()
                    needWhitespace = false
                    state = State.START_NAME
                }
                else -> fail(XmlErrorCondition.NOT_WELL_FORMED, "Invalid character after '<'")
            }

            State.PI -> {
                if (c == '>'.code && markupBuf.endsWith("?")) {
                    markupBuf.setLength(markupBuf.length - 1)
                    handleXmlDeclaration(markupBuf.toString())
                    state = State.PROLOG
                } else {
                    if (markupBuf.length >= MAX_XML_DECLARATION) {
                        fail(XmlErrorCondition.POLICY_VIOLATION, "XML declaration too long")
                    }
                    markupBuf.appendCodePoint(c)
                }
            }

            State.BANG -> {
                markupBuf.appendCodePoint(c)
                if (!CDATA_OPEN.startsWith(markupBuf)) {
                    val what = when {
                        markupBuf.startsWith("-") -> "Comments are"
                        markupBuf.startsWith("D") -> "DTDs are"
                        else -> "Markup declarations are"
                    }
                    fail(XmlErrorCondition.RESTRICTED_XML, "$what not allowed")
                }
                if (markupBuf.length == CDATA_OPEN.length) {
                    if (stack.size < 2) fail(XmlErrorCondition.BAD_FORMAT, "Character data between top-level elements")
                    bracketRun = 0
                    state = State.CDATA
                }
            }

            State.CDATA -> {
                if (c == '>'.code && bracketRun >= 2) {
                    textBuf.setLength(textBuf.length - 2) // drop the "]]" of "]]>"
                    bracketRun = 0
                    state = State.TEXT
                } else {
                    bracketRun = if (c == ']'.code) bracketRun + 1 else 0
                    textBuf.appendCodePoint(c)
                }
            }

            State.START_NAME -> when {
                isNameChar(c) -> appendName(nameBuf, c)
                isXmlWhitespace(c) -> state = State.IN_TAG
                c == '>'.code -> finishStartTag(empty = false)
                c == '/'.code -> state = State.EMPTY_TAG_SLASH
                else -> fail(XmlErrorCondition.NOT_WELL_FORMED, "Invalid character in element name")
            }

            State.IN_TAG -> when {
                isXmlWhitespace(c) -> needWhitespace = false
                c == '>'.code -> finishStartTag(empty = false)
                c == '/'.code -> state = State.EMPTY_TAG_SLASH
                isNameStartChar(c) -> {
                    if (needWhitespace) fail(XmlErrorCondition.NOT_WELL_FORMED, "Missing whitespace between attributes")
                    if (pendingAttrs.size >= limits.maxAttributes) {
                        fail(XmlErrorCondition.POLICY_VIOLATION, "More than ${limits.maxAttributes} attributes")
                    }
                    attrNameBuf.clear()
                    attrNameBuf.appendCodePoint(c)
                    state = State.ATTR_NAME
                }
                else -> fail(XmlErrorCondition.NOT_WELL_FORMED, "Invalid character in tag")
            }

            State.ATTR_NAME -> when {
                isNameChar(c) -> appendName(attrNameBuf, c)
                isXmlWhitespace(c) -> state = State.AFTER_ATTR_NAME
                c == '='.code -> state = State.BEFORE_ATTR_VALUE
                else -> fail(XmlErrorCondition.NOT_WELL_FORMED, "Invalid character in attribute name")
            }

            State.AFTER_ATTR_NAME -> when {
                isXmlWhitespace(c) -> Unit
                c == '='.code -> state = State.BEFORE_ATTR_VALUE
                else -> fail(XmlErrorCondition.NOT_WELL_FORMED, "Expected '=' after attribute name")
            }

            State.BEFORE_ATTR_VALUE -> when {
                isXmlWhitespace(c) -> Unit
                c == '"'.code || c == '\''.code -> {
                    quote = c
                    valueBuf.clear()
                    state = State.ATTR_VALUE
                }
                else -> fail(XmlErrorCondition.NOT_WELL_FORMED, "Attribute value must be quoted")
            }

            State.ATTR_VALUE -> when {
                c == quote -> {
                    pendingAttrs += attrNameBuf.toString() to valueBuf.toString()
                    needWhitespace = true
                    state = State.IN_TAG
                }
                c == '<'.code -> fail(XmlErrorCondition.NOT_WELL_FORMED, "'<' in attribute value")
                c == '&'.code -> {
                    entityInAttribute = true
                    entityBuf.clear()
                    state = State.ENTITY
                }
                // Attribute-value normalisation (XML 1.0 §3.3.3); CR is already LF here.
                c == 0x9 || c == 0xA -> valueBuf.append(' ')
                else -> valueBuf.appendCodePoint(c)
            }

            State.EMPTY_TAG_SLASH ->
                if (c == '>'.code) finishStartTag(empty = true)
                else fail(XmlErrorCondition.NOT_WELL_FORMED, "Expected '>' after '/'")

            State.END_NAME -> when {
                nameBuf.isEmpty() && !isNameStartChar(c) ->
                    fail(XmlErrorCondition.NOT_WELL_FORMED, "Invalid end tag name")
                isNameChar(c) -> appendName(nameBuf, c)
                isXmlWhitespace(c) -> state = State.END_TAG_TRAILING
                c == '>'.code -> finishEndTag()
                else -> fail(XmlErrorCondition.NOT_WELL_FORMED, "Invalid character in end tag")
            }

            State.END_TAG_TRAILING -> when {
                isXmlWhitespace(c) -> Unit
                c == '>'.code -> finishEndTag()
                else -> fail(XmlErrorCondition.NOT_WELL_FORMED, "Invalid character in end tag")
            }

            State.TEXT -> {
                if (stack.size == 1) {
                    // Between top-level elements only whitespace (keepalives) is allowed.
                    when {
                        c == '<'.code -> {
                            inStanza = true
                            stanzaBytes = 1
                            state = State.LT
                        }
                        isXmlWhitespace(c) -> Unit
                        else -> fail(XmlErrorCondition.BAD_FORMAT, "Character data between top-level elements")
                    }
                    return
                }
                when (c) {
                    '<'.code -> {
                        bracketRun = 0
                        state = State.LT
                    }
                    '&'.code -> {
                        bracketRun = 0
                        entityInAttribute = false
                        entityBuf.clear()
                        state = State.ENTITY
                    }
                    else -> {
                        if (c == '>'.code && bracketRun >= 2) {
                            fail(XmlErrorCondition.NOT_WELL_FORMED, "']]>' is not allowed in text")
                        }
                        bracketRun = if (c == ']'.code) bracketRun + 1 else 0
                        textBuf.appendCodePoint(c)
                    }
                }
            }

            State.ENTITY -> when {
                c == ';'.code -> {
                    val target = if (entityInAttribute) valueBuf else textBuf
                    target.appendCodePoint(resolveEntity(entityBuf.toString()))
                    state = if (entityInAttribute) State.ATTR_VALUE else State.TEXT
                }
                entityBuf.length >= MAX_ENTITY_LENGTH ->
                    fail(XmlErrorCondition.NOT_WELL_FORMED, "Entity reference too long")
                c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '#'.code ->
                    entityBuf.appendCodePoint(c)
                else -> fail(XmlErrorCondition.NOT_WELL_FORMED, "Invalid entity reference")
            }

            State.AFTER_CLOSE ->
                if (!isXmlWhitespace(c)) fail(XmlErrorCondition.NOT_WELL_FORMED, "Data after the stream was closed")
        }
    }

    private fun appendName(buf: StringBuilder, c: Int) {
        if (buf.length >= limits.maxNameLength) {
            fail(XmlErrorCondition.POLICY_VIOLATION, "Name longer than ${limits.maxNameLength} characters")
        }
        buf.appendCodePoint(c)
    }

    private fun resolveEntity(name: String): Int = when (name) {
        "lt" -> '<'.code
        "gt" -> '>'.code
        "amp" -> '&'.code
        "quot" -> '"'.code
        "apos" -> '\''.code
        else -> {
            if (!name.startsWith("#")) {
                fail(XmlErrorCondition.RESTRICTED_XML, "Only the predefined entities are allowed")
            }
            val hex = name.startsWith("#x")
            val digits = name.substring(if (hex) 2 else 1)
            val value = if (digits.isEmpty()) null else digits.toIntOrNull(if (hex) 16 else 10)
            if (value == null || !isXmlChar(value)) {
                fail(XmlErrorCondition.NOT_WELL_FORMED, "Invalid character reference &$name;")
            }
            value
        }
    }

    private fun handleXmlDeclaration(content: String) {
        if (!(content.startsWith("xml") && content.length > 3 && isXmlWhitespace(content[3].code))) {
            fail(XmlErrorCondition.RESTRICTED_XML, "Processing instructions are not allowed")
        }
        val pseudo = PSEUDO_ATTRIBUTE.findAll(content.substring(3)).associate {
            it.groupValues[1] to it.groupValues[3]
        }
        if (pseudo["version"] != "1.0") fail(XmlErrorCondition.NOT_WELL_FORMED, "XML declaration must have version 1.0")
        val encoding = pseudo["encoding"]
        if (encoding != null && !encoding.equals("UTF-8", ignoreCase = true)) {
            fail(XmlErrorCondition.UNSUPPORTED_ENCODING, "Encoding '$encoding' is not supported; XMPP requires UTF-8")
        }
    }

    private fun finishStartTag(empty: Boolean) {
        val qname = nameBuf.toString()
        var declarations: MutableMap<String, String>? = null
        val plain = ArrayList<Pair<String, String>>(pendingAttrs.size)
        val seenRaw = HashSet<String>()
        for ((name, value) in pendingAttrs) {
            if (!seenRaw.add(name)) fail(XmlErrorCondition.NOT_WELL_FORMED, "Duplicate attribute '$name'")
            when {
                name == "xmlns" -> {
                    if (value == Namespaces.XML) fail(XmlErrorCondition.NOT_WELL_FORMED, "The xml namespace cannot be the default")
                    (declarations ?: HashMap<String, String>().also { declarations = it })[""] = value
                }
                name.startsWith("xmlns:") -> {
                    val prefix = name.substring(6)
                    if (!isNcName(prefix) || prefix == "xmlns" || value.isEmpty() ||
                        (prefix == "xml") != (value == Namespaces.XML)
                    ) {
                        fail(XmlErrorCondition.NOT_WELL_FORMED, "Invalid namespace declaration '$name'")
                    }
                    (declarations ?: HashMap<String, String>().also { declarations = it })[prefix] = value
                }
                else -> plain += name to value
            }
        }
        if ((declarations?.size ?: 0) > limits.maxNamespaceDeclarations) {
            fail(XmlErrorCondition.POLICY_VIOLATION, "More than ${limits.maxNamespaceDeclarations} namespace declarations")
        }
        if (stack.size + 1 > limits.maxDepth) {
            fail(XmlErrorCondition.POLICY_VIOLATION, "Nesting deeper than ${limits.maxDepth}")
        }

        val (prefix, local) = splitQName(qname)
        val namespace = resolvePrefix(prefix ?: "", declarations)
            ?: fail(XmlErrorCondition.NOT_WELL_FORMED, "Undeclared namespace prefix '$prefix'")

        val attributes = ArrayList<Attribute>(plain.size)
        val seenExpanded = HashSet<Pair<String, String>>()
        for ((name, value) in plain) {
            val (attrPrefix, attrLocal) = splitQName(name)
            val attrNamespace = if (attrPrefix == null) "" else resolvePrefix(attrPrefix, declarations)
                ?: fail(XmlErrorCondition.NOT_WELL_FORMED, "Undeclared namespace prefix '$attrPrefix'")
            if (!seenExpanded.add(attrNamespace to attrLocal)) {
                fail(XmlErrorCondition.NOT_WELL_FORMED, "Duplicate attribute '$name'")
            }
            attributes += Attribute(attrLocal, value, attrNamespace)
        }

        val open = OpenElement(qname, local, namespace, attributes, declarations)
        if (stack.isEmpty()) {
            if (namespace != Namespaces.STREAMS) fail(XmlErrorCondition.INVALID_NAMESPACE, "Root element is not in the streams namespace")
            if (local != "stream") fail(XmlErrorCondition.BAD_FORMAT, "Root element must be <stream>")
            if (empty) fail(XmlErrorCondition.BAD_FORMAT, "Stream header cannot be an empty element")
            stack += open
            state = State.TEXT
            handler.onStreamOpen(StreamHeader(attributes, declarations?.get("") ?: ""))
            return
        }
        flushText()
        stack += open
        state = State.TEXT
        if (empty) closeTop()
    }

    private fun finishEndTag() {
        val qname = nameBuf.toString()
        val top = stack.last()
        if (top.qname != qname) fail(XmlErrorCondition.NOT_WELL_FORMED, "End tag </$qname> does not match <${top.qname}>")
        if (stack.size == 1) {
            stack.clear()
            inStanza = false
            state = State.AFTER_CLOSE
            handler.onStreamClose()
            return
        }
        flushText()
        closeTop()
        state = State.TEXT
    }

    private fun closeTop() {
        val top = stack.removeAt(stack.lastIndex)
        val element = Element(top.name, top.namespace, top.attributes, top.children)
        if (stack.size == 1) {
            inStanza = false
            stanzaBytes = 0
            handler.onElement(element)
        } else {
            stack.last().children += element
        }
    }

    private fun flushText() {
        if (textBuf.isNotEmpty()) {
            stack.last().children += Text(textBuf.toString())
            textBuf.clear()
        }
    }

    private fun resolvePrefix(prefix: String, own: Map<String, String>?): String? {
        if (prefix == "xml") return Namespaces.XML
        own?.get(prefix)?.let { return it }
        for (i in stack.indices.reversed()) {
            stack[i].declarations?.get(prefix)?.let { return it }
        }
        return if (prefix.isEmpty()) "" else null
    }

    private fun splitQName(qname: String): Pair<String?, String> {
        val colon = qname.indexOf(':')
        if (colon < 0) return null to qname
        val prefix = qname.substring(0, colon)
        val local = qname.substring(colon + 1)
        if (!isNcName(prefix) || !isNcName(local)) fail(XmlErrorCondition.NOT_WELL_FORMED, "Invalid qualified name '$qname'")
        return prefix to local
    }

    private fun fail(condition: XmlErrorCondition, message: String): Nothing =
        throw XmlStreamException(condition, message)

    private companion object {
        const val CDATA_OPEN = "[CDATA["
        const val MAX_ENTITY_LENGTH = 10
        const val MAX_XML_DECLARATION = 256
        val PSEUDO_ATTRIBUTE = Regex("""([a-z]+)\s*=\s*(["'])(.*?)\2""")
    }
}
