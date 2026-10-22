package io.github.usmandurrany.tether.core

import io.github.usmandurrany.tether.crypto.Crypto
import io.github.usmandurrany.tether.crypto.HashAlgorithm
import io.github.usmandurrany.tether.crypto.pbkdf2
import io.github.usmandurrany.tether.xml.Element
import io.github.usmandurrany.tether.xml.StreamHeader
import io.github.usmandurrany.tether.xml.XmppStreamHandler
import io.github.usmandurrany.tether.xml.XmppStreamParser
import org.conscrypt.Conscrypt
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64

/** A self-signed certificate made with the JDK's keytool, shared by all tests. */
internal object TestCertificate {
    val keyStore: KeyStore by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "tether-test-${System.nanoTime()}").apply { mkdirs() }
        val file = File(dir, "server.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA", "-dname", "CN=localhost", "-validity", "2",
            "-ext", "SAN=dns:localhost", "-keystore", file.path, "-storetype", "PKCS12",
            "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool failed: $output" }
        KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "changeit".toCharArray()) } }
    }

    val der: ByteArray get() = keyStore.getCertificate("server").encoded

    val sha256Hex: String get() = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    val sslContext: SSLContext by lazy {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "changeit".toCharArray())
        SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    val conscryptContext: SSLContext by lazy {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "changeit".toCharArray())
        SSLContext.getInstance("TLS", Conscrypt.newProvider()).apply { init(kmf.keyManagers, null, null) }
    }
}

/**
 * A scripted XMPP server for tests. It speaks just enough RFC 6120 to exercise
 * the client: STARTTLS, SCRAM-SHA-256(-PLUS) verified independently of the
 * client code, bind, session and disco#info. Behaviour switches simulate
 * misbehaving or hostile servers.
 */
