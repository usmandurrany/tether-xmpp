package io.github.usmandurrany.tether.debug

import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.Node
import io.github.usmandurrany.tether.xml.Text

/**
 * Rewrites a stanza before it is logged. Must not throw and must not keep
 * references to the element. Apps can add their own (for example to hide a
 * custom token element) through [DebugConfig.redactor].
 */
public fun interface StanzaRedactor {
    public fun redact(element: Element): Element

    public companion object {
        /** Applies [redactors] in order. */
        public fun chain(vararg redactors: StanzaRedactor): StanzaRedactor = StanzaRedactor { element ->
            redactors.fold(element) { acc, redactor -> redactor.redact(acc) }
        }
    }
}

/** The redactors Tether ships. [credentials] always runs and cannot be turned off. */
public object Redaction {
    public const val MARK: String = "[redacted]"

    /**
     * Removes everything that could log someone in or impersonate them:
     * - SASL and SASL2 payloads (RFC 6120 §6, XEP-0388), also already blanked by the core;
     * - FAST tokens (XEP-0484);
     * - passwords in in-band registration and password changes (XEP-0077) and legacy auth (XEP-0078);
     * - data-form fields whose name mentions a password, secret or token (push secrets, XEP-0357);
     * - Authorization and Cookie headers of HTTP upload slots (XEP-0363).
     */
    public val credentials: StanzaRedactor = StanzaRedactor { transform(it, ::redactCredentials) }

    /**
     * Replaces message bodies, subjects and XHTML-IM with their length, also
     * inside forwarded messages (carbons, archive results). For remote logs
     * from people outside the team.
     */
    public val messageBodies: StanzaRedactor = StanzaRedactor { transform(it, ::redactBody) }

    private const val SASL = "urn:ietf:params:xml:ns:xmpp-sasl"
    private const val SASL2 = "urn:xmpp:sasl:2"
    private const val FAST = "urn:xmpp:fast:0"
    private const val REGISTER = "jabber:iq:register"
    private const val LEGACY_AUTH = "jabber:iq:auth"
    private const val DATA_FORMS = "jabber:x:data"
    private const val HTTP_UPLOAD = "urn:xmpp:http:upload:0"
    private const val CLIENT = "jabber:client"
    private const val XHTML_IM = "http://jabber.org/protocol/xhtml-im"

    private val SASL_PAYLOADS = setOf("auth", "challenge", "response", "success")
    private val SASL2_PAYLOADS = setOf("initial-response", "challenge", "response", "additional-data")
    private val SECRET_FIELD = Regex("pass|secret|token|key", RegexOption.IGNORE_CASE)
    private val SECRET_HEADERS = setOf("authorization", "cookie")

    /** Returns a replacement for [element], or null to keep it and look at its children. */
    private fun redactCredentials(element: Element): Element? = when {
        element.namespace == SASL && element.name in SASL_PAYLOADS -> blank(element)
        element.namespace == SASL2 && element.name in SASL2_PAYLOADS -> blank(element)
        element.namespace == FAST && element.attribute("token") != null ->
            element.copy(attributes = element.attributes.map { if (it.name == "token" && it.namespace.isEmpty()) it.copy(value = MARK) else it })
        (element.namespace == REGISTER || element.namespace == LEGACY_AUTH) &&
            element.name in setOf("password", "digest", "hash") -> blank(element)
        element.namespace == DATA_FORMS && element.name == "field" &&
            element.attribute("var")?.contains(SECRET_FIELD) == true ->
            element.copy(children = element.children.map { if (it is Element && it.name == "value") blank(it) else it })
        element.namespace == HTTP_UPLOAD && element.name == "header" &&
            element.attribute("name")?.lowercase() in SECRET_HEADERS -> blank(element)
        else -> null
    }

    private fun redactBody(element: Element): Element? = when {
        element.namespace == CLIENT && (element.name == "body" || element.name == "subject") ->
            element.copy(children = listOf(Text("[${element.name}: ${element.text.length} chars]")))
        element.namespace == XHTML_IM && element.name == "html" -> blank(element)
        else -> null
    }

    private fun blank(element: Element): Element =
        if (element.children.isEmpty()) element else element.copy(children = listOf(Text(MARK)))

    private fun transform(element: Element, rule: (Element) -> Element?): Element {
        rule(element)?.let { return it }
        var changed = false
        val children = element.children.map { child: Node ->
            if (child is Element) transform(child, rule).also { if (it !== child) changed = true } else child
        }
        return if (changed) element.copy(children = children) else element
    }
}
