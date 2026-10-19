package io.github.usmandurrany.tether.sasl

/**
 * Kotlin/Native has no Unicode normaliser in its standard library. ASCII is always
 * in NFC, so this is exact for ASCII passwords. A non-ASCII password typed in a
 * decomposed form may fail to authenticate here (it fails closed, never open).
 * A full normaliser comes with the native platform work in phase 4.
 */
internal actual fun normalizeNfc(value: String): String = value
