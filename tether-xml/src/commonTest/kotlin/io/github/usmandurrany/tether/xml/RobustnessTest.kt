package io.github.usmandurrany.tether.xml

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Cheap, deterministic fuzzing: random and mutated input must only ever produce
 * results or [XmlStreamException], never another exception type or a hang.
 * Coverage-guided fuzzing (Jazzer) is planned on top of this.
 */
class RobustnessTest {

    private val seedStanzas = listOf(
        "<message to='a@b' id='1'><body>hi &amp; bye</body></message>",
        "<iq type='set' id='2'><query xmlns='jabber:iq:roster'><item jid='x@y' name='X'/></query></iq>",
        "<stream:features><mechanisms xmlns='urn:ietf:params:xml:ns:xmpp-sasl'><mechanism>SCRAM-SHA-256</mechanism></mechanisms></stream:features>",
        "<r xmlns='urn:xmpp:sm:3'/>",
        "<message><body><![CDATA[x]]></body><x:y xmlns:x='urn:x' x:a='&#x1F600;'/></message>",
    )

    @Test
    fun randomBytesOnlyCauseStreamErrors() {
        val random = Random(42)
        repeat(3_000) {
            val bytes = random.nextBytes(random.nextInt(1, 200))
            expectOnlyStreamErrors(HEADER.encodeToByteArray() + bytes)
        }
    }

    @Test
    fun mutatedStanzasOnlyCauseStreamErrors() {
        val random = Random(7)
        val interesting = "<>/&;'\"=:![]?-x é".encodeToByteArray()
        repeat(10_000) {
            val bytes = (HEADER + seedStanzas.random(random)).encodeToByteArray()
            repeat(random.nextInt(1, 4)) {
                val i = random.nextInt(HEADER.length, bytes.size)
                bytes[i] = if (random.nextBoolean()) random.nextInt(256).toByte() else interesting.random(random)
            }
            expectOnlyStreamErrors(bytes, chunkSize = random.nextInt(1, 16))
        }
    }

    private fun expectOnlyStreamErrors(bytes: ByteArray, chunkSize: Int = bytes.size) {
        try {
            parse(bytes, chunkSize)
        } catch (_: XmlStreamException) {
            // expected for most inputs
        } catch (t: Throwable) {
            fail("Unexpected ${t::class.simpleName} for input: ${bytes.decodeToString()}", t)
        }
    }
}
