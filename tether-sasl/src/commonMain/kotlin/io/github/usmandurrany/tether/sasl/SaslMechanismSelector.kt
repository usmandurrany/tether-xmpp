package io.github.usmandurrany.tether.sasl

/** What the server advertised: SASL mechanisms and, if present, XEP-0440 channel-binding types. */
public data class SaslOffer(
    public val mechanisms: List<String>,
    /** Null when the server did not advertise XEP-0440 at all. */
    public val channelBindingTypes: List<String>? = null,
)

/** Security facts about the current connection and account, from the transport and credential store. */
public class SaslSecurityContext(
    /** TLS is established and the certificate was verified for the XMPP domain. */
    public val tlsVerified: Boolean,
    /** Channel bindings the TLS layer can produce, most preferred first. */
    public val channelBindings: List<ChannelBinding> = emptyList(),
    /**
     * This account has logged in with a `-PLUS` mechanism on this server before.
     * Losing channel binding afterwards is treated as an attack, not a fallback.
     */
    public val channelBindingPinned: Boolean = false,
)

public data class SaslConfig(
    /** Allow PLAIN (off by default). Still requires verified TLS. */
    public val allowPlain: Boolean = false,
    /** Allow SCRAM-SHA-1 for old servers (off by default). */
    public val allowScramSha1: Boolean = false,
    /** Preference order. Entries the policy does not allow are skipped. */
    public val mechanismOrder: List<String> = DEFAULT_ORDER,
    public val scramLimits: ScramLimits = ScramLimits(),
) {
    public companion object {
        public val DEFAULT_ORDER: List<String> = listOf(
            "SCRAM-SHA-512-PLUS", "SCRAM-SHA-256-PLUS", "SCRAM-SHA-1-PLUS",
            "SCRAM-SHA-512", "SCRAM-SHA-256", "SCRAM-SHA-1",
            "PLAIN",
        )
    }
}

/**
 * Picks the strongest mechanism that both sides support and policy allows. It
 * never falls back silently: if nothing acceptable is offered it throws, and the
 * caller surfaces the reason instead of retrying with something weaker.
 */
public object SaslMechanismSelector {

    public fun select(
        offer: SaslOffer,
        credentials: SaslCredentials,
        security: SaslSecurityContext,
        config: SaslConfig = SaslConfig(),
    ): SaslMechanism {
        if (!security.tlsVerified) {
            throw SaslException(SaslFailure.INSECURE_TRANSPORT, "Refusing to authenticate without verified TLS")
        }
        val offered = offer.mechanisms.toSet()
        val serverOffersPlus = offer.mechanisms.any { it.startsWith("SCRAM-") && it.endsWith("-PLUS") }

        for (name in config.mechanismOrder) {
            if (name !in offered) continue
            val mechanism = build(name, offer, credentials, security, config, serverOffersPlus) ?: continue
            if (security.channelBindingPinned && !mechanism.usesChannelBinding) {
                throw SaslException(
                    SaslFailure.DOWNGRADE_DETECTED,
                    "This account used channel binding before, but it is not available now (offered: ${offer.mechanisms})",
                )
            }
            return mechanism
        }
        throw SaslException(
            SaslFailure.NO_ACCEPTABLE_MECHANISM,
            "None of the offered mechanisms ${offer.mechanisms} is allowed by the current configuration",
        )
    }

    private fun build(
        name: String,
        offer: SaslOffer,
        credentials: SaslCredentials,
        security: SaslSecurityContext,
        config: SaslConfig,
        serverOffersPlus: Boolean,
    ): SaslMechanism? {
        if (name == "PLAIN") {
            val password = credentials.password ?: return null
            return if (config.allowPlain) PlainMechanism(credentials.username, password) else null
        }
        val plus = name.endsWith("-PLUS")
        val algorithm = ScramAlgorithm.entries.firstOrNull {
            name == (if (plus) it.plusMechanismName else it.mechanismName)
        } ?: return null
        if (algorithm == ScramAlgorithm.SHA1 && !config.allowScramSha1) return null
        if (!credentials.canUse(algorithm)) return null

        val binding = if (plus) pickBinding(offer, security) ?: return null else null
        return ScramMechanism(
            algorithm = algorithm,
            credentials = credentials,
            channelBinding = binding,
            // GS2 "y" tells the server we could bind but it offered no -PLUS; a server that
            // did offer -PLUS then knows the list was stripped. If the server offers -PLUS but
            // we share no binding type, "n" is correct (XEP-0474 §7).
            clientSupportsBinding = !plus && !serverOffersPlus && security.channelBindings.isNotEmpty(),
            advertised = offer,
            limits = config.scramLimits,
        )
    }

    /**
     * First local binding the server supports. Without XEP-0440 the server's types are
     * unknown, so the client's most preferred binding is tried.
     */
    private fun pickBinding(offer: SaslOffer, security: SaslSecurityContext): ChannelBinding? {
        val serverTypes = offer.channelBindingTypes ?: return security.channelBindings.firstOrNull()
        return security.channelBindings.firstOrNull { it.type in serverTypes }
    }
}
