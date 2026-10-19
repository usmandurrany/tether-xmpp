package io.github.usmandurrany.tether.crypto

/**
 * Portable SHA-1, SHA-256 and SHA-512 (FIPS 180-4), used on platforms without a
 * native crypto provider. The JVM uses JCA instead; a JVM test checks that both
 * produce identical output. The SHA-2 constants were generated from the prime
 * roots that define them, not copied by hand.
 */
internal object PureHashes {

    fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray = when (algorithm) {
        HashAlgorithm.SHA1 -> sha1(data)
        HashAlgorithm.SHA256 -> sha256(data)
        HashAlgorithm.SHA512 -> sha512(data)
    }

    /** Pads per FIPS 180-4 §5.1: 0x80, zeros, then the bit length as [lengthBytes] big-endian bytes. */
    private fun pad(data: ByteArray, blockSize: Int, lengthBytes: Int): ByteArray {
        val bitLength = data.size.toLong() * 8
        var total = data.size + 1 + lengthBytes
        total += (blockSize - total % blockSize) % blockSize
        val out = data.copyOf(total)
        out[data.size] = 0x80.toByte()
        // Messages here are far below 2^64 bits, so any length bytes beyond 8 stay zero.
        for (i in 0 until 8) out[total - 1 - i] = (bitLength ushr (8 * i)).toByte()
        return out
    }

    private fun int(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF shl 24) or (b[i + 1].toInt() and 0xFF shl 16) or
            (b[i + 2].toInt() and 0xFF shl 8) or (b[i + 3].toInt() and 0xFF)

    private fun long(b: ByteArray, i: Int): Long =
        (int(b, i).toLong() shl 32) or (int(b, i + 4).toLong() and 0xFFFFFFFFL)

