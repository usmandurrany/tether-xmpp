package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.crypto.Crypto
import io.github.usmandurrany.tether.sasl.FastToken
import io.github.usmandurrany.tether.sasl.SaslConfig
import io.github.usmandurrany.tether.sasl.ScramKeys
import io.github.usmandurrany.tether.transport.TlsConfig
import io.github.usmandurrany.tether.xml.XmlLimits

/** Everything needed to open one authenticated session. */
public class XmppConfig(
    public val jid: BareJid,
    /** Needed for the first login and for PLAIN; can be null when [scramKeys] or a [fastToken] are stored. */
    public val password: String? = null,
    public val scramKeys: List<ScramKeys> = emptyList(),
    /**
     * A FAST token (XEP-0484) from an earlier login, tried first when the
     * server offers FAST. It only works with the same [userAgent] id it was
     * issued to.
     */
    public val fastToken: FastToken? = null,
    /** Identifies this installation to the server during SASL2 login (XEP-0388). */
    public val userAgent: UserAgent = UserAgent(),
    /** Null lets the server assign a resource. */
    public val resource: String? = null,
    /** Skips DNS SRV and connects here. */
    public val host: String? = null,
    public val port: Int? = null,
    /** With [host]: TLS from the first byte instead of STARTTLS. */
    public val directTls: Boolean = false,
    public val tls: TlsConfig = TlsConfig(),
    public val sasl: SaslConfig = SaslConfig(),
    /** This account logged in with channel binding before; refuse to continue without it. */
    public val channelBindingPinned: Boolean = false,
    /** Maximum wait for each server response during negotiation. */
    public val responseTimeoutMillis: Long = 30_000,
    public val xmlLimits: XmlLimits = XmlLimits(),
) {
    init {
        require(jid.local != null) { "A client JID needs a localpart" }
        require(password != null || scramKeys.isNotEmpty() || fastToken != null) {
            "A password, stored SCRAM keys or a FAST token is required"
        }
        require(port == null || port in 1..65535) { "port out of range" }
        require(port == null || host != null) { "port needs host" }
    }

    override fun toString(): String = "XmppConfig(jid=$jid, host=$host, port=$port, password=${if (password != null) "***" else "none"})"
}

/**
 * The SASL2 user agent (XEP-0388): who is logging in. FAST tokens are tied to
 * [id], so keep it stable for the installation (see [CredentialVault.userAgentId]).
 */
public data class UserAgent(
    /** A UUID v4, the same on every login from this installation. */
    val id: String = randomUuid(),
    val software: String? = "Tether",
    /** A name the user would recognise, such as the device model. Never shared with other users. */
    val device: String? = null,
) {
    public companion object {
        /** A random UUID v4 (RFC 9562). */
        public fun randomUuid(): String {
            val b = Crypto.randomBytes(16)
            b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
            b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }
    }
}

/**
 * How to reach the server, for the ready-made `chat(...)` builders. All
 * optional: by default the server is found through DNS SRV records with
 * STARTTLS and SCRAM login.
 */
public data class ConnectionOptions(
    /** Null lets the server assign a resource. */
    val resource: String? = null,
    /** Skips DNS SRV and connects here. */
    val host: String? = null,
    val port: Int? = null,
    /** With [host]: TLS from the first byte instead of STARTTLS. */
    val directTls: Boolean = false,
    val tls: TlsConfig = TlsConfig(),
    val sasl: SaslConfig = SaslConfig(),
    /** Device name sent with SASL2 logins (see [UserAgent.device]). */
    val device: String? = null,
) {
    /**
     * The config for [jid]: logs in with [password] when given, otherwise with
     * the SCRAM keys or FAST token an earlier login saved in [vault]. A saved
     * FAST token is tried first whenever the server offers FAST. Throws
     * [IllegalArgumentException] when there is no password and nothing saved.
     */
    public suspend fun configFor(jid: BareJid, password: String?, vault: CredentialVault): XmppConfig {
        val keys = if (password.isNullOrEmpty()) vault.loadKeys(jid) else emptyList()
        val token = vault.loadFastToken(jid)
        require(!password.isNullOrEmpty() || keys.isNotEmpty() || token != null) { "No saved login for $jid: sign in with the password" }
        return XmppConfig(
            jid = jid,
            password = password?.ifEmpty { null },
            scramKeys = keys,
            fastToken = token,
            userAgent = UserAgent(id = vault.userAgentId(jid), device = device),
            resource = resource,
            host = host,
            port = port,
            directTls = directTls,
            tls = tls,
            sasl = sasl,
        )
    }
}
