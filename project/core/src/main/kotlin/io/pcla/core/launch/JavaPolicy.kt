package io.pcla.core.launch

/**
 * Minecraft 版本 → 需要的 Java 大版本。
 *
 * 依据 Mojang 的实际要求：
 *  - ≤ 1.16.5      → Java 8
 *  - 1.17 – 1.20.4 → Java 17（1.17 起强制 16/17，1.18 起正式 17）
 *  - ≥ 1.20.5      → Java 21
 * Java 25 目前没有正式版 MC 要求，FCL 打包的是给快照/未来版本留的，所以这里不映射。
 *
 * 映射错了不是「慢一点」，是直接崩：`Unsupported class file major version 65`。
 */
object JavaPolicy {

    const val JAVA_8 = 8
    const val JAVA_17 = 17
    const val JAVA_21 = 21
    const val JAVA_25 = 25

    /** 未知版本一律按 8 处理——宁可让用户在 1.17+ 上看到明确的启动失败，也不要猜 21 崩在类加载。 */
    fun requiredMajor(mcVersion: String): Int {
        val key = numericKey(mcVersion) ?: return JAVA_8
        return when {
            key >= 2005 -> JAVA_21
            key >= 1700 -> JAVA_17
            else -> JAVA_8
        }
    }

    /** "1.20.5" → 2005；"1.16.5" → 1605；快照（23w45a）或非法输入 → null。 */
    private fun numericKey(version: String): Int? {
        val m = Regex("""^1\.(\d+)(?:\.(\d+))?$""").find(version.trim()) ?: return null
        val minor = m.groupValues[1].toIntOrNull() ?: return null
        val patch = m.groupValues[2].toIntOrNull() ?: 0
        return minor * 100 + patch
    }

    /** 随包 assets 里的路径；只有 arm64 分片，因为 abiFilters 只有 arm64-v8a。 */
    fun assetPath(major: Int): String = "java/jre$major/bin-arm64.tar.xz"

    /** 与 assets 目录一致，用于体检页展示「本包内置了哪几套运行时」。 */
    fun bundledMajors(): List<Int> = listOf(JAVA_8, JAVA_17)
}
