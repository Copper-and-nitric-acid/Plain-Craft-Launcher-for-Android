package io.pcla.app

import android.content.Context
import io.pcla.core.launch.AndroidRuntime
import java.io.File

/**
 * Copy the jars and .so files from assets/lwjgl/3.3.3 into the game dir, replacing the x86_64
 * LWJGL set from the manifest. arm64 only; copying is cached by size, so re-entering the game
 * doesn't rewrite 4.7MB each time.
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
        val cacio = File(root, io.pcla.core.launch.AwtRuntime.BUNDLE_DIR).apply { mkdirs() }
        io.pcla.core.launch.AwtRuntime.JARS.forEach { name ->
            val asset = io.pcla.core.launch.AwtRuntime.BUNDLE_DIR + "/" + name
            val target = File(cacio, name)
            if (!target.isFile) {
                context.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
                copied++
            }
        }
        return Installed(
            AndroidRuntime.JARS.map { File(out, it).absolutePath } + io.pcla.core.launch.AwtRuntime.JARS.map { File(cacio, it).absolutePath },
            natives.absolutePath,
            copied,
        )
    }
}
