package io.github.usmandurrany.tether.xml

/** Stream error conditions the XML layer can raise, named as in RFC 6120 §4.9.3. */
public enum class XmlErrorCondition(public val wireName: String) {
    /** Well-formed XML that breaks XMPP rules, such as text between stanzas. */
    BAD_FORMAT("bad-format"),
    /** The root element is not in the streams namespace. */
    INVALID_NAMESPACE("invalid-namespace"),
    /** Not well-formed XML, including invalid UTF-8. */
    NOT_WELL_FORMED("not-well-formed"),
    /** A size, depth or count limit from [XmlLimits] was exceeded. */
    POLICY_VIOLATION("policy-violation"),
    /** XML features forbidden in XMPP: comments, processing instructions, DTDs, custom entities. */
    RESTRICTED_XML("restricted-xml"),
    /** The XML declaration names an encoding other than UTF-8. */
    UNSUPPORTED_ENCODING("unsupported-encoding"),
}

/** Thrown by [XmppStreamParser] when the incoming stream must be closed with a stream error. */
public class XmlStreamException(
    public val condition: XmlErrorCondition,
    message: String,
) : Exception("${condition.wireName}: $message")
