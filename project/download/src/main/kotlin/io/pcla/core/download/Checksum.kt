package io.pcla.core.download

import java.io.File
import java.security.MessageDigest

/**
 * SHA-1 校验。
 *
 * SHA-1 在这里不是防篡改（它已被攻破），而是防「下载了一半」和「CDN 给了你一个 HTML 错误页」。
 * 真防篡改要靠 HTTPS + 官方签名，别指望这一段代码。
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

    /** expected 为 null 表示「没有官方哈希」，这时只做大小校验，不能假称已验证。 */
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
