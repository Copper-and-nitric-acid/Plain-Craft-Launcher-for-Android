package io.pcla.app

import android.content.Context
import java.io.File

/** Native libs shipped in the APK, used to confirm the "foundation" is really there pre-launch. */
data class NativeLibInfo(val name: String, val sizeBytes: Long)

object NativeLibs {

    /** Real path of lib*.so after install; the files only exist with useLegacyPackaging=true. */
    fun dir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    fun list(context: Context): List<NativeLibInfo> =
        dir(context).listFiles { f: File -> f.isFile && f.name.endsWith(".so") }
            ?.map { NativeLibInfo(it.name, it.length()) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun totalBytes(context: Context): Long = list(context).sumOf { it.sizeBytes }

    /**
     * Try loading one library, for troubleshooting only.
     * Note: zink / vulkan / OSMesa can abort outright when loaded without a ready GL context,
     * so by default try only a pure software layer like gl4es, and only on a user button tap.
     */
    fun probeLoad(context: Context, name: String): String? {
        val f = File(dir(context), name)
        if (!f.exists()) return "没找到 ${f.absolutePath}"
        return try {
            System.load(f.absolutePath)
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message ?: "无消息"}"
        }
    }
}
