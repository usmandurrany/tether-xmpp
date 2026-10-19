package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.xml.Element

/** An identity from disco#info (XEP-0030). */
public data class DiscoIdentity(val category: String, val type: String, val name: String?)

/** disco#info result for one entity. [available] is false when the query failed. */
public data class DiscoInfo(
    val available: Boolean,
    val identities: List<DiscoIdentity> = emptyList(),
    val features: Set<String> = emptySet(),
) {
    internal companion object {
        fun parse(query: Element): DiscoInfo = DiscoInfo(
            available = true,
            identities = query.children("identity").map {
                DiscoIdentity(it.attribute("category").orEmpty(), it.attribute("type").orEmpty(), it.attribute("name"))
            },
            features = query.children("feature").mapNotNull { it.attribute("var") }.toSet(),
        )
    }
}

/**
 * What the server offered on this session. Features are only ever enabled when
 * listed here; this object is rebuilt on every new (non-resumed) session.
 */
public data class ServerCapabilities(
    /** Stream features after authentication, as received. */
    val streamFeatures: Element,
    val server: DiscoInfo,
    val account: DiscoInfo,
) {
    public fun hasStreamFeature(name: String, namespace: String): Boolean =
        streamFeatures.child(name, namespace) != null

    public fun serverSupports(feature: String): Boolean = feature in server.features

    public fun accountSupports(feature: String): Boolean = feature in account.features
}
