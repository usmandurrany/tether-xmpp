package io.github.usmandurrany.tether.im

import io.github.usmandurrany.tether.core.DiscoInfo
import io.github.usmandurrany.tether.core.FullJid
import io.github.usmandurrany.tether.core.ServerCapabilities
import io.github.usmandurrany.tether.core.SessionContext
import io.github.usmandurrany.tether.core.StanzaSender
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.XmppStreamHandler
import io.github.usmandurrany.tether.xml.XmppStreamParser
import io.github.usmandurrany.tether.xml.StreamHeader
import io.github.usmandurrany.tether.xml.element

/** Parses one stanza written as XML in the client namespace. */
internal fun xml(text: String): Element {
    var result: Element? = null
    val parser = XmppStreamParser(object : XmppStreamHandler {
        override fun onStreamOpen(header: StreamHeader) {}
        override fun onElement(element: Element) { result = element }
        override fun onStreamClose() {}
    })
    val doc = "<stream:stream xmlns='jabber:client' xmlns:stream='http://etherx.jabber.org/streams'>$text"
    val bytes = doc.encodeToByteArray()
    parser.feed(bytes, 0, bytes.size)
    return result ?: error("no element in $text")
}

/** Records what is sent and answers requests with [answer]. */
internal class FakeSender(var answer: suspend (Element) -> Element = { result(it) }) : StanzaSender {
    val sent = ArrayList<Element>()
    val requests = ArrayList<Element>()

    override suspend fun send(stanza: Element) {
        sent += stanza
    }

    override suspend fun request(iq: Element, timeoutMillis: Long): Element {
        requests += iq
        return answer(iq)
    }

    companion object {
        fun result(iq: Element, block: io.github.usmandurrany.tether.xml.ElementBuilder.() -> Unit = {}): Element =
            element("iq", ImNs.CLIENT) {
                attribute("type", "result")
                attribute("id", iq.attribute("id"))
                block()
            }

        fun error(iq: Element, condition: String = "service-unavailable"): Element = element("iq", ImNs.CLIENT) {
            attribute("type", "error")
            attribute("id", iq.attribute("id"))
            element("error") {
                attribute("type", "cancel")
                element(condition, ImNs.STANZAS)
            }
        }
    }
}

internal class FakeSession(
    private val sender: FakeSender,
    override val resumed: Boolean = false,
    serverFeatures: Set<String> = emptySet(),
    accountFeatures: Set<String> = emptySet(),
    streamFeatures: Element = element("features", "http://etherx.jabber.org/streams"),
    override val jid: FullJid = FullJid.parse("juliet@example.com/phone"),
) : SessionContext, StanzaSender by sender {
    override val capabilities = ServerCapabilities(
        streamFeatures = streamFeatures,
        server = DiscoInfo(true, features = serverFeatures),
        account = DiscoInfo(true, features = accountFeatures),
    )
}
