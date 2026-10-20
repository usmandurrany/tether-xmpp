package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.ElementBuilder
import io.github.usmandurrany.tether.xml.Text
import io.github.usmandurrany.tether.xml.XmlParse
import io.github.usmandurrany.tether.xml.XmlWriter
import io.github.usmandurrany.tether.xml.element

/**
 * Your own data in a message, beside its body: a picture's link and size, a
 * location, a reply-to id, anything. Each kind of data is one XML element with
 * a [name] and a [namespace] you choose, so other apps' data never clashes
 * with yours and clients that do not know it simply ignore it.
 *
 * Most apps make one with [payloadType], which turns key/value fields into
 * child elements, so there is no XML to write:
 *
 * ```
 * data class Picture(val url: String, val size: Long, val width: Int?, val height: Int?)
 *
 * val PictureType = payloadType<Picture>(
 *     name = "picture",
 *     namespace = "urn:example:picture:0",
 *     write = { put("url", it.url); put("size", it.size); put("width", it.width); put("height", it.height) },
 *     read = { Picture(string("url") ?: return@payloadType null, long("size") ?: 0, int("width"), int("height")) },
 * )
 *
 * chat.send(bob, "Sunset at the beach", PictureType.write(picture))   // the caption is the body
 * message.payload(PictureType)?.let { showPicture(it) }               // on the other side
 * ```
 *
 * On the wire that message carries
 * `<picture xmlns="urn:example:picture:0"><url>https://…</url><size>48213</size><width>1024</width><height>768</height></picture>`
 * next to its `<body>`. For a shape [payloadType] cannot express, implement
 * this interface and build the [Element] yourself.
 */
public interface PayloadType<T : Any> {
    /** The element's name, such as "picture". */
    public val name: String

    /** The element's namespace: a URI you own, such as "urn:example:picture:0". Bump the version when the format changes. */
    public val namespace: String

    /** The element that carries [value]. Its name and namespace must be [name] and [namespace]. */
    public fun write(value: T): Element

    /** The value an element of this type carries, or null when it is malformed. */
    public fun read(element: Element): T?

    /** True when [element] is of this type. */
    public fun matches(element: Element): Boolean = element.name == name && element.namespace == namespace
}

/**
 * A [PayloadType] built from key/value fields: each [PayloadWriter.put] becomes
 * a child element, and [read] gets them back with typed getters. Values that
 * are null are left out, so optional fields cost nothing.
 *
 * [read] should return null for an element it cannot use (a required field
 * missing); an exception thrown there is treated the same way, so one bad
 * message never breaks a chat.
 */
public fun <T : Any> payloadType(
    name: String,
    namespace: String,
    write: PayloadWriter.(T) -> Unit,
    read: PayloadReader.() -> T?,
): PayloadType<T> = FieldPayloadType(name, namespace, write, read)

private class FieldPayloadType<T : Any>(
    override val name: String,
    override val namespace: String,
    private val writer: PayloadWriter.(T) -> Unit,
    private val reader: PayloadReader.() -> T?,
) : PayloadType<T> {
    init {
        Payloads.requireUsable(name, namespace)
    }

    override fun write(value: T): Element = PayloadWriter.build(name, namespace) { writer(value) }

    override fun read(element: Element): T? =
        if (!matches(element)) null else runCatching { PayloadReader(element).reader() }.getOrNull()
}

/** Writes the fields of a payload. Keys must be XML names: letters, digits, `-`, `_` and `.`, not starting with a digit. */
@io.github.usmandurrany.tether.xml.XmlDsl
public class PayloadWriter internal constructor(private val builder: ElementBuilder) {
    /** Adds a field; a null [value] is left out. Numbers and booleans are written as text, like `48213` and `true`. */
    public fun put(key: String, value: Any?) {
        if (value == null) return
        Payloads.requireName(key, "key")
        builder.element(key) { text(value.toString()) }
    }

    /** Adds one field per value, all with the same [key]; read them back with [PayloadReader.strings]. */
    public fun putAll(key: String, values: Iterable<Any?>) {
        values.forEach { put(key, it) }
    }

    /** Adds a group of fields under [key], read back with [PayloadReader.child]. */
    public fun put(key: String, block: PayloadWriter.() -> Unit) {
        Payloads.requireName(key, "key")
        builder.element(key) { PayloadWriter(this).block() }
    }

    /** Adds an attribute to this element instead of a child; read back with [PayloadReader.attribute]. */
    public fun attribute(name: String, value: Any?) {
        if (value == null) return
        Payloads.requireName(name, "attribute name")
        builder.attribute(name, value.toString())
    }

    internal companion object {
        fun build(name: String, namespace: String, block: PayloadWriter.() -> Unit): Element =
            element(name, namespace) { PayloadWriter(this).block() }
    }
}

