package io.github.usmandurrany.tether.sasl

/**
 * Prepares a password with the PRECIS OpaqueString profile (RFC 8265 §4.2), which
 * replaced SASLprep: non-ASCII spaces become U+0020, the result is NFC-normalised,
 * and control characters are rejected.
 */
internal fun prepareOpaqueString(value: String): String {
    val mapped = buildString(value.length) {
        for (c in value) append(if (c.code != 0x20 && c.isNonAsciiSpace()) ' ' else c)
    }
    val normalised = normalizeNfc(mapped)
    require(normalised.isNotEmpty()) { "Password must not be empty" }
    for (c in normalised) {
        require(!(c.code < 0x20 || c.code in 0x7F..0x9F)) { "Password contains a control character" }
    }
    return normalised
}

/** Escapes a username for SCRAM's `n=` attribute (RFC 5802 §5.1). */
internal fun escapeSaslName(value: String): String {
    require(value.isNotEmpty()) { "Username must not be empty" }
    require(value.none { it.code < 0x20 || it.code in 0x7F..0x9F }) { "Username contains a control character" }
    return value.replace("=", "=3D").replace(",", "=2C")
}

/** Unicode general category Zs, except U+0020. */
private fun Char.isNonAsciiSpace(): Boolean =
    code == 0xA0 || code == 0x1680 || code in 0x2000..0x200A || code == 0x202F || code == 0x205F || code == 0x3000

/** Unicode NFC normalisation, from the platform. */
internal expect fun normalizeNfc(value: String): String
