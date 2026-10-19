package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.crypto.Crypto
import io.github.usmandurrany.tether.sasl.ChannelBinding
import io.github.usmandurrany.tether.sasl.SaslCredentials
import io.github.usmandurrany.tether.sasl.SaslException
import io.github.usmandurrany.tether.sasl.SaslMechanism
import io.github.usmandurrany.tether.sasl.SaslMechanismSelector
import io.github.usmandurrany.tether.sasl.SaslOffer
import io.github.usmandurrany.tether.sasl.SaslSecurityContext
import io.github.usmandurrany.tether.sasl.ScramKeys
import io.github.usmandurrany.tether.sasl.ScramMechanism
import io.github.usmandurrany.tether.transport.Endpoint
import io.github.usmandurrany.tether.transport.EndpointResolver
import io.github.usmandurrany.tether.transport.TlsInfo
import io.github.usmandurrany.tether.transport.TlsVerificationException
import io.github.usmandurrany.tether.transport.XmppTransport
import io.github.usmandurrany.tether.transport.XmppTransportFactory
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.ElementBuilder
import io.github.usmandurrany.tether.xml.StreamHeader
import io.github.usmandurrany.tether.xml.Text
import io.github.usmandurrany.tether.xml.XmlStreamException
import io.github.usmandurrany.tether.xml.XmlWriter
import io.github.usmandurrany.tether.xml.XmppStreamHandler
import io.github.usmandurrany.tether.xml.XmppStreamParser
import io.github.usmandurrany.tether.xml.element
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64

/** Receives raw traffic for debugging. SASL payloads are always redacted before reaching it. */
public interface TrafficLogger {
    public fun sent(xml: String)
    public fun received(xml: String)
}

/** The outcome of a successful negotiation: an authenticated, bound stream. */
public class NegotiatedSession internal constructor(
    public val jid: FullJid,
    public val transport: XmppTransport,
    public val tls: TlsInfo,
    public val saslMechanism: String,
    /** Store these (securely) to log in later without the password. Null for PLAIN. */
    public val scramKeys: ScramKeys?,
    /** True when the login was bound to the TLS channel; persist it to pin channel binding. */
    public val channelBound: Boolean,
    public val capabilities: ServerCapabilities,
    /** Stream features at each stage, for diagnostics: before TLS (null for direct TLS), after TLS, after login. */
    public val featuresBeforeTls: Element?,
    public val featuresAfterTls: Element,
    /** XEP-0198 state, when the server offered SM and accepted `<enable/>`. */
    public val streamManagement: StreamManagement?,
    /** True when an earlier SM session was resumed instead of starting fresh. */
    public val resumed: Boolean,
    /**
     * The previous SM session, whose unconfirmed stanzas the new session sends
     * first. Left untouched until then, so a failed attempt loses nothing.
     */
    internal val carryOver: StreamManagement?,
    internal val reader: StanzaReader,
)

/** What a new attempt needs to resume the previous session (XEP-0198 §5). */
public class ResumeRequest(
    public val streamManagement: StreamManagement,
    public val jid: FullJid,
    public val capabilities: ServerCapabilities,
)

/**
 * Opens one authenticated session (RFC 6120 §4–7):
 * connect → stream header → STARTTLS → SASL → stream restart → bind → service discovery.
 *
 * Security rules enforced here:
 * - TLS is mandatory. A server without STARTTLS fails with [NegotiationFailure.TLS_UNAVAILABLE].
 * - Authentication only starts on a verified TLS connection.
 * - Each feature is used only if the server advertised it.
 * - SASL payloads never reach the [TrafficLogger].
 *
 * One negotiator handles one attempt; the connection supervisor (next phase) owns
 * retries and makes sure only one attempt runs at a time.
 */
