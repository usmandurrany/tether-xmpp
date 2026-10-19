package io.github.usmandurrany.tether.demo

import android.content.Context
import io.github.usmandurrany.tether.core.BackgroundMode
import java.util.UUID

/**
 * Everything the connection settings page edits. Works with any XMPP server:
 * leave [host] empty to find the server through DNS SRV records, or set it to
 * connect to a host (or IP address) directly.
 *
 * The password is deliberately not here: it is used once, then the SDK keeps
 * only the derived SCRAM keys in encrypted storage.
 */
data class ConnectionSettings(
    val jid: String = "",
    /** Empty lets the server choose a resource. */
    val resource: String = "",
    /** Empty: look up the server through DNS SRV records of the JID's domain. */
    val host: String = "",
    /** Empty: 5222 for STARTTLS, 5223 for direct TLS. Only used with [host]. */
    val port: String = "",
    /** With [host]: TLS from the first byte (port 5223) instead of STARTTLS. */
    val directTls: Boolean = false,
    /** SHA-256 fingerprints of self-signed server certificates to trust, lowercase hex. */
    val pinnedCertificates: Set<String> = emptySet(),
    /** SASL PLAIN, off by default. The SDK still requires verified TLS for it. */
    val allowPlain: Boolean = false,
    /** SCRAM-SHA-1 for old servers, off by default. */
    val allowScramSha1: Boolean = false,
    val backgroundMode: BackgroundMode = BackgroundMode.CLOSE,
    /** Connect on app start with the saved login keys. */
    val autoConnect: Boolean = false,
    /** Log portal (tether-debug). Empty endpoint: remote logging off. */
    val logEndpoint: String = "",
    val logIngestKey: String = "",
    val logLabel: String = "",
    /** Replace message bodies with their length in logs. */
    val redactBodies: Boolean = false,
) {
    val remoteLoggingEnabled: Boolean get() = logEndpoint.isNotBlank() && logIngestKey.isNotBlank()
}

/** Saves [ConnectionSettings] in plain SharedPreferences: nothing secret is in them. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("connection_settings", Context.MODE_PRIVATE)

    fun load(): ConnectionSettings = ConnectionSettings(
        jid = prefs.getString("jid", "").orEmpty(),
        resource = prefs.getString("resource", "").orEmpty(),
        host = prefs.getString("host", "").orEmpty(),
        port = prefs.getString("port", "").orEmpty(),
        directTls = prefs.getBoolean("directTls", false),
        pinnedCertificates = prefs.getStringSet("pins", emptySet()).orEmpty().toSet(),
        allowPlain = prefs.getBoolean("allowPlain", false),
        allowScramSha1 = prefs.getBoolean("allowScramSha1", false),
        backgroundMode = runCatching { BackgroundMode.valueOf(prefs.getString("backgroundMode", "")!!) }
            .getOrDefault(BackgroundMode.CLOSE),
        autoConnect = prefs.getBoolean("autoConnect", false),
        logEndpoint = prefs.getString("logEndpoint", "").orEmpty(),
        logIngestKey = prefs.getString("logIngestKey", "").orEmpty(),
        logLabel = prefs.getString("logLabel", "").orEmpty(),
        redactBodies = prefs.getBoolean("redactBodies", false),
    )

    fun save(settings: ConnectionSettings) {
        prefs.edit()
            .putString("jid", settings.jid.trim())
            .putString("resource", settings.resource.trim())
            .putString("host", settings.host.trim())
            .putString("port", settings.port.trim())
            .putBoolean("directTls", settings.directTls)
            .putStringSet("pins", settings.pinnedCertificates)
            .putBoolean("allowPlain", settings.allowPlain)
            .putBoolean("allowScramSha1", settings.allowScramSha1)
            .putString("backgroundMode", settings.backgroundMode.name)
            .putBoolean("autoConnect", settings.autoConnect)
            .putString("logEndpoint", settings.logEndpoint.trim())
            .putString("logIngestKey", settings.logIngestKey.trim())
            .putString("logLabel", settings.logLabel.trim())
            .putBoolean("redactBodies", settings.redactBodies)
            .apply()
    }

    /** A random id for this install, so the log portal can follow one device across runs. */
    val installId: String
        get() = prefs.getString("installId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("installId", it).apply()
        }
}
