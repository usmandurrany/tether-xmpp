package io.github.usmandurrany.tether.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** The portable implementation must agree with JCA byte for byte, at every length around block edges. */
class PureHashesJvmTest {
    @Test
    fun matchesJcaForManyLengths() {
        val random = Random(1)
        for (algorithm in HashAlgorithm.entries) {
            for (length in 0..300) {
                val data = random.nextBytes(length)
                assertContentEquals(Crypto.hash(algorithm, data), PureHashes.digest(algorithm, data), "$algorithm, $length bytes")
                val key = random.nextBytes(random.nextInt(0, 200))
                assertContentEquals(
                    Crypto.hmac(algorithm, key, data),
                    hmacWith(algorithm, key, data) { PureHashes.digest(algorithm, it) },
                    "HMAC $algorithm, $length bytes",
                )
            }
        }
    }
}
