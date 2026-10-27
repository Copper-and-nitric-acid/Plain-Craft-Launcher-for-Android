package io.pcla.app

import android.content.Context
import java.io.File

/** APK 里随包安装的原生库，用来在启动前确认「底座」确实在。 */
data class NativeLibInfo(val name: String, val sizeBytes: Long)

object NativeLibs {

    /** 安装后 lib*.so 的真实路径；useLegacyPackaging=true 才会有这些文件。 */
    fun dir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    fun list(context: Context): List<NativeLibInfo> =
        dir(context).listFiles { f: File -> f.isFile && f.name.endsWith(".so") }
            ?.map { NativeLibInfo(it.name, it.length()) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun totalBytes(context: Context): Long = list(context).sumOf { it.sizeBytes }

    /**
     * 试加载一个库，只用于排障。
     * 注意：zink / vulkan / OSMesa 这类在没建好 GL 上下文时加载可能直接 abort，
     * 所以默认只试 gl4es 这种纯软件转换层，并且只在用户点按钮时执行。
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
