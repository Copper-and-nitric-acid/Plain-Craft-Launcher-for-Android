package io.pcla.core.launch

/**
 * 把 LaunchPlan 变成一条能交给 ProcessBuilder 的 argv。
 *
 * 为什么不拼成一个字符串再用 sh -c：Android 上 ProcessBuilder 不经过 shell，
 * 拼字符串就得自己处理引号与转义，路径里一个空格就能让启动器静默失败。
 */
object LaunchCommand {

    fun build(
        javaBin: String,
        plan: LaunchPlan,
        heapMb: Int,
        nativesDir: String,
        extraJvm: List<String> = emptyList(),
    ): List<String> {
        val out = ArrayList<String>(plan.jvmArgs.size + plan.gameArgs.size + 12)
        out.add(javaBin)
        out.add("-Xms${heapMb}M")
        out.add("-Xmx${heapMb}M")

        // 原生库目录：json 里通常已给出，老版本没有，补一份
        if (plan.jvmArgs.none { it.startsWith("-Djava.library.path") }) {
            out.add("-Djava.library.path=" + nativesDir)
        }
        out.addAll(extraJvm)
        out.addAll(plan.jvmArgs)

        // 1.12 及以前的 json 只有 minecraftArguments，没有 -cp
        if (plan.jvmArgs.none { it == "-cp" || it == "-classpath" || it.startsWith("-Djava.class.path") }) {
            out.add("-cp")
            out.add(plan.classpath)
        }
        out.add(plan.mainClass)
        out.addAll(plan.gameArgs)
        return out
    }

    /**
     * 给 UI / 日志看的单行形式。参数里有空格就加引号，
     * 方便人眼核对，但**不要**把它的输出再丢回 shell 执行。
     */
    fun render(argv: List<String>): String = argv.joinToString(" ") { arg ->
        if (arg.any { it.isWhitespace() || it == '"' }) "\"" + arg.replace("\"", "\\\"") + "\"" else arg
    }
}
