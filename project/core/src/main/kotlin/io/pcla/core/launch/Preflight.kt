package io.pcla.core.launch

/**
 * 启动前自检。
 *
 * 真机上"点了没反应"最费时间，而大部分原因在起 JVM 之前就能查出来：
 * classpath 里有没有 GLFW、natives 里有没有 libpojavexec、java.library.path 是不是空的。
 * 这里全部做成纯字符串/路径判断，容器里能用假数据跑满。
 */
data class Check(val name: String, val ok: Boolean, val detail: String)

object Preflight {

    fun run(
        plan: LaunchPlan,
        jarNames: List<String>,
        nativePaths: List<String>,
        nativeFiles: List<String> = emptyList(),
        expectedMajor: Int = plan.javaMajor,
        libraryPath: String = "",
    ): List<Check> {
        val out = ArrayList<Check>()
        val cp = plan.classpath.split(File_sep)

        out += Check(
            "merged-modules 在 classpath 最前",
            cp.firstOrNull()?.contains("merged-modules") == true,
            cp.firstOrNull() ?: "classpath 为空",
        )
        out += Check(
            "classpath 里有 GLFW 实现",
            jarNames.any { it.contains("merged-modules") } || cp.any { it.contains("merged-modules") },
            "合并模块提供 org.lwjgl.glfw；官方 lwjgl.jar 里没有这个包",
        )
        out += Check(
            "AWT 工具包在 classpath",
            cp.any { it.contains("cacio") },
            "没有 caciocavallo 时 AWT 会走 headless，建窗口必抛 HeadlessException",
        )
        out += Check(
            "主类已确定",
            plan.mainClass.isNotBlank(),
            plan.mainClass,
        )
        val wantArgs = listOf("--gameDir", "--assetsDir", "--assetIndex")
        val absent = wantArgs.filter { k -> plan.gameArgs.none { it == k || it.startsWith("$k=") } }
        out += Check(
            "游戏参数齐全",
            absent.isEmpty(),
            if (absent.isEmpty()) "共 ${plan.gameArgs.size} 个参数" else "缺 ${absent.joinToString()}",
        )
        val inPlan = plan.jvmArgs.firstOrNull { it.startsWith("-Djava.library.path=") }
        out += Check(
            "java.library.path 已设置",
            libraryPath.isNotBlank() || inPlan != null,
            when {
                libraryPath.isNotBlank() -> libraryPath
                inPlan != null -> inPlan
                else -> "缺失：LaunchCommand 启动前必须注入三处 natives 目录"
            },
        )
        out += Check(
            "natives 目录存在三项",
            nativePaths.filter { it.isNotBlank() }.size >= 3,
            nativePaths.joinToString(" | "),
        )
        out += Check(
            "libpojavexec 在 natives 里",
            nativeFiles.isEmpty() || nativeFiles.any { it.contains("pojavexec") },
            if (nativeFiles.isEmpty()) "未提供文件清单，跳过" else nativeFiles.filter { it.contains("pojavexec") }.joinToString(),
        )
        out += Check(
            "gl4es 在 natives 里",
            nativeFiles.isEmpty() || nativeFiles.any { it.contains("gl4es") },
            if (nativeFiles.isEmpty()) "未提供文件清单，跳过" else nativeFiles.filter { it.contains("gl4es") }.joinToString(),
        )
        out += Check(
            "Java 大版本与版本号匹配",
            plan.javaMajor == expectedMajor,
            "版本要 Java $expectedMajor，计划是 Java ${plan.javaMajor}（8→≤1.16.5，17→1.18~1.20.4，21→1.20.5+）",
        )
        return out
    }

    const val File_sep: Char = ':'

    fun render(checks: List<Check>): String = checks.joinToString("\n") { c ->
        (if (c.ok) "✓ " else "✗ ") + c.name + (if (c.detail.isBlank()) "" else " —— " + c.detail)
    }
}
