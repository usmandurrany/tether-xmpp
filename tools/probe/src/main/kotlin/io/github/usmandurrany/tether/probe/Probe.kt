package io.github.usmandurrany.tether.probe

import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.core.DiscoInfo
import io.github.usmandurrany.tether.core.NegotiationFailure
import io.github.usmandurrany.tether.core.StreamNegotiator
import io.github.usmandurrany.tether.core.TrafficLogger
import io.github.usmandurrany.tether.core.XmppConfig
import io.github.usmandurrany.tether.core.XmppNegotiationException
import io.github.usmandurrany.tether.sasl.SaslConfig
import io.github.usmandurrany.tether.transport.EndpointResolver
import io.github.usmandurrany.tether.transport.JndiSrvLookup
import io.github.usmandurrany.tether.transport.JvmSocketTransportFactory
import io.github.usmandurrany.tether.transport.TlsConfig
import io.github.usmandurrany.tether.transport.TlsVerificationException
import io.github.usmandurrany.tether.xml.Element
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Properties
import kotlin.system.exitProcess

/**
 * Connects to any XMPP server, logs in, and prints what it advertises: stream
 * features at every stage, SASL mechanisms, and disco#info for the server and the
 * account. It relies only on RFC 6120 and XEP-0030, so it works the same on any
 * compliant server.
 *
 * Usage: ./gradlew :tools:probe:run --args="[path/to/xmpp-test.properties] [--verbose]"
 */
fun main(args: Array<String>) {
    val verbose = "--verbose" in args
    val path = args.firstOrNull { !it.startsWith("--") } ?: "xmpp-test.properties"
    val file = File(path)
    if (!file.exists()) {
        System.err.println("No $path. Copy xmpp-test.properties.example to $path and fill it in.")
        exitProcess(2)
    }
    val props = Properties().apply { file.inputStream().use { load(it) } }
    fun prop(key: String): String? = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

    val config = XmppConfig(
        jid = BareJid.parse(prop("jid") ?: error("jid is required")),
        password = prop("password") ?: error("password is required"),
        resource = prop("resource") ?: "tether-probe",
        host = prop("host"),
        port = prop("port")?.toInt(),
        directTls = prop("directTls").toBoolean(),
        tls = TlsConfig(pinnedCertificateSha256 = setOfNotNull(prop("tls.pin")?.lowercase()?.replace(":", ""))),
        sasl = SaslConfig(allowPlain = prop("sasl.allowPlain").toBoolean()),
    )
    val logger = if (!verbose) null else object : TrafficLogger {
        override fun sent(xml: String) = println("  C: $xml")
        override fun received(xml: String) = println("  S: $xml")
    }

    println("Probing ${config.jid} ...")
    val negotiator = StreamNegotiator(config, JvmSocketTransportFactory(config.tls), EndpointResolver(JndiSrvLookup()), logger)
    val session = try {
        runBlocking { negotiator.negotiate() }
    } catch (e: XmppNegotiationException) {
        println("\nFAILED: ${e.message}")
        val tls = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<TlsVerificationException>().firstOrNull()
        if (e.failure == NegotiationFailure.TLS_VERIFICATION && tls?.peerCertificateSha256 != null) {
            println("The server's certificate SHA-256 is ${tls.peerCertificateSha256}")
            println("If you trust this server (e.g. your own, self-signed), add this line to $path:")
            println("  tls.pin=${tls.peerCertificateSha256}")
        }
        exitProcess(1)
    }

    session.transport.close()
    println("\nConnected and authenticated as ${session.jid}")
    println("TLS: ${session.tls.protocol}, ${session.tls.cipherSuite}${if (session.tls.pinned) " (pinned certificate)" else ""}")
    println("Server certificate SHA-256: ${session.tls.peerCertificateSha256}")
    println("SASL mechanism used: ${session.saslMechanism}${if (session.channelBound) " (channel-bound)" else ""}")
    val sasl2 = session.featuresAfterTls.child("authentication", "urn:xmpp:sasl:2") != null
    println("SASL2 (XEP-0388): ${if (sasl2) "offered, used for this login" else "not offered"}")
    println("FAST (XEP-0484): ${negotiator.fastToken?.let { "token issued for ${it.mechanism}" } ?: "no token issued"}")
    session.featuresBeforeTls?.let { section("Stream features before TLS", describe(it)) }
    section("Stream features after TLS (pre-auth)", describe(session.featuresAfterTls))
    section("Stream features after login", describe(session.capabilities.streamFeatures))
    section("Server disco#info (${session.jid.bare.domain})", describe(session.capabilities.server))
    section("Account disco#info (${session.jid.bare})", describe(session.capabilities.account))
}

private fun section(title: String, lines: List<String>) {
    println("\n== $title ==")
    if (lines.isEmpty()) println("  (none)") else lines.forEach { println("  $it") }
}

/** One line per top-level feature with its namespace, plus notable children (mechanisms, binding types, ...). */
private fun describe(features: Element): List<String> = features.elements.flatMap { feature ->
    val head = "${feature.name} [${feature.namespace}]"
    listOf(head) + feature.elements.flatMap { detail(it, "    ") }
}

/** A child and its descendants (SASL2 nests inline features such as FAST two levels down). */
private fun detail(child: Element, indent: String): List<String> {
    val text = child.text.trim()
    val attrs = child.attributes.joinToString(" ") { "${it.name}=${it.value}" }
    val line = "$indent- ${child.name}${if (text.isNotEmpty()) ": $text" else ""}${if (attrs.isNotEmpty()) " ($attrs)" else ""}"
    return listOf(line) + child.elements.flatMap { detail(it, "$indent  ") }
}

private fun describe(info: DiscoInfo): List<String> {
    if (!info.available) return listOf("(query failed or not supported)")
    return info.identities.map { "identity: ${it.category}/${it.type}${it.name?.let { n -> " \"$n\"" } ?: ""}" } +
        info.features.sorted().map { "feature: $it" }
}
