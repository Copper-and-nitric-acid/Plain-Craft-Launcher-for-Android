package io.pcla.app

import android.content.Context
import java.io.File

/**
 * "Power-on self-test" for the in-process JVM: redo FCL's native prep before launchMinecraft.
 *
 * Order and content follow FCL's FCLauncher.setEnv / setUpJavaRuntime /
 * setupGraphicAndSoundEngine (see FCL source 1.3.1.8). A missing step never shows up as an
 * error, but as "the JVM starts yet the game won't" or "dlopen can't find libjli.so".
 */
object NativeBootstrap {
    init {
        System.loadLibrary("pcla_surface")
    }

    private external fun setenv(key: String, value: String): Int
    private external fun dlopen(path: String): Long
    private external fun chdir(path: String): Int

    /** Load the three native libs. libfcl must be first: pojavexec's hooks depend on its tables. */
    fun loadLibs(): List<String> {
        val loaded = ArrayList<String>()
        for (name in listOf("fcl", "pojavexec", "pojavexec_awt")) {
            runCatching { System.loadLibrary(name) }
                .onSuccess { loaded += name }
                .onFailure { loaded += "$name(失败: ${it.message})" }
        }
        return loaded
    }

    /** Env vars + pre-dlopen. Returns a human-readable report, one line per step into the log. */
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
            // this gl4es + GLES2 group is FCL's default for ID_GL4ES
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

        // load every .so in the JRE into the global symbol table first (that's what setUpJavaRuntime does)
        val soFiles = ArrayList<File>()
        fun walk(dir: File, depth: Int = 0) {
            if (depth > 4) return
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".so")) soFiles += f
                else if (f.isDirectory) walk(f, depth + 1)
            }
        }
        walk(runtime.home)
        // order: jli/jvm first, then the rest (some .so files depend on symbols in libjvm)
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

        // sound + GL backends
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
