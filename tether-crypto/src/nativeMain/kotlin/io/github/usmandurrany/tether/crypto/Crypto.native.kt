package io.github.usmandurrany.tether.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open
import platform.posix.read

public actual object Crypto {
    public actual fun hash(algorithm: HashAlgorithm, data: ByteArray): ByteArray =
        PureHashes.digest(algorithm, data)

    public actual fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray =
        hmacWith(algorithm, key, data) { PureHashes.digest(algorithm, it) }

    /** Reads the kernel CSPRNG. Fails loudly rather than ever returning weak bytes. */
    @OptIn(ExperimentalForeignApi::class)
    public actual fun randomBytes(count: Int): ByteArray {
        require(count >= 0) { "count must not be negative" }
        val out = ByteArray(count)
        if (count == 0) return out
        val fd = open("/dev/urandom", O_RDONLY or O_CLOEXEC)
        check(fd >= 0) { "Cannot open /dev/urandom (errno $errno)" }
        try {
            out.usePinned { pinned ->
                var offset = 0
                while (offset < count) {
                    val n = read(fd, pinned.addressOf(offset), (count - offset).convert()).toInt()
                    if (n > 0) {
                        offset += n
                    } else if (!(n < 0 && errno == EINTR)) {
                        error("Reading /dev/urandom failed (errno $errno)")
                    }
                }
            }
        } finally {
            close(fd)
        }
        return out
    }
}