internal class FakeXmppServer(
    private val password: String = "secret",
    val offerStartTls: Boolean = true,
    val mechanisms: List<String> = listOf("SCRAM-SHA-256", "SCRAM-SHA-256-PLUS"),
    val advertiseChannelBinding: Boolean = true,
    val rejectLogin: Boolean = false,
    val streamErrorAfterAuth: String? = null,
    val requireSession: Boolean = false,
    val bindJid: String = "juliet@localhost/phone",
    /** Advertise XEP-0198 after login. */
    val streamManagement: Boolean = false,
    /** Accept `<resume/>` for the id this server handed out. */
    @Volatile var allowResume: Boolean = true,
    /** Ignore `<r/>` and pings, like a server that has silently gone away. */
    @Volatile var silent: Boolean = false,
    /** Pretend this many of the client's last stanzas were lost in transit when it resumes. */
    @Volatile var lostInTransit: Int = 0,
    /** Serve TLS through Conscrypt and offer `tls-exporter` channel binding. */
    val conscrypt: Boolean = false,
    /** Offer SASL2 (XEP-0388) next to classic SASL. */
    val sasl2: Boolean = false,
    /** FAST (XEP-0484) mechanisms offered inline in SASL2; empty: no FAST. */
    val fastMechanisms: List<String> = emptyList(),
    /** Refuse every FAST token, as after the server dropped them. */
    @Volatile var rejectFast: Boolean = false,
    /** Hand out a new token on each FAST login, as when a token is close to expiry. */
    @Volatile var rotateTokens: Boolean = false,
) : AutoCloseable {
    /** Valid FAST tokens: token to (mechanism, user-agent id). */
    val tokens = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    /** Every SASL2 `<authenticate/>` the client sent. */
    val authenticates = LinkedBlockingQueue<Element>()
    @Volatile var fastLogins = 0
    @Volatile var lastStreamFrom: String? = null
    private var tokenCounter = 0
    /** Sent just before the answer to an IQ addressed to the account, like archive results before `<fin/>`. */
    @Volatile var beforeAccountAnswer: List<String> = emptyList()

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val failures = LinkedBlockingQueue<Throwable>()

    /** Mechanism and GS2 header the client used, for assertions. */
    @Volatile var usedMechanism: String? = null
    @Volatile var usedGs2Header: String? = null

    /** Stanzas the client sent after login, in order. */
    val fromClient = LinkedBlockingQueue<Element>()
    /** `h` values from the client's `<a/>` answers. */
    val clientAcks = LinkedBlockingQueue<Long>()
    @Volatile var connections = 0
    @Volatile var resumes = 0
    @Volatile var resumeAttempts = 0

    /** True after the client sent CSI `<inactive/>` (XEP-0352), until `<active/>`. */
    @Volatile var csiInactive = false
    private val held = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile var clientClosedStream = false
    @Volatile private var smSessionEnded = false

    // Server-side SM state; survives a dropped connection, as on a real server.
    @Volatile private var smInbound = 0L
    @Volatile private var current: Conn? = null
    private val smId = "sm-${System.nanoTime()}"

    private val worker = thread(isDaemon = true, name = "fake-xmpp") {
        while (!server.isClosed) {
            try {
                server.accept().use {
                    connections++
                    serve(it)
                }
            } catch (t: Throwable) {
                if (!server.isClosed && current?.dropped != true) failures += t
            }
        }
    }

    /** Sends raw XML to the connected client. */
    fun push(xml: String) {
        current!!.send(xml)
    }

    /**
     * Sends raw XML to the client, or holds it while the client is inactive,
     * as MongooseIM's CSI buffer does; `<active/>` sends what was held.
     */
    fun deliver(xml: String) {
        if (csiInactive) held += xml else push(xml)
    }

    /** Cuts the TCP connection without a stream close, like a dead mobile link. */
    fun dropConnection() {
        current?.let {
            it.dropped = true
            it.socket.close()
        }
    }

    override fun close() {
        server.close()
        current?.socket?.close()
        worker.join(5000)
    }

    private class Conn(var socket: Socket) {
        @Volatile var dropped = false
        var ssl: SSLSocket? = null
        var input: InputStream = socket.getInputStream()
        var output: OutputStream = socket.getOutputStream()
        val events = LinkedBlockingQueue<Any>()
        var parser = newParser()

        fun newParser() = XmppStreamParser(object : XmppStreamHandler {
            override fun onStreamOpen(header: StreamHeader) { events += header }
            override fun onElement(element: Element) { events += element }
            override fun onStreamClose() { events += "closed" }
        })

        fun next(): Any {
            val buffer = ByteArray(8192)
            while (events.isEmpty()) {
                val n = input.read(buffer)
                if (n < 0) return "eof"
                parser.feed(buffer, 0, n)
            }
            return events.take()
        }

        fun element(): Element = next() as Element

        fun send(xml: String) {
            output.write(xml.encodeToByteArray())
            output.flush()
        }

        fun restart() {
            parser = newParser()
            events.clear()
        }
    }

    private fun serve(socket: Socket) {
        val c = Conn(socket)
        current = c
        // Stream 1: plaintext.
        check(c.next() is StreamHeader)
        c.send(header())
        if (!offerStartTls) {
            c.send("<stream:features><mechanisms xmlns='urn:ietf:params:xml:ns:xmpp-sasl'><mechanism>PLAIN</mechanism></mechanisms></stream:features>")
            c.next() // the client should hang up; anything else is a failure seen by the test
            return
        }
        c.send("<stream:features><starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'><required/></starttls></stream:features>")
        check(c.element().name == "starttls")
        c.send("<proceed xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>")
        val context = if (conscrypt) TestCertificate.conscryptContext else TestCertificate.sslContext
        val ssl = context.socketFactory.createSocket(socket, null, socket.port, true) as SSLSocket
        ssl.useClientMode = false
        ssl.startHandshake()
        c.socket = ssl
        c.ssl = ssl
        c.input = ssl.inputStream
        c.output = ssl.outputStream
        c.restart()

        // Stream 2: TLS, authenticate.
        lastStreamFrom = (c.next() as StreamHeader).from
        c.send(header())
        val mechs = mechanisms.joinToString("") { "<mechanism>$it</mechanism>" }
        val types = if (conscrypt) "<channel-binding type='tls-exporter'/><channel-binding type='tls-server-end-point'/>" else "<channel-binding type='tls-server-end-point'/>"
        val cb = if (advertiseChannelBinding) "<sasl-channel-binding xmlns='urn:xmpp:sasl-cb:0'>$types</sasl-channel-binding>" else ""
        val fast = if (fastMechanisms.isEmpty()) "" else
            "<inline><fast xmlns='urn:xmpp:fast:0'>${fastMechanisms.joinToString("") { "<mechanism>$it</mechanism>" }}</fast></inline>"
        val sasl2Feature = if (sasl2) "<authentication xmlns='urn:xmpp:sasl:2'>$mechs$fast</authentication>" else ""
        c.send("<stream:features><mechanisms xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>$mechs</mechanisms>$cb$sasl2Feature</stream:features>")
        val first = c.element()
        if (first.name == "authenticate") {
            if (!sasl2Login(c, first)) return
            // No stream restart after SASL2: the features follow at once.
        } else {
            val auth = first
            check(auth.name == "auth")
            usedMechanism = auth.attribute("mechanism")
            if (rejectLogin) {
                c.send("<failure xmlns='urn:ietf:params:xml:ns:xmpp-sasl'><not-authorized/><text>bad password</text></failure>")
                c.next()
                return
            }
            val serverFinal = scram(c, Base64.Default.decode(auth.text), SASL) ?: return
            c.send("<success xmlns='$SASL'>${Base64.Default.encode(serverFinal)}</success>")
            c.restart()

            // Stream 3: authenticated.
            check(c.next() is StreamHeader)
            c.send(header())
        }
        if (streamErrorAfterAuth != null) {
            c.send("<stream:error><$streamErrorAfterAuth xmlns='urn:ietf:params:xml:ns:xmpp-streams'/></stream:error></stream:stream>")
            return
        }
        val session = if (requireSession) "<session xmlns='urn:ietf:params:xml:ns:xmpp-session'/>" else ""
        val sm = if (streamManagement) "<sm xmlns='urn:xmpp:sm:3'/>" else ""
        c.send("<stream:features><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'/>$session$sm<csi xmlns='urn:xmpp:csi:0'/></stream:features>")
        var smOn = false
        while (true) {
            val event = c.next()
            if (event == "closed") {
                // A clean close ends the session for good: it can no longer be resumed.
                clientClosedStream = true
                smSessionEnded = true
                c.send("</stream:stream>")
                return
            }
            val el = (event as? Element) ?: return
            val id = el.attribute("id")
            if (el.namespace == "urn:xmpp:sm:3") {
                when (el.name) {
                    "enable" -> {
                        smOn = true
                        smSessionEnded = false
                        smInbound = 0
                        c.send("<enabled xmlns='urn:xmpp:sm:3' id='$smId' resume='true' max='300'/>")
                    }
                    "resume" -> {
                        resumeAttempts++
                        smInbound -= lostInTransit
                        if (allowResume && !smSessionEnded && el.attribute("previd") == smId) {
                            smOn = true
                            resumes++
                            c.send("<resumed xmlns='urn:xmpp:sm:3' previd='$smId' h='$smInbound'/>")
                        } else {
                            c.send("<failed xmlns='urn:xmpp:sm:3' h='$smInbound'><item-not-found xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></failed>")
                        }
                    }
                    "r" -> if (!silent) c.send("<a xmlns='urn:xmpp:sm:3' h='$smInbound'/>")
                    "a" -> clientAcks += el.attribute("h")!!.toLong()
                }
                continue
            }
            if (el.namespace == "urn:xmpp:csi:0") {
                csiInactive = el.name == "inactive"
                if (!csiInactive) while (true) c.send(held.poll() ?: break)
                fromClient += el
                continue
            }
            if (smOn && el.namespace == "jabber:client") smInbound++
            when {
                el.child("bind", "urn:ietf:params:xml:ns:xmpp-bind") != null ->
                    c.send("<iq type='result' id='$id'><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'><jid>$bindJid</jid></bind></iq>")
                el.child("session", "urn:ietf:params:xml:ns:xmpp-session") != null -> c.send("<iq type='result' id='$id'/>")
                el.child("ping", "urn:xmpp:ping") != null -> if (!silent) c.send("<iq type='result' id='$id' from='localhost'/>")
                el.name == "iq" && el.child("query", "http://jabber.org/protocol/disco#info") != null && el.attribute("to") == "localhost" -> {
                    // An unrelated stanza first, which the client must keep for the app.
                    c.send("<message from='localhost'><body>welcome</body></message>")
                    c.send(
                        "<iq type='result' id='$id' from='localhost'><query xmlns='http://jabber.org/protocol/disco#info'>" +
                            "<identity category='server' type='im' name='Fake'/><feature var='urn:xmpp:carbons:2'/>" +
                            "<feature var='urn:xmpp:ping'/></query></iq>",
                    )
                }
                el.name == "iq" && el.attribute("to") == "juliet@localhost" -> {
                    beforeAccountAnswer.forEach { c.send(it) }
                    c.send(
                        "<iq type='error' id='$id' from='juliet@localhost'><error type='cancel'>" +
                            "<service-unavailable xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></error></iq>",
                    )
                }
                el.name == "iq" && el.attribute("type") == "result" -> fromClient += el
                else -> fromClient += el
            }
        }
    }

    /**
     * SASL2 login, retried on the same stream after a `<failure/>`, as real
     * servers allow. Returns false when the client went away.
     */
    private fun sasl2Login(c: Conn, first: Element): Boolean {
        var next: Element? = first
        while (true) {
            val auth = next ?: (c.next() as? Element ?: return false)
            next = null
            check(auth.name == "authenticate" && auth.namespace == SASL2) { "expected <authenticate/>, got $auth" }
            authenticates += auth
            val mechanism = auth.attribute("mechanism")!!
            usedMechanism = mechanism
            val agent = auth.child("user-agent")?.attribute("id").orEmpty()
            val initial = Base64.Default.decode(auth.child("initial-response")!!.text)
            val additional: ByteArray
            var token: String? = null
            if (mechanism.startsWith("HT-")) {
                check(auth.child("fast", FAST) != null) { "a token login must carry <fast/>" }
                val split = initial.indexOf(0)
                check(initial.copyOfRange(0, split).decodeToString() == "juliet")
                val proof = initial.copyOfRange(split + 1, initial.size)
                val cb = htBinding(c, mechanism)
                val match = tokens.entries.firstOrNull { (t, owner) ->
                    owner == (mechanism to agent) &&
                        Crypto.hmac(HashAlgorithm.SHA256, t.encodeToByteArray(), "Initiator".encodeToByteArray() + cb).contentEquals(proof)
                }
                if (rejectFast || match == null) {
                    c.send("<failure xmlns='$SASL2'><credentials-expired xmlns='$SASL'/><text>token not accepted</text></failure>")
                    continue
                }
                fastLogins++
                additional = Crypto.hmac(HashAlgorithm.SHA256, match.key.encodeToByteArray(), "Responder".encodeToByteArray() + cb)
                if (rotateTokens) token = issue(mechanism, agent)
            } else {
                if (rejectLogin) {
                    c.send("<failure xmlns='$SASL2'><not-authorized xmlns='$SASL'/><text>bad password</text></failure>")
                    continue
                }
                additional = scram(c, initial, SASL2) ?: return false
                auth.child("request-token", FAST)?.attribute("mechanism")?.let { requested ->
                    check(requested in fastMechanisms) { "token requested for $requested, which was not offered" }
                    token = issue(requested, agent)
                }
            }
            val tokenXml = token?.let { "<token xmlns='$FAST' expiry='2030-01-01T00:00:00Z' token='$it'/>" }.orEmpty()
            c.send(
                "<success xmlns='$SASL2'><additional-data>${Base64.Default.encode(additional)}</additional-data>" +
                    "<authorization-identifier>juliet@localhost</authorization-identifier>$tokenXml</success>",
            )
            return true
        }
    }

    private fun issue(mechanism: String, agent: String): String {
        // XEP-0484: a new token replaces the client's older ones.
        tokens.entries.removeIf { it.value.second == agent }
        val token = "token-${++tokenCounter}-${System.nanoTime()}"
        tokens[token] = mechanism to agent
        return token
    }

    private fun htBinding(c: Conn, mechanism: String): ByteArray = when {
        mechanism.endsWith("-ENDP") -> MessageDigest.getInstance("SHA-256").digest(TestCertificate.der)
        mechanism.endsWith("-EXPR") -> Conscrypt.exportKeyingMaterial(c.ssl!!, "EXPORTER-Channel-Binding", null, 32)
        else -> ByteArray(0)
    }

    /**
     * SCRAM-SHA-256 server side, from RFC 5802, independent of the client
     * implementation. Returns the server-final message, or null after sending a failure.
     */
    private fun scram(c: Conn, clientFirst: ByteArray, ns: String): ByteArray? {
        val text = clientFirst.decodeToString()
        val gs2 = text.substring(0, text.indexOf(',', text.indexOf(',') + 1) + 1)
        usedGs2Header = gs2
        val bare = text.substring(gs2.length)
        val clientNonce = bare.substringAfter("r=")
        val salt = "fake-salt-1234".encodeToByteArray()
        val serverFirst = "r=${clientNonce}srv,s=${Base64.Default.encode(salt)},i=4096"
        c.send("<challenge xmlns='$ns'>${Base64.Default.encode(serverFirst.encodeToByteArray())}</challenge>")

        val final = Base64.Default.decode(c.element().text).decodeToString()
        val withoutProof = final.substringBefore(",p=")
        val cbData = Base64.Default.decode(withoutProof.substringAfter("c=").substringBefore(","))
        val expectedBinding = when {
            gs2.startsWith("p=tls-server-end-point") -> MessageDigest.getInstance("SHA-256").digest(TestCertificate.der)
            gs2.startsWith("p=tls-exporter") -> Conscrypt.exportKeyingMaterial(c.ssl!!, "EXPORTER-Channel-Binding", null, 32)
            else -> ByteArray(0)
        }
        check(cbData.contentEquals(gs2.encodeToByteArray() + expectedBinding)) { "channel binding mismatch" }

        val h = HashAlgorithm.SHA256
        val salted = Crypto.pbkdf2(h, password.encodeToByteArray(), salt, 4096)
        val clientKey = Crypto.hmac(h, salted, "Client Key".encodeToByteArray())
        val storedKey = Crypto.hash(h, clientKey)
        val authMessage = "$bare,$serverFirst,$withoutProof".encodeToByteArray()
        val signature = Crypto.hmac(h, storedKey, authMessage)
        val proof = Base64.Default.decode(final.substringAfter(",p="))
        val recovered = ByteArray(proof.size) { (proof[it].toInt() xor signature[it].toInt()).toByte() }
        if (!Crypto.hash(h, recovered).contentEquals(storedKey)) {
            c.send("<failure xmlns='$ns'><not-authorized${if (ns == SASL) "" else " xmlns='$SASL'"}/></failure>")
            error("client proof invalid")
        }
        val serverSignature = Crypto.hmac(h, Crypto.hmac(h, salted, "Server Key".encodeToByteArray()), authMessage)
        return "v=${Base64.Default.encode(serverSignature)}".encodeToByteArray()
    }

    private companion object {
        const val SASL = "urn:ietf:params:xml:ns:xmpp-sasl"
        const val SASL2 = "urn:xmpp:sasl:2"
        const val FAST = "urn:xmpp:fast:0"
    }

    private fun header() =
        "<?xml version='1.0'?><stream:stream xmlns='jabber:client' xmlns:stream='http://etherx.jabber.org/streams' " +
            "from='localhost' id='${System.nanoTime()}' version='1.0' xml:lang='en'>"
}