/** Reads the fields of a payload. Every getter returns null when the field is missing or not of that type. */
public class PayloadReader(
    /** The whole element, for anything the getters do not cover. */
    public val element: Element,
) {
    /** The text of the first field called [key]. */
    public fun string(key: String): String? = element.child(key)?.let(::textOf)

    public fun int(key: String): Int? = string(key)?.trim()?.toIntOrNull()

    public fun long(key: String): Long? = string(key)?.trim()?.toLongOrNull()

    public fun double(key: String): Double? = string(key)?.trim()?.toDoubleOrNull()

    /** `true` or `1` is true, `false` or `0` is false (XML Schema booleans). */
    public fun boolean(key: String): Boolean? = when (string(key)?.trim()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    /** Every field called [key], in order, as written by [PayloadWriter.putAll]. */
    public fun strings(key: String): List<String> = element.children(key).map(::textOf)

    /** The group of fields under [key], as written by `put(key) { … }`. */
    public fun child(key: String): PayloadReader? = element.child(key)?.let(::PayloadReader)

    /** Every group called [key], in order. */
    public fun children(key: String): List<PayloadReader> = element.children(key).map(::PayloadReader)

    /** An attribute of this element. */
    public fun attribute(name: String): String? = element.attribute(name)

    /** True when there is a field called [key]. */
    public fun has(key: String): Boolean = element.child(key) != null

    private fun textOf(e: Element): String = buildString { e.children.forEach { if (it is Text) append(it.value) } }
}

/**
 * Helpers for custom data in messages: which elements count as an app's own,
 * and how to keep them in a database.
 */
public object Payloads {
    /**
     * Namespaces the SDK reads and writes itself. A payload may not use them,
     * and elements in them never show up in [Message.payloads].
     */
    public val reserved: Set<String> = setOf(
        "",
        ImNs.CLIENT,
        ImNs.CARBONS,
        ImNs.FORWARD,
        ImNs.DELAY,
        ImNs.RECEIPTS,
        ImNs.CHAT_STATES,
        ImNs.MARKERS,
        ImNs.SID,
        ImNs.HINTS,
        ImNs.MAM,
        "urn:xmpp:sm:3",
    )

    /** The app-level elements of a message stanza: every child that is not part of a protocol the SDK handles. */
    public fun of(message: Element): List<Element> = message.elements.filter { it.namespace !in reserved }

    /**
     * [payloads] as one string, for a [MessageStore] column. Read it back with
     * [fromXml]. An empty list is an empty string.
     */
    public fun toXml(payloads: List<Element>): String =
        if (payloads.isEmpty()) "" else XmlWriter.toXml(Element(STORE_NAME, STORE_NS, children = payloads))

    /** The payloads saved with [toXml]; empty for an empty or unreadable string. */
    public fun fromXml(xml: String): List<Element> {
        if (xml.isBlank()) return emptyList()
        return runCatching { XmlParse.element(xml).elements }.getOrDefault(emptyList())
    }

    /** Throws when [payloads] cannot go in a message: a reserved namespace, which would confuse the SDK and other clients. */
    internal fun requireSendable(payloads: List<Element>) {
        for (p in payloads) {
            require(p.namespace !in reserved) {
                "Payload <${p.name}> uses namespace \"${p.namespace}\", which the SDK reserves; give it a namespace of your own"
            }
        }
    }

    internal fun requireUsable(name: String, namespace: String) {
        requireName(name, "payload name")
        require(namespace !in reserved) { "Namespace \"$namespace\" is reserved by the SDK; use one of your own, such as urn:example:$name:0" }
    }

    internal fun requireName(name: String, what: String) {
        require(NAME.matches(name)) { "Invalid $what \"$name\": use letters, digits, '-', '_' and '.', not starting with a digit" }
        require(!name.startsWith("xml", ignoreCase = true)) { "Invalid $what \"$name\": names starting with \"xml\" are reserved" }
    }

    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_.-]*$")
    private const val STORE_NAME = "payloads"
    private const val STORE_NS = "urn:tether:payloads:0"
}

/** The first payload of [type] in the message, or null. */
public fun <T : Any> Message.payload(type: PayloadType<T>): T? = payloads.firstNotNullOfOrNull(type::readOrNull)

/** Every payload of [type] in the message, in order. */
public fun <T : Any> Message.payloads(type: PayloadType<T>): List<T> = payloads.mapNotNull(type::readOrNull)

/** True when the message has a payload of [type]. */
public fun Message.has(type: PayloadType<*>): Boolean = payloads.any(type::matches)

/** The first payload of [type] in the message, or null. */
public fun <T : Any> ChatMessage.payload(type: PayloadType<T>): T? = payloads.firstNotNullOfOrNull(type::readOrNull)

/** Every payload of [type] in the message, in order. */
public fun <T : Any> ChatMessage.payloads(type: PayloadType<T>): List<T> = payloads.mapNotNull(type::readOrNull)

/** [PayloadType.read] for a matching element, with a malformed one (null or an exception) as null. */
private fun <T : Any> PayloadType<T>.readOrNull(element: Element): T? =
    if (!matches(element)) null else runCatching { read(element) }.getOrNull()
