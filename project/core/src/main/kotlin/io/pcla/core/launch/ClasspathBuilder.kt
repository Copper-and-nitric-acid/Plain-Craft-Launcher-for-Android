package io.pcla.core.launch

/**
 * 把版本 json 翻译成一条能直接交给 JVM 的命令行。
 *
 * 刻意保持纯字符串运算（不碰 java.io）：natives 解压、文件是否存在
 * 由调用方负责，这样这段逻辑在容器里能用假路径跑完整的自检。
 */
data class LaunchPlan(
    val mainClass: String,
    val classpath: String,
    val nativeJars: List<String>,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val javaMajor: Int,
    val missing: List<String>,
    val unresolved: List<String>,
)

object ClasspathBuilder {

    fun build(
        meta: VersionMeta,
        libRoot: String,
        clientJar: String,
        placeholders: Map<String, String>,
    ): LaunchPlan {
        val entries = ArrayList<String>()
        val natives = ArrayList<String>()
        val missing = ArrayList<String>()

        meta.libraries.forEach { lib ->
            if (!Platform.allows(lib.rules)) return@forEach
            if (lib.nativeClassifier != null) {
                val path = lib.nativePath
                if (path == null) missing.add(lib.name) else natives.add(join(libRoot, path))
                // 新格式里同一坐标可能既是普通库又是 natives（如 lwjgl），
                // 这里只取 natives，普通 jar 由后面那条同名库提供。
                if (lib.artifactPath == null) return@forEach
            }
            val path = lib.artifactPath
            if (path == null) {
                missing.add(lib.name)
            } else {
                entries.add(join(libRoot, path))
            }
        }
        entries.add(clientJar)

        val unresolved = ArrayList<String>()
        fun subst(list: List<Arg>): List<String> = list
            .filter { Platform.allows(it.rules) }
            .map { arg -> substitute(arg.value, placeholders, unresolved) }

        return LaunchPlan(
            mainClass = meta.mainClass.orEmpty(),
            classpath = entries.joinToString(":"),
            nativeJars = natives.distinct(),
            jvmArgs = subst(meta.jvmArgs),
            gameArgs = subst(meta.gameArgs),
            javaMajor = meta.javaMajor ?: JavaPolicy.requiredMajor(meta.id),
            missing = missing,
            unresolved = unresolved.distinct(),
        )
    }

    /** 形如 dollar+{key} 的占位符替换；未知的保留原样并记账，方便在 UI 上报错而不是静默启动失败。 */
    fun substitute(text: String, values: Map<String, String>, unresolved: MutableList<String>? = null): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val isPlaceholder = c == '\u0024' && i + 1 < text.length && text[i + 1] == '{'
            if (!isPlaceholder) {
                sb.append(c)
                i++
                continue
            }
            val end = text.indexOf('}', i)
            if (end < 0) {
                sb.append(text, i, text.length)
                break
            }
            val key = text.substring(i + 2, end)
            val v = values[key]
            if (v != null) {
                sb.append(v)
            } else {
                sb.append(text, i, end + 1)
                unresolved?.add(key)
            }
            i = end + 1
        }
        return sb.toString()
    }

    /** 启动时 JVM 侧最常用的一批占位符，缺省值全是「本机上真实存在」的路径。 */
    fun placeholders(
        classpath: String,
        nativesDir: String,
        gameDir: String,
        assetsDir: String,
        assetsIndex: String,
        versionName: String,
        playerName: String = "PCLA",
    ): Map<String, String> = mapOf(
        "classpath" to classpath,
        "natives_directory" to nativesDir,
        "game_directory" to gameDir,
        "assets_root" to assetsDir,
        "assets_index_name" to assetsIndex,
        "version_name" to versionName,
        "version_type" to "PCLA",
        "auth_player_name" to playerName,
        "auth_uuid" to "00000000000000000000000000000000",
        "auth_access_token" to "0",
        "auth_session" to "-",
        "user_type" to "legacy",
        "user_properties" to "{}",
        "launcher_name" to "PCLA",
        "launcher_version" to "0.5.0",
        "clientid" to "",
        "auth_xuid" to "",
    )

    /** 启动前的最低限度检查：任何一条不过，就不该按下启动键。 */
    fun problems(plan: LaunchPlan): List<String> = buildList {
        if (plan.mainClass.isBlank()) add("mainClass 为空，版本 json 不完整")
        if (plan.classpath.isBlank()) add("classpath 为空")
        if (plan.missing.isNotEmpty()) add("缺 ${plan.missing.size} 个库（未声明下载地址）")
        if (plan.unresolved.isNotEmpty()) add("未替换的占位符：${plan.unresolved.joinToString(",")}")
    }

    private fun join(root: String, path: String): String =
        if (root.endsWith("/")) root + path else "$root/$path"
}