    private fun IntArray.toBytes(): ByteArray {
        val out = ByteArray(size * 4)
        for (i in indices) for (j in 0 until 4) out[i * 4 + j] = (this[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    private fun LongArray.toBytes(): ByteArray {
        val out = ByteArray(size * 8)
        for (i in indices) for (j in 0 until 8) out[i * 8 + j] = (this[i] ushr (56 - 8 * j)).toByte()
        return out
    }

    private fun sha1(data: ByteArray): ByteArray {
        val m = pad(data, 64, 8)
        val h = intArrayOf(0x67452301, 0xEFCDAB89u.toInt(), 0x98BADCFEu.toInt(), 0x10325476, 0xC3D2E1F0u.toInt())
        val w = IntArray(80)
        for (block in 0 until m.size / 64) {
            for (t in 0 until 16) w[t] = int(m, block * 64 + t * 4)
            for (t in 16 until 80) w[t] = (w[t - 3] xor w[t - 8] xor w[t - 14] xor w[t - 16]).rotateLeft(1)
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]
            for (t in 0 until 80) {
                val f: Int
                val k: Int
                when {
                    t < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                    t < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                    t < 60 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDCu.toInt() }
                    else -> { f = b xor c xor d; k = 0xCA62C1D6u.toInt() }
                }
                val temp = a.rotateLeft(5) + f + e + k + w[t]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e
        }
        return h.toBytes()
    }

    private fun sha256(data: ByteArray): ByteArray {
        val m = pad(data, 64, 8)
        val h = H256.copyOf()
        val w = IntArray(64)
        for (block in 0 until m.size / 64) {
            for (t in 0 until 16) w[t] = int(m, block * 64 + t * 4)
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K256[t] + w[t]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return h.toBytes()
    }

    private fun sha512(data: ByteArray): ByteArray {
        val m = pad(data, 128, 16)
        val h = H512.copyOf()
        val w = LongArray(80)
        for (block in 0 until m.size / 128) {
            for (t in 0 until 16) w[t] = long(m, block * 128 + t * 8)
            for (t in 16 until 80) {
                val s0 = w[t - 15].rotateRight(1) xor w[t - 15].rotateRight(8) xor (w[t - 15] ushr 7)
                val s1 = w[t - 2].rotateRight(19) xor w[t - 2].rotateRight(61) xor (w[t - 2] ushr 6)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 80) {
                val s1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K512[t] + w[t]
                val s0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return h.toBytes()
    }

    private val H256 = intArrayOf(
        0x6A09E667u.toInt(), 0xBB67AE85u.toInt(), 0x3C6EF372u.toInt(), 0xA54FF53Au.toInt(),
        0x510E527Fu.toInt(), 0x9B05688Cu.toInt(), 0x1F83D9ABu.toInt(), 0x5BE0CD19u.toInt(),
    )

    private val K256 = intArrayOf(
        0x428A2F98u.toInt(), 0x71374491u.toInt(), 0xB5C0FBCFu.toInt(), 0xE9B5DBA5u.toInt(),
        0x3956C25Bu.toInt(), 0x59F111F1u.toInt(), 0x923F82A4u.toInt(), 0xAB1C5ED5u.toInt(),
        0xD807AA98u.toInt(), 0x12835B01u.toInt(), 0x243185BEu.toInt(), 0x550C7DC3u.toInt(),
        0x72BE5D74u.toInt(), 0x80DEB1FEu.toInt(), 0x9BDC06A7u.toInt(), 0xC19BF174u.toInt(),
        0xE49B69C1u.toInt(), 0xEFBE4786u.toInt(), 0x0FC19DC6u.toInt(), 0x240CA1CCu.toInt(),
        0x2DE92C6Fu.toInt(), 0x4A7484AAu.toInt(), 0x5CB0A9DCu.toInt(), 0x76F988DAu.toInt(),
        0x983E5152u.toInt(), 0xA831C66Du.toInt(), 0xB00327C8u.toInt(), 0xBF597FC7u.toInt(),
        0xC6E00BF3u.toInt(), 0xD5A79147u.toInt(), 0x06CA6351u.toInt(), 0x14292967u.toInt(),
        0x27B70A85u.toInt(), 0x2E1B2138u.toInt(), 0x4D2C6DFCu.toInt(), 0x53380D13u.toInt(),
        0x650A7354u.toInt(), 0x766A0ABBu.toInt(), 0x81C2C92Eu.toInt(), 0x92722C85u.toInt(),
        0xA2BFE8A1u.toInt(), 0xA81A664Bu.toInt(), 0xC24B8B70u.toInt(), 0xC76C51A3u.toInt(),
        0xD192E819u.toInt(), 0xD6990624u.toInt(), 0xF40E3585u.toInt(), 0x106AA070u.toInt(),
        0x19A4C116u.toInt(), 0x1E376C08u.toInt(), 0x2748774Cu.toInt(), 0x34B0BCB5u.toInt(),
        0x391C0CB3u.toInt(), 0x4ED8AA4Au.toInt(), 0x5B9CCA4Fu.toInt(), 0x682E6FF3u.toInt(),
        0x748F82EEu.toInt(), 0x78A5636Fu.toInt(), 0x84C87814u.toInt(), 0x8CC70208u.toInt(),
        0x90BEFFFAu.toInt(), 0xA4506CEBu.toInt(), 0xBEF9A3F7u.toInt(), 0xC67178F2u.toInt(),
    )

    private val H512 = longArrayOf(
        0x6A09E667F3BCC908uL.toLong(), 0xBB67AE8584CAA73BuL.toLong(), 0x3C6EF372FE94F82BuL.toLong(), 0xA54FF53A5F1D36F1uL.toLong(),
        0x510E527FADE682D1uL.toLong(), 0x9B05688C2B3E6C1FuL.toLong(), 0x1F83D9ABFB41BD6BuL.toLong(), 0x5BE0CD19137E2179uL.toLong(),
    )

    private val K512 = longArrayOf(
        0x428A2F98D728AE22uL.toLong(), 0x7137449123EF65CDuL.toLong(), 0xB5C0FBCFEC4D3B2FuL.toLong(), 0xE9B5DBA58189DBBCuL.toLong(),
        0x3956C25BF348B538uL.toLong(), 0x59F111F1B605D019uL.toLong(), 0x923F82A4AF194F9BuL.toLong(), 0xAB1C5ED5DA6D8118uL.toLong(),
        0xD807AA98A3030242uL.toLong(), 0x12835B0145706FBEuL.toLong(), 0x243185BE4EE4B28CuL.toLong(), 0x550C7DC3D5FFB4E2uL.toLong(),
        0x72BE5D74F27B896FuL.toLong(), 0x80DEB1FE3B1696B1uL.toLong(), 0x9BDC06A725C71235uL.toLong(), 0xC19BF174CF692694uL.toLong(),
        0xE49B69C19EF14AD2uL.toLong(), 0xEFBE4786384F25E3uL.toLong(), 0x0FC19DC68B8CD5B5uL.toLong(), 0x240CA1CC77AC9C65uL.toLong(),
        0x2DE92C6F592B0275uL.toLong(), 0x4A7484AA6EA6E483uL.toLong(), 0x5CB0A9DCBD41FBD4uL.toLong(), 0x76F988DA831153B5uL.toLong(),
        0x983E5152EE66DFABuL.toLong(), 0xA831C66D2DB43210uL.toLong(), 0xB00327C898FB213FuL.toLong(), 0xBF597FC7BEEF0EE4uL.toLong(),
        0xC6E00BF33DA88FC2uL.toLong(), 0xD5A79147930AA725uL.toLong(), 0x06CA6351E003826FuL.toLong(), 0x142929670A0E6E70uL.toLong(),
        0x27B70A8546D22FFCuL.toLong(), 0x2E1B21385C26C926uL.toLong(), 0x4D2C6DFC5AC42AEDuL.toLong(), 0x53380D139D95B3DFuL.toLong(),
        0x650A73548BAF63DEuL.toLong(), 0x766A0ABB3C77B2A8uL.toLong(), 0x81C2C92E47EDAEE6uL.toLong(), 0x92722C851482353BuL.toLong(),
        0xA2BFE8A14CF10364uL.toLong(), 0xA81A664BBC423001uL.toLong(), 0xC24B8B70D0F89791uL.toLong(), 0xC76C51A30654BE30uL.toLong(),
        0xD192E819D6EF5218uL.toLong(), 0xD69906245565A910uL.toLong(), 0xF40E35855771202AuL.toLong(), 0x106AA07032BBD1B8uL.toLong(),
        0x19A4C116B8D2D0C8uL.toLong(), 0x1E376C085141AB53uL.toLong(), 0x2748774CDF8EEB99uL.toLong(), 0x34B0BCB5E19B48A8uL.toLong(),
        0x391C0CB3C5C95A63uL.toLong(), 0x4ED8AA4AE3418ACBuL.toLong(), 0x5B9CCA4F7763E373uL.toLong(), 0x682E6FF3D6B2B8A3uL.toLong(),
        0x748F82EE5DEFB2FCuL.toLong(), 0x78A5636F43172F60uL.toLong(), 0x84C87814A1F0AB72uL.toLong(), 0x8CC702081A6439ECuL.toLong(),
        0x90BEFFFA23631E28uL.toLong(), 0xA4506CEBDE82BDE9uL.toLong(), 0xBEF9A3F7B2C67915uL.toLong(), 0xC67178F2E372532BuL.toLong(),
        0xCA273ECEEA26619CuL.toLong(), 0xD186B8C721C0C207uL.toLong(), 0xEADA7DD6CDE0EB1EuL.toLong(), 0xF57D4F7FEE6ED178uL.toLong(),
        0x06F067AA72176FBAuL.toLong(), 0x0A637DC5A2C898A6uL.toLong(), 0x113F9804BEF90DAEuL.toLong(), 0x1B710B35131C471BuL.toLong(),
        0x28DB77F523047D84uL.toLong(), 0x32CAAB7B40C72493uL.toLong(), 0x3C9EBE0A15C9BEBCuL.toLong(), 0x431D67C49C100D4CuL.toLong(),
        0x4CC5D4BECB3E42B6uL.toLong(), 0x597F299CFC657E2AuL.toLong(), 0x5FCB6FAB3AD6FAECuL.toLong(), 0x6C44198C4A475817uL.toLong(),
    )
}
