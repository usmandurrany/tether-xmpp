package io.github.usmandurrany.tether.xml

/** A node in an immutable XML tree: either an [Element] or a run of [Text]. */
public sealed interface Node

/** Character data. Entities are already decoded; the writer escapes again on output. */
public data class Text(public val value: String) : Node

/**
 * An attribute. Unprefixed attributes have an empty [namespace], as in the XML
 * Namespaces spec. `xml:lang` has namespace [Namespaces.XML] and name `lang`.
 */
public data class Attribute(
    public val name: String,
    public val value: String,
    public val namespace: String = "",
)

/**
 * An immutable XML element with a resolved namespace.
 *
 * Prefixes are not kept: two elements are equal when their namespaces, names,
 * attributes and children are equal, whatever prefixes the sender used.
 */
public data class Element(
    public val name: String,
    public val namespace: String,
    public val attributes: List<Attribute> = emptyList(),
    public val children: List<Node> = emptyList(),
) : Node {

    /** Value of the attribute with this [name] and [namespace], or null. */
    public fun attribute(name: String, namespace: String = ""): String? =
        attributes.firstOrNull { it.name == name && it.namespace == namespace }?.value

    /** Direct child elements, in document order. */
    public val elements: List<Element> get() = children.filterIsInstance<Element>()

    /** First direct child with this [name] and [namespace] (defaults to this element's namespace). */
    public fun child(name: String, namespace: String = this.namespace): Element? =
        children.firstOrNull { it is Element && it.name == name && it.namespace == namespace } as Element?

    /** All direct children with this [name] and [namespace] (defaults to this element's namespace). */
    public fun children(name: String, namespace: String = this.namespace): List<Element> =
        elements.filter { it.name == name && it.namespace == namespace }

    /** Concatenated direct text content (not including descendants). */
    public val text: String
        get() = buildString { children.forEach { if (it is Text) append(it.value) } }

    /** XML form for logs and debugging; never throws, even for content the writer rejects. */
    override fun toString(): String =
        runCatching { XmlWriter.toXml(this) }.getOrElse { "Element(name=$name, namespace=$namespace, <unserialisable>)" }
}

@DslMarker
public annotation class XmlDsl

/** Builds an [Element]. Child elements default to the parent's namespace. */
public inline fun element(
    name: String,
    namespace: String,
    block: ElementBuilder.() -> Unit = {},
): Element = ElementBuilder(name, namespace).apply(block).build()

@XmlDsl
public class ElementBuilder(public val name: String, public val namespace: String) {
    private val attributes = ArrayList<Attribute>()
    private val children = ArrayList<Node>()

    /** Adds an attribute; a null [value] is skipped so optional attributes stay concise. */
    public fun attribute(name: String, value: String?, namespace: String = "") {
        if (value != null) attributes += Attribute(name, value, namespace)
    }

    public inline fun element(
        name: String,
        namespace: String = this.namespace,
        block: ElementBuilder.() -> Unit = {},
    ) {
        child(ElementBuilder(name, namespace).apply(block).build())
    }

    public fun text(value: String) {
        if (value.isNotEmpty()) children += Text(value)
    }

    public fun child(node: Node) {
        children += node
    }

    public fun build(): Element = Element(name, namespace, attributes.toList(), children.toList())
}
