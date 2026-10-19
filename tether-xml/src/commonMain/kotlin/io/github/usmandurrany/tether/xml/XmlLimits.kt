package io.github.usmandurrany.tether.xml

/**
 * Hard limits applied while parsing. Exceeding any of them fails the stream with
 * [XmlErrorCondition.POLICY_VIOLATION] instead of letting a peer exhaust memory.
 */
public data class XmlLimits(
    /** Maximum bytes of one top-level element (a stanza or nonza), including its tags. */
    public val maxStanzaBytes: Int = 256 * 1024,
    /** Maximum bytes before the stream header is complete, XML declaration included. */
    public val maxStreamHeaderBytes: Int = 4 * 1024,
    /** Maximum element nesting depth, counting `<stream:stream>` as depth 1. */
    public val maxDepth: Int = 32,
    /** Maximum attributes on one element, namespace declarations included. */
    public val maxAttributes: Int = 64,
    /** Maximum namespace declarations on one element. */
    public val maxNamespaceDeclarations: Int = 16,
    /** Maximum length of an element or attribute name, in characters. */
    public val maxNameLength: Int = 256,
) {
    init {
        require(maxStanzaBytes > 0 && maxStreamHeaderBytes > 0 && maxDepth >= 2 &&
            maxAttributes > 0 && maxNamespaceDeclarations > 0 && maxNameLength > 0) {
            "XML limits must be positive (and maxDepth at least 2)"
        }
    }
}
