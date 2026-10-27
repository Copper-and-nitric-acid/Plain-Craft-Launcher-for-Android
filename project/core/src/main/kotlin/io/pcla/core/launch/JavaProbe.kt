package io.pcla.core.launch

/**
 * 只干一件小事：把 `java -version` 的输出认出来，对上大版本。
 *
 * 为什么需要它：JRE 解包出问题时，`bin/java` 存在但一执行就死
 * （缺 so、架构不对、被压坏），而日志里只有一行 "Exec format error"，
 * 看不出是哪一层的事。先单独跑 `-version`，能把「JRE 本身」和
 * 「Minecraft 的 classpath」这两类问题分开。
 */
object JavaProbe {

    /** `java -version` 的参数：只要版本，不跑用户代码。 */
    val ARGS: List<String> = listOf("-version")

    data class Output(
        /** 匹配到的版本行原文，例如 `openjdk version "17.0.9" 2023-10-17`。 */
        val line: String? = null,
        /** 引号里的版本号，例如 17.0.9 / 1.8.0_202。 */
        val version: String? = null,
        /** 由 version 推出来的大版本，认不出就是 null。 */
        val major: Int? = null,
        /** `java.home` 那两行里给出来的路径（有的版本不打印）。 */
        val home: String? = null,
    ) {
        val ok: Boolean get() = version != null
    }

    private val VERSION_LINE = Regex("""^(?:openjdk|java|OpenJDK|Java)\s+version\s+"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val HOME_LINE = Regex("""java\.home\s*=\s*(\S+)""")

    /**
     * 从完整输出里挑版本。stderr 也算：`java -version` 历来把版本写 stderr。
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

    /** `1.8.0_202` → 8；`17.0.9` → 17；`21` → 21。 */
    fun majorOf(version: String): Int? {
        val parts = version.trim().split('.', '_', '-')
        val first = parts.firstOrNull()?.toIntOrNull() ?: return null
        if (first != 1) return first
        // 老写法 1.x.y：x 才是大版本；`java -version` 已经不会再输出 1.9 了
        return parts.getOrNull(1)?.toIntOrNull()
    }

    /** 跑起来之后该报的问题，空列表就是没问题。 */
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

    /** 给日志用的一行摘要。 */
    fun render(out: Output, expectedMajor: Int): String = buildString {
        append("java ")
        append(out.version ?: "未识别")
        out.major?.let { append("（大版本 $it，需要 $expectedMajor）") }
        if (out.home != null) append(" home=${out.home}")
    }
}
