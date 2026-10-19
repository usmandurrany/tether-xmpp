package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.sasl.SaslConfig
import io.github.usmandurrany.tether.sasl.ScramKeys
import io.github.usmandurrany.tether.transport.TlsConfig
import io.github.usmandurrany.tether.xml.XmlLimits

/** Everything needed to open one authenticated session. */
public class XmppConfig(
    public val jid: BareJid,
    /** Needed for the first login and for PLAIN; can be null when [scramKeys] are stored. */
    public val password: String? = null,
    public val scramKeys: List<ScramKeys> = emptyList(),
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
        require(password != null || scramKeys.isNotEmpty()) { "A password or stored SCRAM keys is required" }
        require(port == null || port in 1..65535) { "port out of range" }
        require(port == null || host != null) { "port needs host" }
    }

    override fun toString(): String = "XmppConfig(jid=$jid, host=$host, port=$port, password=${if (password != null) "***" else "none"})"
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
) {
    /**
     * The config for [jid]: logs in with [password] when given, otherwise with
     * the SCRAM keys an earlier login saved in [vault]. Throws
     * [IllegalArgumentException] when there is neither.
     */
    public suspend fun configFor(jid: BareJid, password: String?, vault: CredentialVault): XmppConfig {
        val keys = if (password.isNullOrEmpty()) vault.loadKeys(jid) else emptyList()
        require(!password.isNullOrEmpty() || keys.isNotEmpty()) { "No saved login for $jid: sign in with the password" }
        return XmppConfig(
            jid = jid,
            password = password?.ifEmpty { null },
            scramKeys = keys,
            resource = resource,
            host = host,
            port = port,
            directTls = directTls,
            tls = tls,
            sasl = sasl,
        )
    }
}
