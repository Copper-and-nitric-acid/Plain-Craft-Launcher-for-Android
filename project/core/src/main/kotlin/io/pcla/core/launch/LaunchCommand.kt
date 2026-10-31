package io.pcla.core.launch

/**
 * Turn a LaunchPlan into an argv ProcessBuilder can take.
 *
 * Why not join one string and run sh -c: on Android ProcessBuilder does not go through a shell,
 * so a joined string means handling quotes and escapes yourself — one space in a path silently breaks the launcher.
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

        // Native library dir: the json usually gives it, old versions do not, so add one if missing
        if (plan.jvmArgs.none { it.startsWith("-Djava.library.path") }) {
            out.add("-Djava.library.path=" + nativesDir)
        }
        out.addAll(extraJvm)
        out.addAll(plan.jvmArgs)

        // json for 1.12 and older has only minecraftArguments, no -cp
        if (plan.jvmArgs.none { it == "-cp" || it == "-classpath" || it.startsWith("-Djava.class.path") }) {
            out.add("-cp")
            out.add(plan.classpath)
        }
        out.add(plan.mainClass)
        out.addAll(plan.gameArgs)
        return out
    }

    /**
     * Single-line form for the UI / log. Arguments containing spaces get quotes,
     * so a human can verify it, but **do not** feed its output back to a shell to execute.
     */
    fun render(argv: List<String>): String = argv.joinToString(" ") { arg ->
        if (arg.any { it.isWhitespace() || it == '"' }) "\"" + arg.replace("\"", "\\\"") + "\"" else arg
    }
}
