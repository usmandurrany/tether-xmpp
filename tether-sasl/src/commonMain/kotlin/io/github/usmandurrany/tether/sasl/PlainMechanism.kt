package io.github.usmandurrany.tether.sasl

/**
 * SASL PLAIN (RFC 4616). The password crosses the wire (inside TLS), so the
 * selector only offers it when explicitly enabled and the TLS certificate was
 * verified. Needed for servers that check passwords against LDAP or another
 * external store where SCRAM is impossible.
 */
public class PlainMechanism internal constructor(
    private val username: String,
    private val password: String,
) : SaslMechanism {
    override val name: String = "PLAIN"
    override val usesChannelBinding: Boolean = false
    private var sent = false

    override fun initialResponse(): ByteArray {
        if (sent) throw SaslException(SaslFailure.PROTOCOL_ERROR, "Initial response already sent")
        require(username.isNotEmpty() && '\u0000' !in username) { "Invalid username" }
        sent = true
        // authzid is left empty: authorise as the authenticated identity.
        return "\u0000$username\u0000${prepareOpaqueString(password)}".encodeToByteArray()
    }

    override fun evaluateChallenge(challenge: ByteArray): ByteArray =
        throw SaslException(SaslFailure.PROTOCOL_ERROR, "PLAIN does not expect challenges")

    override fun complete(additionalData: ByteArray?) {
        if (!sent) throw SaslException(SaslFailure.PROTOCOL_ERROR, "Success before the initial response")
    }
}
