package io.github.usmandurrany.tether.core

/**
 * An XMPP address without a resource (RFC 7622). Validation is structural only
 * for now; full PRECIS preparation of each part comes with the address module.
 */
public data class BareJid(public val local: String?, public val domain: String) {
    init {
        require(domain.isNotEmpty() && domain.length <= 1023) { "Invalid domain" }
        require(domain.none { it == '@' || it == '/' || it.isWhitespace() }) { "Invalid domain '$domain'" }
        if (local != null) {
            require(local.isNotEmpty() && local.length <= 1023) { "Invalid localpart" }
            require(local.none { it in "\"&'/:<>@" || it.isWhitespace() }) { "Invalid localpart '$local'" }
        }
    }

    override fun toString(): String = if (local != null) "$local@$domain" else domain

    public companion object {
        public fun parse(value: String): BareJid {
            val withoutResource = value.substringBefore('/')
            val at = withoutResource.indexOf('@')
            return if (at < 0) BareJid(null, withoutResource)
            else BareJid(withoutResource.substring(0, at), withoutResource.substring(at + 1))
        }
    }
}

/** A full address with a resource, as assigned by the server at bind time. */
public data class FullJid(public val bare: BareJid, public val resource: String) {
    init {
        require(resource.isNotEmpty() && resource.length <= 1023) { "Invalid resource" }
    }

    override fun toString(): String = "$bare/$resource"

    public companion object {
        public fun parse(value: String): FullJid {
            val slash = value.indexOf('/')
            require(slash > 0) { "'$value' has no resource" }
            return FullJid(BareJid.parse(value.substring(0, slash)), value.substring(slash + 1))
        }
    }
}