public class StreamNegotiator(
    private val config: XmppConfig,
    private val transports: XmppTransportFactory,
    private val resolver: EndpointResolver,
    private val logger: TrafficLogger? = null,
) {
    private val domain = config.jid.domain

    /**
     * Opens a session. With [resume], tries XEP-0198 resumption first and falls
     * back to a fresh bind; unconfirmed stanzas are carried over either way.
     */
    public suspend fun negotiate(resume: ResumeRequest? = null): NegotiatedSession {
        val transport = connect()
        try {
            return negotiateOn(transport, resume)
        } catch (t: Throwable) {
            transport.close()
            throw when (t) {
                is XmppNegotiationException, is CancellationException -> t
                is TlsVerificationException ->
                    XmppNegotiationException(NegotiationFailure.TLS_VERIFICATION, t.message.orEmpty(), cause = t)
                is SaslException -> XmppNegotiationException(NegotiationFailure.AUTHENTICATION, t.message.orEmpty(), cause = t)
                is XmlStreamException -> XmppNegotiationException(NegotiationFailure.PROTOCOL, "Server sent invalid XML: ${t.message}", cause = t)
                else -> XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, t.message ?: t.toString(), cause = t)
            }
        }
    }

    private suspend fun connect(): XmppTransport {
        val endpoints = config.host?.let {
            listOf(Endpoint(it, config.port ?: if (config.directTls) 5223 else 5222, config.directTls))
        } ?: resolver.resolve(domain)
        if (endpoints.isEmpty()) {
            throw XmppNegotiationException(NegotiationFailure.UNREACHABLE, "DNS says $domain offers no XMPP client service")
        }
        val errors = ArrayList<String>()
        for (endpoint in endpoints) {
            try {
                return transports.connect(endpoint, domain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TlsVerificationException) {
                // A certificate problem is not fixed by trying another host of the same domain.
                throw XmppNegotiationException(NegotiationFailure.TLS_VERIFICATION, e.message.orEmpty(), cause = e)
            } catch (e: Exception) {
                errors += "$endpoint: ${e.message ?: e::class.simpleName}"
            }
        }
        throw XmppNegotiationException(NegotiationFailure.UNREACHABLE, "Could not connect: ${errors.joinToString("; ")}")
    }

    private suspend fun negotiateOn(transport: XmppTransport, resume: ResumeRequest?): NegotiatedSession {
        val reader = StanzaReader(transport, config, logger)

        var features = reader.openStream(from = null)
        var featuresBeforeTls: Element? = null
        if (transport.tls == null) {
            featuresBeforeTls = features
            features.child("starttls", Ns.TLS) ?: throw XmppNegotiationException(
                NegotiationFailure.TLS_UNAVAILABLE,
                "Server does not offer STARTTLS; refusing to continue unencrypted",
            )
            reader.send(element("starttls", Ns.TLS))
            val answer = reader.nextNonza()
            when {
                answer.name == "proceed" && answer.namespace == Ns.TLS -> transport.startTls(domain)
                else -> throw XmppNegotiationException(NegotiationFailure.TLS_UNAVAILABLE, "Server refused STARTTLS")
            }
            reader.restart()
            features = reader.openStream(from = config.jid.toString())
        }
        val tls = transport.tls
            ?: throw XmppNegotiationException(NegotiationFailure.TLS_VERIFICATION, "TLS is not established")
        val featuresAfterTls = features

        val mechanism = authenticate(reader, transport, features)
        reader.restart()
        val postAuth = reader.openStream(from = config.jid.toString())

        val smOffered = postAuth.child("sm", Ns.SM) != null
        val previous = resume?.streamManagement
        if (resume != null && previous != null && smOffered && previous.canResume) {
            if (tryResume(reader, previous)) {
                return NegotiatedSession(
                    jid = resume.jid,
                    transport = transport,
                    tls = tls,
                    saslMechanism = mechanism.name,
                    scramKeys = (mechanism as? ScramMechanism)?.derivedKeys,
                    channelBound = mechanism.usesChannelBinding,
                    capabilities = resume.capabilities,
                    featuresBeforeTls = featuresBeforeTls,
                    featuresAfterTls = featuresAfterTls,
                    streamManagement = previous,
                    resumed = true,
                    carryOver = previous,
                    reader = reader,
                )
            }
        }

        val jid = bind(reader, postAuth)
        establishLegacySessionIfRequired(reader, postAuth)
        val capabilities = ServerCapabilities(
            streamFeatures = postAuth,
            server = discoInfo(reader, domain),
            account = discoInfo(reader, jid.bare.toString()),
        )
        // Capability gate: SM only when the server advertised it on this stream.
        val sm = if (smOffered) enableStreamManagement(reader) else null
        return NegotiatedSession(
            jid = jid,
            transport = transport,
            tls = tls,
            saslMechanism = mechanism.name,
            scramKeys = (mechanism as? ScramMechanism)?.derivedKeys,
            channelBound = mechanism.usesChannelBinding,
            capabilities = capabilities,
            featuresBeforeTls = featuresBeforeTls,
            featuresAfterTls = featuresAfterTls,
            streamManagement = sm,
            resumed = false,
            carryOver = previous,
            reader = reader,
        )
    }

    /**
     * Sends `<resume/>` and applies the server's `h` to [previous]. Returns true
     * when resumed, false when the server refused and a fresh bind is needed.
     */
    private suspend fun tryResume(reader: StanzaReader, previous: StreamManagement): Boolean {
        reader.send(
            element("resume", Ns.SM) {
                attribute("h", previous.inbound.toString())
                attribute("previd", previous.resumptionId)
            },
        )
        val answer = reader.nextNonza()
        if (answer.namespace != Ns.SM) throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Unexpected <${answer.name}/> after <resume/>")
        return when (answer.name) {
            "resumed" -> {
                val h = StreamManagement.parseH(answer.attribute("h"))
                    ?: throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "<resumed/> without a valid h")
                if (!previous.onAck(h)) {
                    throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Server acknowledged more stanzas than were sent")
                }
                true
            }
            "failed" -> {
                // The server may still say how much it received (XEP-0198 §5), which avoids duplicates.
                StreamManagement.parseH(answer.attribute("h"))?.let { previous.onAck(it) }
                false
            }
            else -> throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Unexpected <${answer.name}/> after <resume/>")
        }
    }

    /** XEP-0198 §3. A refusal is not an error: the session simply runs without SM. */
    private suspend fun enableStreamManagement(reader: StanzaReader): StreamManagement? {
        reader.send(element("enable", Ns.SM) { attribute("resume", "true") })
        while (true) {
            val answer = reader.nextNonza()
            if (answer.namespace != Ns.SM) {
                reader.unclaimed(answer) // A stanza that arrived first; not counted, as SM is not on yet.
                continue
            }
            return when (answer.name) {
                "enabled" -> {
                    val resumable = answer.attribute("resume").let { it == "true" || it == "1" }
                    StreamManagement(
                        resumptionId = answer.attribute("id")?.takeIf { resumable && it.isNotEmpty() },
                        location = answer.attribute("location"),
                        maxResumeSeconds = answer.attribute("max")?.toIntOrNull()?.takeIf { it > 0 },
                    )
                }
                "failed" -> null
                else -> throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Unexpected <${answer.name}/> after <enable/>")
            }
        }
    }

    private suspend fun authenticate(reader: StanzaReader, transport: XmppTransport, features: Element): SaslMechanism {
        val mechanismsElement = features.child("mechanisms", Ns.SASL)
            ?: throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Server offers no SASL mechanisms")
        val offer = SaslOffer(
            mechanisms = mechanismsElement.children("mechanism").map { it.text.trim() },
            // XEP-0440: only meaningful if the server advertises it.
            channelBindingTypes = features.child("sasl-channel-binding", Ns.SASL_CB)
                ?.children("channel-binding")?.mapNotNull { it.attribute("type") },
        )
        val mechanism = SaslMechanismSelector.select(
            offer = offer,
            credentials = SaslCredentials(config.jid.local!!, config.password, config.scramKeys),
            security = SaslSecurityContext(
                tlsVerified = transport.tls != null,
                channelBindings = transport.channelBindings.map { ChannelBinding(it.type, it.data) },
                channelBindingPinned = config.channelBindingPinned,
            ),
            config = config.sasl,
        )
        reader.send(
            element("auth", Ns.SASL) {
                attribute("mechanism", mechanism.name)
                text(encodeSasl(mechanism.initialResponse()))
            },
        )
        while (true) {
            val reply = reader.nextNonza()
            if (reply.namespace != Ns.SASL) throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Unexpected <${reply.name}/> during SASL")
            when (reply.name) {
                "challenge" -> reader.send(
                    element("response", Ns.SASL) { text(encodeSasl(mechanism.evaluateChallenge(decodeSasl(reply.text)))) },
                )
                "success" -> {
                    mechanism.complete(reply.text.trim().takeIf { it.isNotEmpty() && it != "=" }?.let { decodeSasl(it) })
                    return mechanism
                }
                "failure" -> {
                    val condition = reply.elements.firstOrNull { it.name != "text" }?.name ?: "unknown"
                    val text = reply.child("text")?.text
                    throw XmppNegotiationException(
                        NegotiationFailure.AUTHENTICATION,
                        "Server rejected login ($condition)${text?.let { ": $it" } ?: ""}",
                    )
                }
                else -> throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Unexpected <${reply.name}/> during SASL")
            }
        }
    }

    private suspend fun bind(reader: StanzaReader, features: Element): FullJid {
        features.child("bind", Ns.BIND)
            ?: throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Server does not offer resource binding")
        val result = reader.iq("set", to = null) {
            element("bind", Ns.BIND) { config.resource?.let { r -> element("resource") { text(r) } } }
        }
        val jidText = result.child("bind", Ns.BIND)?.child("jid")?.text?.trim()
            ?: throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Bind result has no JID")
        val jid = runCatching { FullJid.parse(jidText) }.getOrNull()
            ?: throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Server bound an invalid JID '$jidText'")
        if (jid.bare != config.jid) {
            throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Server bound $jid, which is not our account ${config.jid}")
        }
        return jid
    }

    /** RFC 3921 session establishment, still required by some servers unless marked optional (RFC 6121 Appendix E). */
    private suspend fun establishLegacySessionIfRequired(reader: StanzaReader, features: Element) {
        val session = features.child("session", Ns.SESSION) ?: return
        if (session.child("optional") != null) return
        reader.iq("set", to = null) { element("session", Ns.SESSION) }
    }

    private suspend fun discoInfo(reader: StanzaReader, to: String): DiscoInfo = try {
        val result = reader.iq("get", to = to) { element("query", Ns.DISCO_INFO) }
        result.child("query", Ns.DISCO_INFO)?.let { DiscoInfo.parse(it) } ?: DiscoInfo(available = false)
    } catch (e: IqErrorException) {
        DiscoInfo(available = false)
    }

    private fun encodeSasl(data: ByteArray): String = if (data.isEmpty()) "=" else Base64.Default.encode(data)

    private fun decodeSasl(text: String): ByteArray {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == "=") return ByteArray(0)
        return try {
            Base64.Default.decode(trimmed)
        } catch (e: IllegalArgumentException) {
            throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Invalid base64 in SASL payload")
        }
    }
}

