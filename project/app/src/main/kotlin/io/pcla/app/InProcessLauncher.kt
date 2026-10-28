package io.pcla.app

import android.content.Context
import android.util.Log
import com.oracle.dalvik.VMLauncher
import io.pcla.core.launch.AwtRuntime
import io.pcla.core.launch.LaunchCommand
import java.io.File

/**
 * 实验：不 exec 外部 java，而是在 App 进程里起 JVM（Pojav 的做法）。
 *
 * 为什么赌它：Surface 属于 App 进程，`bin/java` 在另一个进程拿不到。
 * 同进程起来，`CallbackBridge.setupBridgeWindow(Surface)` 才有意义。
 *
 * 风险写在明处：JVM 崩了是整个 App 崩（没有进程隔离兜底），
 * 所以只放在一个单独的「实验」按钮后面，默认路径仍是 exec。
 */
object InProcessLauncher {

    private const val TAG = "PCLA-JVM"

    /** Java 8 的 libjli 在 lib/aarch64/jli 下，17 在 lib 下，搜索比写死路径可靠。 */
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

    /** 只装库、不启 VM，用来单独验证符号能否解析。 */
    fun loadNative(context: Context): Result<String> = runCatching {
        val libs = NativeBootstrap.loadLibs()
        val m = VMLauncher::class.java.getDeclaredMethod("launchJVM", Array<String>::class.java)
        "原生库：" + libs.joinToString("、") + "；launchJVM 已找到：" + m.name
    }

    /**
     * 用与 exec 路径完全相同的参数起同进程 JVM。
     * 返回 null 表示 native 直接返回了（某些实现是异步的）。
     */
    fun launch(
        context: Context,
        installed: GameInstaller.Installed,
        heapMb: Int,
        onLine: (String) -> Unit,
    ): Int? {
        NativeBootstrap.loadLibs().forEach { onLine("原生库 $it") }
        val runtime = RuntimePack.ensure(context, installed.javaMajor)
        // 同进程没有 bin/java 可用，JVM 由 native 自己 dlopen；这里只给参数与路径
        System.setProperty("java.home", runtime.home.absolutePath)
        System.setProperty("java.library.path", GameInstaller.nativesPath(context, installed.root))
        // 必须和 java.library.path 同序：随包 arm64 在前，清单解出的（x86）在后
        System.setProperty("org.lwjgl.librarypath", GameInstaller.nativesPath(context, installed.root))

        // FCL 在 launchMinecraft 之前做的 native 准备：环境变量 + 把 JRE 的 .so
        // 全部预 dlopen 进全局符号表。不做这步，JLI 里按 soname 找 libjli.so 会失败。
        NativeBootstrap.prepare(context, runtime, installed.root).forEach { onLine(it) }

        // native 里的 launchJVM 就是 JLI_Launch(argc, argv)：argv[0] 必须是 java 的绝对路径
        // （JLI 靠它的目录推 java.home），后面才是 JVM 参数 + 主类 + 游戏参数。
        // 所以这里**不能**去掉 argv[0]，和 exec 路径用同一份命令。
        val argv = LaunchCommand.build(
            javaBin = runtime.javaBin.absolutePath,
            plan = installed.plan,
            heapMb = heapMb,
            nativesDir = GameInstaller.nativesPath(context, installed.root),
            extraJvm = listOf("-Djava.home=" + runtime.home.absolutePath) + AwtRuntime.jvmProps(1280, 720),
        )
        // Pojav 的 jre_launcher.c 用 dlopen("libjli.so") 取 JLI_Launch；
        // App 进程的 LD_LIBRARY_PATH 改不了，只能先把关键 so 以绝对路径载进来，
        // 之后 dlopen 按 soname 就能命中已加载的那份。
        listOf("libjli.so", "libjvm.so", "libjava.so", "libverify.so", "libzip.so", "libjimage.so")
            .mapNotNull { name -> findLib(runtime.home, name) }
            .forEach { so ->
                runCatching { System.load(so.absolutePath) }
                    .onFailure { onLine("预载 " + so.name + " 失败：" + (it.message ?: "")) }
            }
        onLine("同进程启动：${argv.size} 个参数，主类 " + installed.plan.mainClass)
        onLine(argv.take(6).joinToString(" "))
        val started = System.currentTimeMillis()
        // native 崩溃不会留堆栈，先落一份「准备调 JVM」的记录：
        // 进程若死在这行之后，至少能确认是 native 里崩的，而不是根本没走到。
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
