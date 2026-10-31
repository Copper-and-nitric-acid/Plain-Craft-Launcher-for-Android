package io.pcla.core.launch

/**
 * One small job: recognize `java -version` output and map it to a major version.
 *
 * Why it is needed: when JRE extraction goes wrong, `bin/java` exists but dies on execution
 * (missing .so, wrong arch, corrupted), and the log has only one line, "Exec format error",
 * which does not say which layer failed. Running `-version` alone separates "the JRE itself"
 * from "Minecraft's classpath".
 */
object JavaProbe {

    /** Arguments for `java -version`: version only, no user code. */
    val ARGS: List<String> = listOf("-version")

    data class Output(
        /** The matched version line verbatim, e.g. `openjdk version "17.0.9" 2023-10-17`. */
        val line: String? = null,
        /** Version number inside the quotes, e.g. 17.0.9 / 1.8.0_202. */
        val version: String? = null,
        /** Major version derived from version; null if unrecognized. */
        val major: Int? = null,
        /** Path given in the two `java.home` lines (some versions do not print it). */
        val home: String? = null,
    ) {
        val ok: Boolean get() = version != null
    }

    private val VERSION_LINE = Regex("""^(?:openjdk|java|OpenJDK|Java)\s+version\s+"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val HOME_LINE = Regex("""java\.home\s*=\s*(\S+)""")

    /**
     * Pick the version from the full output. stderr counts too: `java -version` has always written the version to stderr.
     */
    fun parse(text: String): Output {
        var version: String? = null
        var line: String? = null
        var home: String? = null
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (version == null) {
                VERSION_LINE.find(l)?.let {
                    version = it.groupValues[1]
                    line = l
                }
            }
            if (home == null) HOME_LINE.find(l)?.let { home = it.groupValues[1] }
        }
        return Output(line = line, version = version, major = version?.let(::majorOf), home = home)
    }

    /** `1.8.0_202` → 8; `17.0.9` → 17; `21` → 21. */
    fun majorOf(version: String): Int? {
        val parts = version.trim().split('.', '_', '-')
        val first = parts.firstOrNull()?.toIntOrNull() ?: return null
        if (first != 1) return first
        // Old 1.x.y form: x is the major version; `java -version` no longer prints 1.9
        return parts.getOrNull(1)?.toIntOrNull()
    }

    /** Problems to report once it runs; an empty list means no problem. */
    fun problems(expectedMajor: Int, out: Output): List<String> {
        val out2 = mutableListOf<String>()
        if (!out.ok) {
            out2 += "没认出 java 版本（输出里没有 `java version \"x.y.z\"` 这种行）"
            return out2
        }
        val got = out.major
        if (got == null) {
            out2 += "版本号 `${out.version}` 认不出大版本"
        } else if (got != expectedMajor) {
            out2 += "版本对不上：这个版本要 Java $expectedMajor，包里的却是 Java $got"
            if (got > expectedMajor) out2 += "用高版本跑会 `Unsupported class file major version`"
            else out2 += "用低版本跑会 `Unsupported class file major version 6x` 或直接缺失新 API"
        }
        return out2
    }

    /** One-line summary for the log. */
    fun render(out: Output, expectedMajor: Int): String = buildString {
        append("java ")
        append(out.version ?: "未识别")
        out.major?.let { append("（大版本 $it，需要 $expectedMajor）") }
        if (out.home != null) append(" home=${out.home}")
    }
}
