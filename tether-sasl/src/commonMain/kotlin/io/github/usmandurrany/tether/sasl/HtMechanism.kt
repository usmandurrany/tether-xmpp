package io.github.usmandurrany.tether.sasl

import io.github.usmandurrany.tether.crypto.Crypto
import io.github.usmandurrany.tether.crypto.HashAlgorithm
import io.github.usmandurrany.tether.crypto.constantTimeEquals

/**
 * A login token the server issued through XEP-0484 (FAST). It logs in without
 * the password, so it is a secret: keep it in encrypted storage. It only works
 * with the [mechanism] it was issued for, and only for the client installation
 * that asked for it (the SASL2 user-agent id).
 */
public class FastToken(
    /** The HT mechanism the token is bound to, e.g. `HT-SHA-256-NONE`. */
    public val mechanism: String,
    public val token: String,
    /** When the server stops accepting it (XEP-0082 date-time), as sent by the server. */
    public val expiry: String?,
) {
    init {
        require(HtMechanism.parse(mechanism) != null) { "Not an HT mechanism: $mechanism" }
        require(token.isNotEmpty()) { "token must not be empty" }
    }

    override fun toString(): String = "FastToken($mechanism, expiry=$expiry)"
}

/**
 * The SASL HT mechanism family (draft-schmaus-kitten-sasl-ht), which XEP-0484
 * uses to log in with a [FastToken] in one round trip:
 *
 * - client: `username NUL HMAC(token, "Initiator" || cb-data)`
 * - server, with success: `HMAC(token, "Responder" || cb-data)`
 *
 * The server is checked too: [complete] fails unless the server proves it
 * knows the token. With a channel-binding variant (`-ENDP`, `-EXPR`) the
 * login only works on this TLS connection, so it cannot be relayed.
 */
public class HtMechanism internal constructor(
    private val username: String,
    private val token: FastToken,
    private val hash: HashAlgorithm,
    private val channelBinding: ChannelBinding?,
) : SaslMechanism {
    override val name: String = token.mechanism
    override val usesChannelBinding: Boolean get() = channelBinding != null

    private val cbData = channelBinding?.data ?: ByteArray(0)
    private var sent = false

    override fun initialResponse(): ByteArray {
        if (sent) throw SaslException(SaslFailure.PROTOCOL_ERROR, "Initial response already sent")
        require(username.isNotEmpty() && '\u0000' !in username) { "Invalid username" }
        sent = true
        val proof = Crypto.hmac(hash, token.token.encodeToByteArray(), "Initiator".encodeToByteArray() + cbData)
        return username.encodeToByteArray() + byteArrayOf(0) + proof
    }

    override fun evaluateChallenge(challenge: ByteArray): ByteArray =
        throw SaslException(SaslFailure.PROTOCOL_ERROR, "HT does not expect challenges")

    override fun complete(additionalData: ByteArray?) {
        if (!sent) throw SaslException(SaslFailure.PROTOCOL_ERROR, "Success before the initial response")
        val expected = Crypto.hmac(hash, token.token.encodeToByteArray(), "Responder".encodeToByteArray() + cbData)
        if (additionalData == null || !constantTimeEquals(additionalData, expected)) {
            throw SaslException(SaslFailure.SERVER_SIGNATURE_MISMATCH, "The server did not prove it knows the FAST token")
        }
    }

    public companion object {
        private val HASHES = mapOf("SHA-256" to HashAlgorithm.SHA256, "SHA-512" to HashAlgorithm.SHA512)
        private val BINDINGS = mapOf(
            "NONE" to null,
            "ENDP" to ChannelBinding.TLS_SERVER_END_POINT,
            "EXPR" to ChannelBinding.TLS_EXPORTER,
        )

        /** Hash and channel-binding type of a supported HT mechanism name, or null. */
        internal fun parse(name: String): Pair<HashAlgorithm, String?>? {
            if (!name.startsWith("HT-")) return null
            val cb = name.substringAfterLast('-')
            val hash = HASHES[name.removePrefix("HT-").removeSuffix("-$cb")] ?: return null
            if (cb !in BINDINGS) return null
            return hash to BINDINGS[cb]
        }

        /**
         * The HT mechanism to ask the server for a new token with, from the
         * FAST mechanisms it offers: channel-bound first (the TLS layer must be
         * able to produce that binding), then the stronger hash.
         */
        public fun pickForNewToken(offered: List<String>, security: SaslSecurityContext): String? =
            offered.mapNotNull { name -> parse(name)?.let { name to it } }
                .filter { (_, p) -> p.second == null || security.channelBindings.any { it.type == p.second } }
                .filter { (_, p) -> !security.channelBindingPinned || p.second != null }
                .sortedWith(
                    compareBy<Pair<String, Pair<HashAlgorithm, String?>>> { (_, p) -> bindingRank(p.second) }
                        .thenByDescending { (_, p) -> p.first.outputBytes },
                )
                .firstOrNull()?.first

        /**
         * A mechanism that logs in with [token], or null when it cannot be used
         * on this connection: the server no longer offers its mechanism, the
         * TLS layer cannot produce its channel binding, or the account is
         * pinned to channel binding and the token has none.
         */
        public fun forToken(
            username: String,
            token: FastToken,
            offered: List<String>,
            security: SaslSecurityContext,
        ): HtMechanism? {
            if (!security.tlsVerified || token.mechanism !in offered) return null
            val (hash, cbType) = parse(token.mechanism) ?: return null
            if (security.channelBindingPinned && cbType == null) return null
            val binding = cbType?.let { type -> security.channelBindings.firstOrNull { it.type == type } ?: return null }
            return HtMechanism(username, token, hash, binding)
        }

        private fun bindingRank(type: String?): Int = when (type) {
            ChannelBinding.TLS_EXPORTER -> 0
            ChannelBinding.TLS_SERVER_END_POINT -> 1
            else -> 2
        }
    }
}
