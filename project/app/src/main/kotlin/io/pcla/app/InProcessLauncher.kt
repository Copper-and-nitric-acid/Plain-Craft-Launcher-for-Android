package io.pcla.app

import android.content.Context
import android.util.Log
import com.oracle.dalvik.VMLauncher
import io.pcla.core.launch.AwtRuntime
import io.pcla.core.launch.LaunchCommand
import java.io.File

/**
 * Experiment: don't exec an external java, start the JVM inside the app process (Pojav's way).
 *
 * Why bet on it: the Surface belongs to the app process and `bin/java` in another process can't
 * get it; started in-process, `CallbackBridge.setupBridgeWindow(Surface)` finally means something.
 *
 * The risk is stated openly: if the JVM dies the whole app dies (no process isolation to fall
 * back on), so it sits behind a separate "experiment" button and exec remains the default path.
 */
object InProcessLauncher {

    private const val TAG = "PCLA-JVM"

    /** Java 8's libjli is under lib/aarch64/jli and 17's under lib; searching beats hard-coded paths. */
    private fun findLib(home: File, name: String): File? {
        val stack = ArrayDeque(listOf(File(home, "lib")))
        var seen = 0
        while (stack.isNotEmpty() && seen < 400) {
            val dir = stack.removeFirst()
            seen++
            val hit = File(dir, name)
            if (hit.isFile) return hit
            dir.listFiles()?.filter { it.isDirectory }?.forEach { stack.addLast(it) }
        }
        return null
    }

    data class Attempt(val argv: List<String>)

    /** Load libs without starting the VM, to verify symbol resolution on its own. */
    fun loadNative(context: Context): Result<String> = runCatching {
        val libs = NativeBootstrap.loadLibs()
        val m = VMLauncher::class.java.getDeclaredMethod("launchJVM", Array<String>::class.java)
        "原生库：" + libs.joinToString("、") + "；launchJVM 已找到：" + m.name
    }

    /**
     * Start an in-process JVM with exactly the same arguments as the exec path.
     * null means native returned directly (some implementations are async).
     */
    fun launch(
        context: Context,
        installed: GameInstaller.Installed,
        heapMb: Int,
        onLine: (String) -> Unit,
    ): Int? {
        NativeBootstrap.loadLibs().forEach { onLine("原生库 $it") }
        val runtime = RuntimePack.ensure(context, installed.javaMajor)
        // in-process there is no bin/java; native dlopens the JVM itself, so we only pass args and paths
        System.setProperty("java.home", runtime.home.absolutePath)
        System.setProperty("java.library.path", GameInstaller.nativesPath(context, installed.root))
        // must be in the same order as java.library.path: bundled arm64 first, manifest-extracted (x86) after
        System.setProperty("org.lwjgl.librarypath", GameInstaller.nativesPath(context, installed.root))

        // FCL's native prep before launchMinecraft: env vars + pre-dlopen every JRE .so into the
        // global symbol table. Skip it and JLI's lookup of libjli.so by soname fails.
        NativeBootstrap.prepare(context, runtime, installed.root).forEach { onLine(it) }

        // native launchJVM is JLI_Launch(argc, argv): argv[0] must be java's absolute path
        // (JLI derives java.home from its directory), then come JVM args + main class + game args.
        // So argv[0] must **not** be dropped here; this is the same command as the exec path.
        val argv = LaunchCommand.build(
            javaBin = runtime.javaBin.absolutePath,
            plan = installed.plan,
            heapMb = heapMb,
            nativesDir = GameInstaller.nativesPath(context, installed.root),
            extraJvm = listOf("-Djava.home=" + runtime.home.absolutePath) + AwtRuntime.jvmProps(1280, 720),
        )
        // Pojav's jre_launcher.c gets JLI_Launch via dlopen("libjli.so"); the app process's
        // LD_LIBRARY_PATH can't be changed, so load the key .so files by absolute path first and
        // a later dlopen by soname hits the already-loaded copy.
        listOf("libjli.so", "libjvm.so", "libjava.so", "libverify.so", "libzip.so", "libjimage.so")
            .mapNotNull { name -> findLib(runtime.home, name) }
            .forEach { so ->
                runCatching { System.load(so.absolutePath) }
                    .onFailure { onLine("预载 " + so.name + " 失败：" + (it.message ?: "")) }
            }
        onLine("同进程启动：${argv.size} 个参数，主类 " + installed.plan.mainClass)
        onLine(argv.take(6).joinToString(" "))
        val started = System.currentTimeMillis()
        // a native crash leaves no stack trace, so drop an "about to call the JVM" note first:
        // if the process dies after this line, at least we know it died in native, not before.
        runCatching {
            val dir = context.getExternalFilesDir("crash") ?: context.filesDir
            dir.mkdirs()
            File(dir, "last-launch.txt").writeText(
                "time=${System.currentTimeMillis()}\nversion=${BuildConfig.VERSION_NAME}\n" +
                    argv.joinToString("\n")
            )
        }
        val code = VMLauncher.launchJVM(argv.toTypedArray())
        Log.i(TAG, "launchJVM 返回 $code，耗时 ${System.currentTimeMillis() - started}ms")
        return code
    }
}
