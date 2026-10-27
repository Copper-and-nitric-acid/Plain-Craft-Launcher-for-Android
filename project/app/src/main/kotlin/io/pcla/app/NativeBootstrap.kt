package io.pcla.app

import android.content.Context
import java.io.File

/**
 * 同进程起 JVM 的「开机自检」：把 FCL 在 launchMinecraft 之前做的 native 准备照做一遍。
 *
 * 顺序和内容都对着 FCL 的 FCLauncher.setEnv / setUpJavaRuntime /
 * setupGraphicAndSoundEngine 来的（见 FCL 源码 1.3.1.8）。缺任何一步的表现都不是
 * 报错，而是「JVM 起来了但起不来游戏」或者「dlopen 找不到 libjli.so」。
 */
object NativeBootstrap {
    init {
        System.loadLibrary("pcla_surface")
    }

    private external fun setenv(key: String, value: String): Int
    private external fun dlopen(path: String): Long
    private external fun chdir(path: String): Int

    /** 载入三个原生库。libfcl 必须最先，pojavexec 的 hook 依赖它注册的表。 */
    fun loadLibs(): List<String> {
        val loaded = ArrayList<String>()
        for (name in listOf("fcl", "pojavexec", "pojavexec_awt")) {
            runCatching { System.loadLibrary(name) }
                .onSuccess { loaded += name }
                .onFailure { loaded += "$name(失败: ${it.message})" }
        }
        return loaded
    }

    /** 环境变量 + 预 dlopen。返回人看的报告，逐条进日志。 */
    fun prepare(context: Context, runtime: RuntimePack.Installed, gameDir: File): List<String> {
        val out = ArrayList<String>()
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val cache = context.cacheDir.absolutePath
        val libPath = listOf(
            GameInstaller.nativesPath(context, gameDir),
            RuntimePack.libraryPath(runtime),
        ).joinToString(":")

        val envMap = linkedMapOf(
            "HOME" to context.filesDir.absolutePath,
            "TMPDIR" to cache,
            "JAVA_HOME" to runtime.home.absolutePath,
            "PATH" to runtime.home.absolutePath + "/bin:" + System.getenv("PATH").orEmpty(),
            "LD_LIBRARY_PATH" to libPath,
            "POJAV_NATIVEDIR" to nativeDir,
            "FCL_NATIVEDIR" to nativeDir,
            // gl4es + GLES2 这一组是 FCL 里 ID_GL4ES 的默认值
            "POJAV_RENDERER" to "opengles2",
            "LIBGL_ES" to "2",
            "LIBGL_MIPMAP" to "3",
            "LIBGL_NORMALIZE" to "1",
            "LIBGL_NOINTOVLHACK" to "1",
            "LIBGL_NOERROR" to "1",
            "FORCE_VSYNC" to "false",
        )
        for ((k, v) in envMap) {
            runCatching { setenv(k, v) }
            out += "env $k=$v"
        }

        // JRE 里所有 .so 都先载进全局符号表（FCL 的 setUpJavaRuntime 就是这么干的）
        val soFiles = ArrayList<File>()
        fun walk(dir: File, depth: Int = 0) {
            if (depth > 4) return
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".so")) soFiles += f
                else if (f.isDirectory) walk(f, depth + 1)
            }
        }
        walk(runtime.home)
        // 顺序上先 jli/jvm，再剩下的（有些 .so 依赖 libjvm 里的符号）
        val ordered = soFiles.sortedBy {
            when {
                it.name == "libjli.so" -> 0
                it.name == "libjvm.so" -> 1
                else -> 2
            }
        }
        var ok = 0
        for (f in ordered) {
            if (dlopen(f.absolutePath) != 0L) ok++ else out += "✗ dlopen ${f.name}"
        }
        out += "JRE 原生库 ${ok}/${ordered.size} 载入"

        // 声音 + GL 后端
        for (name in listOf("libopenal.so", "libgl4es_114.so", "libglxshim.so")) {
            val f = File(nativeDir, name)
            if (!f.isFile) {
                out += "（没有 $name）"
                continue
            }
            val h = dlopen(f.absolutePath)
            out += if (h != 0L) "✓ dlopen $name" else "✗ dlopen $name"
        }
        chdir(gameDir.absolutePath)
        out += "chdir ${gameDir.absolutePath}"
        return out
    }
}
