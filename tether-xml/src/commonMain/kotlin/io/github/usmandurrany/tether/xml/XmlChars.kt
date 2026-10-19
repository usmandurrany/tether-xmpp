package io.github.usmandurrany.tether.xml

// Character classes from XML 1.0 (Fifth Edition) §2.2 and §2.3.

internal fun isXmlChar(c: Int): Boolean =
    c == 0x9 || c == 0xA || c == 0xD ||
        c in 0x20..0xD7FF || c in 0xE000..0xFFFD || c in 0x10000..0x10FFFF

internal fun isXmlWhitespace(c: Int): Boolean = c == 0x20 || c == 0x9 || c == 0xA || c == 0xD

internal fun isNameStartChar(c: Int): Boolean =
    c == ':'.code || c in 'A'.code..'Z'.code || c == '_'.code || c in 'a'.code..'z'.code ||
        c in 0xC0..0xD6 || c in 0xD8..0xF6 || c in 0xF8..0x2FF || c in 0x370..0x37D ||
        c in 0x37F..0x1FFF || c in 0x200C..0x200D || c in 0x2070..0x218F ||
        c in 0x2C00..0x2FEF || c in 0x3001..0xD7FF || c in 0xF900..0xFDCF ||
        c in 0xFDF0..0xFFFD || c in 0x10000..0xEFFFF

internal fun isNameChar(c: Int): Boolean =
    isNameStartChar(c) || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code ||
        c == 0xB7 || c in 0x300..0x36F || c in 0x203F..0x2040

/** Appends a Unicode code point, as a surrogate pair when above the BMP. */
internal fun StringBuilder.appendCodePoint(cp: Int): StringBuilder {
    if (cp < 0x10000) {
        append(cp.toChar())
    } else {
        val v = cp - 0x10000
        append((0xD800 + (v ushr 10)).toChar())
        append((0xDC00 + (v and 0x3FF)).toChar())
    }
    return this
}

/** True if [s] is a valid XML name without a colon (an NCName). */
internal fun isNcName(s: String): Boolean {
    if (s.isEmpty()) return false
    var i = 0
    var first = true
    while (i < s.length) {
        val cp = codePointAt(s, i) ?: return false
        if (cp == ':'.code) return false
        if (if (first) !isNameStartChar(cp) else !isNameChar(cp)) return false
        first = false
        i += if (cp >= 0x10000) 2 else 1
    }
    return true
}

/** Code point at [i], or null for a lone surrogate. */
internal fun codePointAt(s: String, i: Int): Int? {
    val c = s[i]
    if (c.isHighSurrogate()) {
        if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return null
        return 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
    }
    if (c.isLowSurrogate()) return null
    return c.code
}
