package io.github.usmandurrany.tether.transport

import org.conscrypt.Conscrypt
import java.net.IDN
import java.net.InetAddress
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Where TLS sockets come from. Lets Android (or a JVM app) plug in Conscrypt for `tls-exporter`. */
public interface TlsProvider {
    public fun sslContext(trustManager: X509TrustManager): SSLContext

    /**
     * `tls-exporter` channel-binding data (RFC 9266) for an established socket,
     * or null when the provider cannot export keying material. Only defined for
     * TLS 1.3 here: with TLS 1.2 it is unsafe without extended master secret.
     */
    public fun tlsExporter(socket: SSLSocket): ByteArray?
}

/** The platform's default TLS. The JDK has no keying-material export, so only `tls-server-end-point` is offered. */
public object PlatformTlsProvider : TlsProvider {
    override fun sslContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

    override fun tlsExporter(socket: SSLSocket): ByteArray? = null
}

/**
 * TLS through Conscrypt (BoringSSL), which can export keying material, so
 * SCRAM-*-PLUS works with `tls-exporter`. Add `org.conscrypt:conscrypt-android`
 * (Android) or `org.conscrypt:conscrypt-openjdk-uber` (JVM) to use it.
 */
public class ConscryptTlsProvider : TlsProvider {
    private val provider by lazy { Conscrypt.newProvider() }

    override fun sslContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS", provider).apply { init(null, arrayOf(trustManager), null) }

    override fun tlsExporter(socket: SSLSocket): ByteArray? {
        if (!Conscrypt.isConscrypt(socket) || socket.session.protocol != "TLSv1.3") return null
        return Conscrypt.exportKeyingMaterial(socket, "EXPORTER-Channel-Binding", null, 32)
    }

    public companion object {
        /** True when Conscrypt is on the classpath and its native library loads. */
        public val isAvailable: Boolean by lazy {
            runCatching { Class.forName("org.conscrypt.Conscrypt"); Conscrypt.isAvailable() }.getOrDefault(false)
        }
    }
}

/**
 * Checks that a certificate names [domain] (RFC 6125 DNS-ID): subjectAltName
 * dNSName entries, or iPAddress entries for an IP literal, and the subject CN
 * only when there are no dNSName entries. A wildcard may only be the whole
 * leftmost label and matches exactly one label.
 *
 * Done explicitly after every handshake that is not pinned, because not every
 * TLS provider applies `endpointIdentificationAlgorithm` to a plain trust manager.
 */
internal object HostnameCheck {
    fun matches(certificate: X509Certificate, domain: String): Boolean {
        val sans = try {
            certificate.subjectAlternativeNames.orEmpty()
        } catch (e: CertificateParsingException) {
            emptyList()
        }
        val dnsNames = sans.filter { it[0] == 2 }.map { it[1] as String }
        val ips = sans.filter { it[0] == 7 }.map { it[1] as String }
        return matches(domain, dnsNames, ips, commonName(certificate))
    }

    fun matches(domain: String, dnsNames: List<String>, ipAddresses: List<String>, commonName: String?): Boolean {
        val host = domain.trimEnd('.')
        if (isIpLiteral(host)) {
            val wanted = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return ipAddresses.any { runCatching { InetAddress.getByName(it) == wanted }.getOrDefault(false) }
        }
        val ascii = toAscii(host) ?: return false
        val candidates = dnsNames.ifEmpty { listOfNotNull(commonName) }
        return candidates.any { pattern -> toAscii(pattern.trimEnd('.'))?.let { nameMatches(ascii, it) } == true }
    }

    private fun nameMatches(host: String, pattern: String): Boolean {
        if (!pattern.startsWith("*.")) return host == pattern
        val suffix = pattern.substring(1) // ".example.com"
        // The wildcard must cover one whole label, and never a public suffix like "*.com".
        if (suffix.count { it == '.' } < 2 || '*' in suffix) return false
        val label = host.removeSuffix(suffix)
        return host.endsWith(suffix) && label.isNotEmpty() && '.' !in label
    }

    private fun toAscii(name: String): String? =
        runCatching { IDN.toASCII(name, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun isIpLiteral(host: String): Boolean =
        ':' in host || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))

    /** The most specific CN of the subject, from its RFC 2253 form. */
    private fun commonName(certificate: X509Certificate): String? {
        val dn = certificate.subjectX500Principal.getName("RFC2253")
        var result: String? = null
        var i = 0
        while (i < dn.length) {
            // Read one attribute type=value pair, honouring backslash escapes.
            val start = i
            val value = StringBuilder()
            var inValue = false
            var type = ""
            while (i < dn.length && (dn[i] != ',' && dn[i] != '+' || !inValue)) {
                val c = dn[i]
                if (!inValue && c == '=') {
                    type = dn.substring(start, i).trim()
                    inValue = true
                } else if (inValue && c == '\\' && i + 1 < dn.length) {
                    i++
                    value.append(dn[i])
                } else if (inValue) {
                    value.append(c)
                }
                i++
            }
            i++
            if (type.equals("CN", ignoreCase = true) && result == null) result = value.toString()
        }
        return result
    }
}
