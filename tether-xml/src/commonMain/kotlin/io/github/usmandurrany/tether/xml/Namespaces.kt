package io.github.usmandurrany.tether.xml

/** Namespaces the XML layer itself needs to know about. */
public object Namespaces {
    /** Namespace of the `<stream:stream>` root element (RFC 6120 §4.8.1). */
    public const val STREAMS: String = "http://etherx.jabber.org/streams"

    /** The namespace bound to the reserved `xml` prefix, used by `xml:lang`. */
    public const val XML: String = "http://www.w3.org/XML/1998/namespace"
}
