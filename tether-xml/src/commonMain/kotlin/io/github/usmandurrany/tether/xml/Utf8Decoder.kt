package io.github.usmandurrany.tether.xml

/**
 * Incremental, strict UTF-8 decoder. Bytes can arrive split anywhere.
 * Rejects overlong forms, surrogate code points, values above U+10FFFF and bad
 * continuation bytes, per RFC 3629.
 */
internal class Utf8Decoder {
    private var codePoint = 0
    private var remaining = 0
    private var minValue = 0

    val isMidSequence: Boolean get() = remaining > 0

    /** Returns the decoded code point, or -1 when more bytes are needed. */
    fun decode(b: Int): Int {
        if (remaining == 0) {
            when (b) {
                in 0x00..0x7F -> return b
                in 0xC2..0xDF -> start(b and 0x1F, 1, 0x80)
                in 0xE0..0xEF -> start(b and 0x0F, 2, 0x800)
                in 0xF0..0xF4 -> start(b and 0x07, 3, 0x10000)
                else -> fail("Invalid UTF-8 lead byte 0x${b.toString(16)}")
            }
            return -1
        }
        if (b and 0xC0 != 0x80) fail("Invalid UTF-8 continuation byte 0x${b.toString(16)}")
        codePoint = (codePoint shl 6) or (b and 0x3F)
        if (--remaining > 0) return -1
        val cp = codePoint
        if (cp < minValue) fail("Overlong UTF-8 sequence")
        if (cp in 0xD800..0xDFFF) fail("UTF-8 encoded surrogate")
        if (cp > 0x10FFFF) fail("Code point above U+10FFFF")
        return cp
    }

    fun reset() {
        codePoint = 0
        remaining = 0
        minValue = 0
    }

    private fun start(bits: Int, count: Int, min: Int) {
        codePoint = bits
        remaining = count
        minValue = min
    }

    private fun fail(message: String): Nothing =
        throw XmlStreamException(XmlErrorCondition.NOT_WELL_FORMED, message)
}
