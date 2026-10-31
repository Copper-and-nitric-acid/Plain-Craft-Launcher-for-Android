package io.pcla.app

import android.view.Surface

/**
 * Hand the Android Surface to the bundled libpojavexec.so.
 *
 * The entry name is Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow — a JNI symbol name is
 * decided by the class declaring the native method, so the bundle ships a class of the same name,
 * app/src/main/java/org/lwjgl/glfw/CallbackBridge.java, and the native method resolves (primary
 * path). The dlopen + dlsym in src/main/cpp/surface_bridge.c is the fallback for the same thing.
 */
object SurfaceBridge {
    init {
        System.loadLibrary("pcla_surface")
    }

    private external fun install(surface: Surface): Int
    private external fun probe(): String

    /** Which Surface entry points libpojavexec.so has, for the self-check card. */
    fun describe(): String = runCatching { probe() }.getOrElse { "加载失败：${it.message}" }

    /**
     * Reflectively call `org.lwjgl.glfw.CallbackBridge.setupBridgeWindow(Object)`.
     *
     * Why reflection instead of a direct reference: that class lives in `src/main/java` (the Java
     * source set), and the Kotlin compiler's visibility into this module's Java sources depends on
     * the AGP version and build order (CI #44 died on `Unresolved reference: lwjgl`); reflection
     * only needs the class at runtime, zero compile-time coupling, and dlsym still backstops it.
     */
    private fun directAttach(surface: Surface): Throwable? = try {
        val cls = Class.forName("org.lwjgl.glfw.CallbackBridge")
        cls.getMethod("setupBridgeWindow", Any::class.java).invoke(null, surface)
        null
    } catch (t: java.lang.reflect.InvocationTargetException) {
        t.cause ?: t
    } catch (t: Throwable) {
        t
    }

    /** null means success; otherwise a Chinese-language reason. */
    fun attach(surface: Surface): String? {
        val direct = directAttach(surface)
        if (direct == null) return null
        // fallback: dlopen + dlsym
        return when (val r = runCatching { install(surface) }.getOrElse { -99 }) {
            0 -> null
            -1 -> "两条路都失败：${direct.message}；dlopen libpojavexec.so 也失败"
            -2 -> "两条路都失败：${direct.message}；so 里没有 Surface 入口"
            -3 -> "Surface 还没准备好"
            -99 -> "调用 native 时崩了，见 logcat（tag PclaSurface）"
            else -> "两条路都失败：${direct.message}；dlopen 返回 $r"
        }
    }
}
