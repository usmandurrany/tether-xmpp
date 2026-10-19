package io.github.usmandurrany.tether.sasl

import java.text.Normalizer

internal actual fun normalizeNfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
