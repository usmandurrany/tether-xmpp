package io.github.usmandurrany.tether.sasl

import kotlin.test.Test
import kotlin.test.assertEquals

class OpaqueStringJvmTest {
    @Test
    fun normalisesToNfc() {
        // "e" + combining acute accent must hash the same as the precomposed "é".
        assertEquals("é", prepareOpaqueString("é"))
    }
}
