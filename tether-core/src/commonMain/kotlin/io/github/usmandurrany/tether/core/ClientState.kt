package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.XmlParse
import io.github.usmandurrany.tether.xml.XmlWriter
import io.github.usmandurrany.tether.xml.element

/**
 * What [XmppClient] saves so a restarted app loses nothing: the stanzas not yet
 * confirmed, the outbox, the XEP-0198 session to resume, and the keepalive
 * timing learned per network. Stored as one XML document.
 */
internal class ClientState(
    val resume: SavedResume?,
    /** Unconfirmed stanzas of a session that cannot be resumed, then the outbox, oldest first. */
    val unsent: List<Element>,
    val keepalive: Map<String, Pair<Long, Long>>,
) {
    class SavedResume(
        val jid: FullJid,
        val snapshot: SmSnapshot,
        val streamFeatures: Element,
        val serverFeatures: Set<String>,
        val accountFeatures: Set<String>,
    ) {
        fun toRequest(): ResumeRequest {
            val sm = StreamManagement(snapshot.resumptionId, snapshot.location, snapshot.maxResumeSeconds)
            sm.restoreCounters(snapshot.inbound, snapshot.acknowledged)
            snapshot.pending.forEach { sm.onSent(it) }
            val capabilities = ServerCapabilities(
                streamFeatures,
                DiscoInfo(true, features = serverFeatures),
                DiscoInfo(true, features = accountFeatures),
            )
            return ResumeRequest(sm, jid, capabilities)
        }
    }

    fun encode(): String = XmlWriter.toXml(
        element("state", NS) {
            resume?.let { r ->
                element("resume") {
                    attribute("id", r.snapshot.resumptionId)
                    attribute("location", r.snapshot.location)
                    attribute("max", r.snapshot.maxResumeSeconds?.toString())
                    attribute("inbound", r.snapshot.inbound.toString())
                    attribute("acked", r.snapshot.acknowledged.toString())
                    attribute("jid", r.jid.toString())
                    element("features") { child(r.streamFeatures) }
                    element("server") { r.serverFeatures.forEach { f -> element("feature") { attribute("var", f) } } }
                    element("account") { r.accountFeatures.forEach { f -> element("feature") { attribute("var", f) } } }
                    element("pending") { r.snapshot.pending.forEach { child(it) } }
                }
            }
            element("unsent") { unsent.forEach { child(it) } }
            element("keepalive") {
                keepalive.forEach { (kind, value) ->
                    element("network") {
                        // The "no network reported" key is not valid XML text.
                        if (kind == AdaptiveKeepalive.UNKNOWN) attribute("unknown", "true") else attribute("kind", kind)
                        attribute("interval", value.first.toString())
                        attribute("ceiling", value.second.toString())
                    }
                }
            }
        },
    )

    companion object {
        const val NS = "urn:tether:state:0"

        /** Null for missing or unreadable data: a broken save must never stop the app from connecting. */
        fun decode(text: String): ClientState? = runCatching {
            val root = XmlParse.element(text)
            require(root.name == "state" && root.namespace == NS)
            val resume = root.child("resume", NS)?.let { r ->
                SavedResume(
                    jid = FullJid.parse(r.attribute("jid")!!),
                    snapshot = SmSnapshot(
                        resumptionId = r.attribute("id")!!,
                        location = r.attribute("location"),
                        maxResumeSeconds = r.attribute("max")?.toInt(),
                        inbound = StreamManagement.parseH(r.attribute("inbound"))!!,
                        acknowledged = StreamManagement.parseH(r.attribute("acked"))!!,
                        pending = r.child("pending", NS)!!.elements.filter(StreamManagement::isStanza),
                    ),
                    streamFeatures = r.child("features", NS)!!.elements.single(),
                    serverFeatures = features(r.child("server", NS)),
                    accountFeatures = features(r.child("account", NS)),
                )
            }
            ClientState(
                resume = resume,
                unsent = root.child("unsent", NS)?.elements.orEmpty().filter(StreamManagement::isStanza),
                keepalive = root.child("keepalive", NS)?.children("network", NS).orEmpty().associate {
                    val kind = if (it.attribute("unknown") == "true") AdaptiveKeepalive.UNKNOWN else it.attribute("kind")!!
                    kind to (it.attribute("interval")!!.toLong() to it.attribute("ceiling")!!.toLong())
                },
            )
        }.getOrNull()

        private fun features(e: Element?): Set<String> =
            e?.children("feature", NS).orEmpty().mapNotNull { it.attribute("var") }.toSet()
    }
}
