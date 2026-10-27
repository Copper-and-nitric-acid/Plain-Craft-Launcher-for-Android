package io.pcla.app

import android.content.Context
import io.pcla.core.launch.AndroidRuntime
import java.io.File

/**
 * 把 assets/lwjgl/3.3.3 里的 jar 与 .so 拷到游戏目录，替代清单里那套 x86_64 的 LWJGL。
 *
 * 只在 arm64 上做；拷贝带缓存（按大小判断），重复进游戏不会反复写 4.7MB。
 */
object LwjglAssets {

    data class Installed(val jars: List<String>, val nativesDir: String, val copied: Int)

    fun install(context: Context, root: File): Installed {
        val out = File(root, AndroidRuntime.BUNDLE_DIR)
        val natives = File(out, "natives").apply { mkdirs() }
        var copied = 0

        AndroidRuntime.JARS.forEach { name ->
            val asset = "${AndroidRuntime.BUNDLE_DIR}/$name"
            val target = File(out, name)
            val want = runCatching { context.assets.open(asset).use { it.available().toLong() } }.getOrDefault(-1L)
            if (!target.isFile || (want > 0 && target.length() != want)) {
                context.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
                copied++
            }
        }
        AndroidRuntime.NATIVES.forEach { name ->
            val asset = "${AndroidRuntime.BUNDLE_DIR}/natives/$name"
            val target = File(natives, name)
            val want = runCatching { context.assets.open(asset).use { it.available().toLong() } }.getOrDefault(-1L)
            if (!target.isFile || (want > 0 && target.length() != want)) {
                context.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
                copied++
            }
            target.setExecutable(true, false)
        }
        return Installed(AndroidRuntime.JARS.map { File(out, it).absolutePath }, natives.absolutePath, copied)
    }
}
