package io.github.usmandurrany.tether.transport

/**
 * DNS wire format for SRV lookups (RFC 1035, RFC 2782), for platforms without a
 * system SRV API (Android). Only what an SRV query needs: one
 * question, and answers with name compression.
 */
public object DnsSrv {
    private const val TYPE_SRV = 33
    private const val CLASS_IN = 1

    /** The outcome of one response. */
    public sealed interface Answer {
        public data class Records(val records: List<SrvRecord>) : Answer

        /** The name does not exist (NXDOMAIN): a definite "no records". */
        public data object NoSuchName : Answer

        /** Too big for UDP; ask again over TCP. */
        public data object Truncated : Answer

        /** The server failed or refused; try the next server. */
        public data class ServerFailure(val rcode: Int) : Answer
    }

    /** Builds a recursive query for the SRV records of [name]. */
    public fun query(name: String, id: Int): ByteArray {
        require(id in 0..0xFFFF)
        val out = ArrayList<Byte>(32 + name.length)
        out.u16(id)
        out.u16(0x0100) // standard query, recursion desired
        out.u16(1) // one question
        out.u16(0)
        out.u16(0)
        out.u16(0)
        writeName(out, name)
        out.u16(TYPE_SRV)
        out.u16(CLASS_IN)
        return out.toByteArray()
    }

    /**
     * Parses a response to [query]. Returns null when the message is not an
     * answer to that query (wrong id or question), which a client must ignore:
     * accepting it would let a spoofed packet redirect the connection.
     */
    public fun parse(response: ByteArray, id: Int, name: String): Answer? {
        val r = Reader(response)
        try {
            if (r.u16() != id) return null
            val flags = r.u16()
            if (flags and 0x8000 == 0) return null // not a response
            val questions = r.u16()
            val answers = r.u16()
            r.u16() // authority
            r.u16() // additional
            if (questions != 1) return null
            val asked = r.name()
            val qtype = r.u16()
            val qclass = r.u16()
            if (!asked.equals(name.trimEnd('.'), ignoreCase = true) || qtype != TYPE_SRV || qclass != CLASS_IN) return null
            if (flags and 0x0200 != 0) return Answer.Truncated
            when (val rcode = flags and 0x000F) {
                0 -> Unit
                3 -> return Answer.NoSuchName
                else -> return Answer.ServerFailure(rcode)
            }
            val records = ArrayList<SrvRecord>()
            repeat(answers) {
                r.name() // owner: may be a CNAME target, so not compared
                val type = r.u16()
                val klass = r.u16()
                r.skip(4) // ttl
                val length = r.u16()
                val end = r.position + length
                if (type == TYPE_SRV && klass == CLASS_IN) {
                    val priority = r.u16()
                    val weight = r.u16()
                    val port = r.u16()
                    val target = r.name().ifEmpty { "." }
                    if (target == "." || port in 1..65535) records += SrvRecord(priority, weight, port, target)
                }
                r.seek(end)
            }
            return Answer.Records(records)
        } catch (e: IndexOutOfBoundsException) {
            return null // truncated or malformed: treat as no answer
        } catch (e: IllegalArgumentException) {
            return null
        }
    }

    private fun writeName(out: ArrayList<Byte>, name: String) {
        val labels = name.trimEnd('.').split('.')
        var total = 1
        for (label in labels) {
            val bytes = label.encodeToByteArray()
            require(bytes.size in 1..63) { "Invalid DNS label in '$name'" }
            total += bytes.size + 1
            out += bytes.size.toByte()
            bytes.forEach { out += it }
        }
        require(total <= 255) { "DNS name too long" }
        out += 0
    }

    private fun ArrayList<Byte>.u16(value: Int) {
        add((value ushr 8).toByte())
        add(value.toByte())
    }

    private class Reader(private val data: ByteArray) {
        var position = 0
            private set

        fun u8(): Int = data[position++].toInt() and 0xFF

        fun u16(): Int = (u8() shl 8) or u8()

        fun skip(count: Int) {
            seek(position + count)
        }

        fun seek(to: Int) {
            if (to > data.size) throw IndexOutOfBoundsException()
            position = to
        }

        /** Reads a possibly compressed name, guarding against pointer loops. */
        fun name(): String {
            val labels = ArrayList<String>()
            var cursor = position
            var jumped = false
            var jumps = 0
            while (true) {
                val length = data[cursor].toInt() and 0xFF
                when {
                    length == 0 -> {
                        cursor++
                        break
                    }
                    length and 0xC0 == 0xC0 -> {
                        val target = ((length and 0x3F) shl 8) or (data[cursor + 1].toInt() and 0xFF)
                        if (!jumped) position = cursor + 2
                        jumped = true
                        require(++jumps <= 32) { "Compression loop" }
                        cursor = target
                    }
                    length and 0xC0 == 0 -> {
                        labels += data.decodeToString(cursor + 1, cursor + 1 + length)
                        cursor += 1 + length
                        require(labels.size <= 127) { "Name too long" }
                    }
                    else -> throw IllegalArgumentException("Unsupported label type")
                }
            }
            if (!jumped) position = cursor
            return labels.joinToString(".")
        }
    }
}
