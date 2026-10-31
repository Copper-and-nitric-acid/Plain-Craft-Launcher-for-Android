package io.pcla.core.download

import java.io.File
import java.security.MessageDigest

/**
 * SHA-1 verification.
 *
 * SHA-1 here is not anti-tampering (it is broken), it catches "downloaded only half" and
 * "the CDN served an HTML error page". Real tamper protection needs HTTPS + official signatures.
 */
object Checksum {

    fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun normalize(hex: String): String = hex.trim().lowercase().removePrefix("sha1:")

    /** expected null means "no official hash"; then only size is checked, we must not claim verified. */
    fun verify(file: File, expectedSha1: String?, expectedSize: Long? = null): HashResult {
        if (expectedSize != null && file.length() != expectedSize) {
            return HashResult.SizeMismatch(expectedSize, file.length())
        }
        if (expectedSha1.isNullOrBlank()) return HashResult.NoHashProvided
        val actual = sha1Of(file)
        return if (actual == normalize(expectedSha1)) HashResult.Match(actual)
        else HashResult.Mismatch(normalize(expectedSha1), actual)
    }

    sealed interface HashResult {
        data class Match(val sha1: String) : HashResult
        data class Mismatch(val expected: String, val actual: String) : HashResult
        data class SizeMismatch(val expected: Long, val actual: Long) : HashResult
        data object NoHashProvided : HashResult
    }
}