internal class IqErrorException(val condition: String) : Exception("IQ error: $condition")

/**
 * Bridges transport bytes to parsed elements during negotiation. Reads only when
 * no element is queued, so nothing is read past what the current step needs.
 */
internal class StanzaReader(
    private val transport: XmppTransport,
    private val config: XmppConfig,
    private val logger: TrafficLogger?,
) : XmppStreamHandler {
    private val parser = XmppStreamParser(this, config.xmlLimits)
    private val events = ArrayDeque<Any>()
    private val skipped = ArrayDeque<Element>()
    private val buffer = ByteArray(16 * 1024)
    private var iqCounter = 0
    private val idPrefix = Base64.UrlSafe.encode(Crypto.randomBytes(6))

    override fun onStreamOpen(header: StreamHeader) {
        events.addLast(header)
    }

    override fun onElement(element: Element) {
        logger?.received(redact(element))
        events.addLast(element)
    }

    override fun onStreamClose() {
        events.addLast(StreamClosed)
    }

    /** Sends a stream header and returns the server's `<stream:features/>`. */
    suspend fun openStream(from: String?): Element {
        val header = buildString {
            append("<?xml version='1.0'?><stream:stream xmlns='jabber:client' xmlns:stream='http://etherx.jabber.org/streams'")
            append(" to='"); XmlWriter.escapeAttribute(config.jid.domain, this); append('\'')
            if (from != null) { append(" from='"); XmlWriter.escapeAttribute(from, this); append('\'') }
            append(" version='1.0' xml:lang='en'>")
        }
        logger?.sent(header)
        transport.write(header.encodeToByteArray())
        val serverHeader = next() as? StreamHeader
            ?: throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Expected a stream header")
        val major = serverHeader.version?.substringBefore('.')?.toIntOrNull()
        if (major == null || major < 1) {
            throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Server does not speak XMPP 1.0 (version='${serverHeader.version}')")
        }
        val features = nextNonza()
        if (features.name != "features" || features.namespace != Ns.STREAMS) {
            throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Expected <stream:features/>, got <${features.name}/>")
        }
        return features
    }

    /** Stream restart after STARTTLS or SASL success: the old XML document is over. */
    fun restart() {
        parser.reset()
        events.clear()
    }

    suspend fun send(element: Element) {
        logger?.sent(redact(element))
        transport.write(XmlWriter.toXml(element, inheritedNamespace = Ns.CLIENT).encodeToByteArray())
    }

    /** Next top-level element, turning `<stream:error/>` and stream close into exceptions. */
    suspend fun nextNonza(): Element {
        val event = next()
        if (event !is Element) throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Unexpected stream event")
        if (event.name == "error" && event.namespace == Ns.STREAMS) {
            val condition = event.elements.firstOrNull { it.namespace == Ns.STREAM_ERRORS && it.name != "text" }?.name ?: "undefined-condition"
            val text = event.child("text", Ns.STREAM_ERRORS)?.text
            throw XmppNegotiationException(
                NegotiationFailure.STREAM_ERROR,
                "Server closed the stream: $condition${text?.let { " ($it)" } ?: ""}",
                streamError = condition,
            )
        }
        return event
    }

    /** Sends an IQ and waits for its result, skipping unrelated stanzas. Throws [IqErrorException] on type='error'. */
    suspend fun iq(type: String, to: String?, payload: ElementBuilder.() -> Unit): Element {
        val id = "$idPrefix-${++iqCounter}"
        send(
            element("iq", Ns.CLIENT) {
                attribute("type", type)
                attribute("id", id)
                attribute("to", to)
                payload()
            },
        )
        while (true) {
            val reply = nextNonza()
            if (reply.name != "iq" || reply.namespace != Ns.CLIENT || reply.attribute("id") != id) {
                unclaimed(reply)
                continue
            }
            when (reply.attribute("type")) {
                "result" -> return reply
                "error" -> throw IqErrorException(
                    reply.child("error")?.elements?.firstOrNull { it.namespace == Ns.STANZAS }?.name ?: "undefined-condition",
                )
                else -> throw XmppNegotiationException(NegotiationFailure.PROTOCOL, "Invalid IQ response type")
            }
        }
    }

    /** Keeps a stanza that arrived while negotiation waited for something else, for the session to deliver. */
    fun unclaimed(element: Element) {
        if (StreamManagement.isStanza(element)) skipped.addLast(element)
    }

    /** Stanzas kept by [unclaimed], in arrival order. */
    fun takeUnclaimed(): List<Element> = skipped.toList().also { skipped.clear() }

    /**
     * Next element on a live session, with no timeout (liveness is the supervisor's
     * probe). Returns null when the server closes the stream; a stream error throws.
     */
    suspend fun nextLive(): Element? {
        while (events.isEmpty()) {
            val n = transport.read(buffer)
            if (n < 0) throw XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, "Server closed the connection")
            parser.feed(buffer, 0, n)
        }
        if (events.first() === StreamClosed) {
            events.removeFirst()
            return null
        }
        return nextNonza()
    }

    /**
     * Waits for the next parser event. Transport reads may block in a way coroutine
     * cancellation cannot interrupt, so the timeout closes the transport, which
     * unblocks the read.
     */
    private suspend fun next(): Any {
        if (events.isEmpty()) {
            var timedOut = false
            try {
                coroutineScope {
                    val watchdog = launch {
                        delay(config.responseTimeoutMillis)
                        timedOut = true
                        transport.close()
                    }
                    try {
                        while (events.isEmpty()) {
                            val n = transport.read(buffer)
                            if (n < 0) throw XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, "Server closed the connection")
                            parser.feed(buffer, 0, n)
                        }
                    } finally {
                        watchdog.cancel()
                    }
                }
            } catch (e: Exception) {
                if (timedOut) {
                    throw XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, "No response within ${config.responseTimeoutMillis} ms")
                }
                throw e
            }
        }
        val event = events.removeFirst()
        if (event === StreamClosed) {
            throw XmppNegotiationException(NegotiationFailure.CONNECTION_LOST, "Server closed the stream")
        }
        return event
    }

    /** SASL elements carry password-derived data: log their names only. */
    private fun redact(element: Element): String =
        if (element.namespace == Ns.SASL && element.name != "mechanisms") {
            XmlWriter.toXml(element.copy(children = listOf(Text("[redacted]"))), Ns.CLIENT)
        } else {
            XmlWriter.toXml(element, Ns.CLIENT)
        }

    private object StreamClosed
}
