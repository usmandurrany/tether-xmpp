package io.github.usmandurrany.tether.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

class CryptoTest {

    private val abc = "abc".encodeToByteArray()
    private val twoBlock = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()

    // FIPS 180-4 example vectors, through the platform implementation and the portable one.
    @Test
    fun hashVectors() {
        for (digest in listOf<(HashAlgorithm, ByteArray) -> ByteArray>(Crypto::hash, PureHashes::digest)) {
            assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", digest(HashAlgorithm.SHA1, abc).toHex())
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest(HashAlgorithm.SHA256, abc).toHex())
            assertEquals(
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                    "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                digest(HashAlgorithm.SHA512, abc).toHex(),
            )
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest(HashAlgorithm.SHA256, ByteArray(0)).toHex())
            assertEquals("84983e441c3bd26ebaae4aa1f95129e5e54670f1", digest(HashAlgorithm.SHA1, twoBlock).toHex())
            assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", digest(HashAlgorithm.SHA256, twoBlock).toHex())
        }
    }

    // RFC 2202 and RFC 4231 test case 2
    @Test
    fun hmacVectors() {
        val key = "Jefe".encodeToByteArray()
        val data = "what do ya want for nothing?".encodeToByteArray()
        assertEquals("effcdf6ae5eb2fa2d27416d5f184df9c259a7c79", Crypto.hmac(HashAlgorithm.SHA1, key, data).toHex())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", Crypto.hmac(HashAlgorithm.SHA256, key, data).toHex())
        assertEquals(
            "164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554" +
                "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737",
            Crypto.hmac(HashAlgorithm.SHA512, key, data).toHex(),
        )
        // RFC 4231 test case 6: a key longer than the block size is hashed first.
        val longKey = ByteArray(131) { 0xAA.toByte() }
        val msg = "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54", Crypto.hmac(HashAlgorithm.SHA256, longKey, msg).toHex())
    }

    // RFC 6070
    @Test
    fun pbkdf2Vectors() {
        val p = "password".encodeToByteArray()
        val s = "salt".encodeToByteArray()
        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6", Crypto.pbkdf2(HashAlgorithm.SHA1, p, s, 1).toHex())
        assertEquals("4b007901b765489abead49d926f721d065a429c1", Crypto.pbkdf2(HashAlgorithm.SHA1, p, s, 4096).toHex())
    }

    @Test
    fun randomBytesAreFreshAndSized() {
        val a = Crypto.randomBytes(32)
        val b = Crypto.randomBytes(32)
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
        assertEquals(0, Crypto.randomBytes(0).size)
    }

    @Test
    fun constantTimeEqualsBehaves() {
        assertTrue(constantTimeEquals(hex("0102"), hex("0102")))
        assertFalse(constantTimeEquals(hex("0102"), hex("0103")))
        assertFalse(constantTimeEquals(hex("01"), hex("0102")))
    }
}
