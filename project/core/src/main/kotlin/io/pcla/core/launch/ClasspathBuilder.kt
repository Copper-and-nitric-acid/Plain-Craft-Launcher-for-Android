package io.pcla.core.launch

/**
 * Translate a version json into a command line the JVM can take directly.
 *
 * Deliberately pure string work (no java.io): natives extraction and file existence
 * are the caller's job, so this logic can run its full self-check in a container with fake paths.
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
        extraJars: List<String> = emptyList(),
        // The manifest's org.lwjgl:*:3.2.2 is built for desktop x86; we swapped in the bundled 3.3.3 (with arm64 natives).
        // Keeping both on the classpath is not "compatibility", it means: 3.2.2's x86 liblwjgl.so gets found first via
        // java.library.path → wrong ELF class; classes also get mixed between 3.2.2 and 3.3.3.
        // So drop the whole group by coordinate prefix and keep only the bundled set.
        skipGroups: Set<String> = setOf("org.lwjgl"),
    ): LaunchPlan {
        val entries = ArrayList<String>(extraJars)
        val natives = ArrayList<String>()
        val missing = ArrayList<String>()

        meta.libraries.forEach { lib ->
            if (!Platform.allows(lib.rules)) return@forEach
            if (skipGroups.any { lib.name.startsWith("$it:") }) return@forEach
            if (lib.nativeClassifier != null) {
                val path = lib.nativePath
                if (path == null) missing.add(lib.name) else natives.add(join(libRoot, path))
                // In the new format one coordinate can be both a regular library and natives (e.g. lwjgl);
                // here we take only the natives; the plain jar comes from the same-named entry below.
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
        // The classpath can only be computed here; any value passed by the caller is overwritten,
        // otherwise you naturally hit "the cp in the placeholders is not plan.classpath".
        val values = HashMap(placeholders).apply {
            this["classpath"] = entries.joinToString(":")
        }
        fun subst(list: List<Arg>): List<String> = list
            .filter { Platform.allows(it.rules) }
            .map { arg -> substitute(arg.value, values, unresolved) }

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

    /** Replaces dollar+{key} placeholders; unknown ones are kept as-is and recorded, so the UI can report them instead of failing silently at launch. */
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

    /** The batch of placeholders the JVM side needs most at launch; defaults are all paths that really exist on this device. */
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

    /** Minimum checks before launch: if any one fails, the launch button should not be pressed. */
    fun problems(plan: LaunchPlan): List<String> = buildList {
        if (plan.mainClass.isBlank()) add("mainClass 为空，版本 json 不完整")
        if (plan.classpath.isBlank()) add("classpath 为空")
        if (plan.missing.isNotEmpty()) add("缺 ${plan.missing.size} 个库（未声明下载地址）")
        if (plan.unresolved.isNotEmpty()) add("未替换的占位符：${plan.unresolved.joinToString(",")}")
    }

    private fun join(root: String, path: String): String =
        if (root.endsWith("/")) root + path else "$root/$path"
}
