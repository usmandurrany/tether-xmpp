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
