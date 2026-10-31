package io.pcla.core.launch

/**
 * Minecraft version → required Java major version.
 *
 * Based on Mojang's actual requirements:
 *  - ≤ 1.16.5      → Java 8
 *  - 1.17 – 1.20.4 → Java 17 (16/17 forced from 1.17, 17 official from 1.18)
 *  - ≥ 1.20.5      → Java 21
 * No released MC version requires Java 25 yet; FCL bundles it for snapshots/future versions, so it is not mapped here.
 *
 * A wrong mapping is not "a bit slower", it crashes: `Unsupported class file major version 65`.
 */
object JavaPolicy {

    const val JAVA_8 = 8
    const val JAVA_17 = 17
    const val JAVA_21 = 21
    const val JAVA_25 = 25

    /** Unknown versions are treated as 8 — better a clear launch failure on 1.17+ than guessing 21 and crashing in class loading. */
    fun requiredMajor(mcVersion: String): Int {
        val key = numericKey(mcVersion) ?: return JAVA_8
        return when {
            key >= 2005 -> JAVA_21
            key >= 1700 -> JAVA_17
            else -> JAVA_8
        }
    }

    /** "1.20.5" → 2005; "1.16.5" → 1605; snapshot (23w45a) or invalid input → null. */
    private fun numericKey(version: String): Int? {
        val m = Regex("""^1\.(\d+)(?:\.(\d+))?$""").find(version.trim()) ?: return null
        val minor = m.groupValues[1].toIntOrNull() ?: return null
        val patch = m.groupValues[2].toIntOrNull() ?: 0
        return minor * 100 + patch
    }

    /** Path inside the bundled assets; arm64 shard only, because abiFilters is arm64-v8a only. */
    fun assetPath(major: Int): String = "java/jre$major/bin-arm64.tar.xz"

    /** Matches the assets directory; used by the health page to show which runtimes are built in. */
    fun bundledMajors(): List<Int> = listOf(JAVA_8, JAVA_17)
}
