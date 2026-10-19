package io.github.usmandurrany.tether.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DnsSrvTest {
    private val name = "_xmpp-client._tcp.example.com"

    /** A response: the query echoed back, plus answers whose names point at the question (compression). */
    private fun response(
        id: Int = 0x1234,
        flags: Int = 0x8180,
        answers: List<ByteArray> = emptyList(),
        question: String = name,
    ): ByteArray {
        val query = DnsSrv.query(question, id)
        val header = query.copyOf(12)
        header[2] = (flags ushr 8).toByte()
        header[3] = flags.toByte()
        header[7] = answers.size.toByte()
        return header + query.copyOfRange(12, query.size) + answers.fold(ByteArray(0)) { a, b -> a + b }
    }

    private fun srv(priority: Int, weight: Int, port: Int, target: ByteArray, type: Int = 33): ByteArray {
        val rdata = u16(priority) + u16(weight) + u16(port) + target
        return byteArrayOf(0xC0.toByte(), 12) + u16(type) + u16(1) + byteArrayOf(0, 0, 0x0E, 0x10) + u16(rdata.size) + rdata
    }

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun label(vararg parts: String): ByteArray =
        parts.fold(ByteArray(0)) { acc, p -> acc + byteArrayOf(p.length.toByte()) + p.encodeToByteArray() } + byteArrayOf(0)

    @Test
    fun parsesRecordsWithCompressedNames() {
        // The second target is "xmpp2" followed by a pointer to "example.com" inside the question.
        val pointerToDomain = byteArrayOf(5) + "xmpp2".encodeToByteArray() + byteArrayOf(0xC0.toByte(), (12 + 1 + 12 + 1 + 4).toByte())
        val answer = DnsSrv.parse(
            response(answers = listOf(srv(10, 5, 5222, label("xmpp", "example", "com")), srv(20, 0, 5223, pointerToDomain))),
            0x1234,
            name,
        )
        assertEquals(
            DnsSrv.Answer.Records(
                listOf(SrvRecord(10, 5, 5222, "xmpp.example.com"), SrvRecord(20, 0, 5223, "xmpp2.example.com")),
            ),
            answer,
        )
    }

    @Test
    fun ignoresOtherRecordTypes() {
        val cname = srv(0, 0, 0, label("x"), type = 5)
        assertEquals(DnsSrv.Answer.Records(emptyList()), DnsSrv.parse(response(answers = listOf(cname)), 0x1234, name))
    }

    @Test
    fun aDotTargetMeansNoService() {
        val answer = DnsSrv.parse(response(answers = listOf(srv(0, 0, 0, byteArrayOf(0)))), 0x1234, name)
        assertEquals(DnsSrv.Answer.Records(listOf(SrvRecord(0, 0, 0, "."))), answer)
    }

    @Test
    fun rejectsAnswersToSomeoneElsesQuery() {
        assertNull(DnsSrv.parse(response(id = 0x4321), 0x1234, name), "wrong id")
        assertNull(DnsSrv.parse(response(question = "_xmpp-client._tcp.evil.com"), 0x1234, name), "wrong question")
        assertNull(DnsSrv.parse(response(flags = 0x0100), 0x1234, name), "a query, not a response")
    }

    @Test
    fun reportsNxdomainTruncationAndFailures() {
        assertEquals(DnsSrv.Answer.NoSuchName, DnsSrv.parse(response(flags = 0x8183), 0x1234, name))
        assertEquals(DnsSrv.Answer.Truncated, DnsSrv.parse(response(flags = 0x8380), 0x1234, name))
        assertEquals(DnsSrv.Answer.ServerFailure(2), DnsSrv.parse(response(flags = 0x8182), 0x1234, name))
    }

    @Test
    fun malformedOrHostileMessagesAreRejectedSafely() {
        val good = response(answers = listOf(srv(10, 5, 5222, label("xmpp", "example", "com"))))
        for (cut in 0 until good.size - 1) assertNull(DnsSrv.parse(good.copyOf(cut), 0x1234, name), "cut at $cut")
        // A target that points at itself.
        val loopAt = response().size + 18
        val loop = response(answers = listOf(srv(1, 1, 5222, byteArrayOf(0xC0.toByte(), loopAt.toByte()))))
        assertNull(DnsSrv.parse(loop, 0x1234, name))
    }

    @Test
    fun queryIsWellFormed() {
        val q = DnsSrv.query("a.bc", 0xBEEF)
        assertEquals(
            listOf(0xBE, 0xEF, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0, 1, 'a'.code, 2, 'b'.code, 'c'.code, 0, 0, 33, 0, 1),
            q.map { it.toInt() and 0xFF },
        )
    }
}
