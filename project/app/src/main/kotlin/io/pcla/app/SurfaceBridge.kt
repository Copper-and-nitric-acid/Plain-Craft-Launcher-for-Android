package io.pcla.app

import android.view.Surface

/**
 * 把 Android 的 Surface 交给随包的 libpojavexec.so。
 *
 * 入口名是 Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow——JNI 的符号名
 * 由「声明 native 方法的类」决定，所以包里放了 app/src/main/java/org/lwjgl/glfw/
 * CallbackBridge.java 这个同名类，native 方法能正常解析（首选路径）。
 * src/main/cpp/surface_bridge.c 里的 dlopen + dlsym 是同一件事的兜底写法。
 */
object SurfaceBridge {
    init {
        System.loadLibrary("pcla_surface")
    }

    private external fun install(surface: Surface): Int
    private external fun probe(): String

    /** libpojavexec.so 里有哪些 Surface 入口，给自检卡用。 */
    fun describe(): String = runCatching { probe() }.getOrElse { "加载失败：${it.message}" }

    /**
     * 反射调 `org.lwjgl.glfw.CallbackBridge.setupBridgeWindow(Object)`。
     *
     * 为什么用反射而不是直接引用：那个类在 `src/main/java`（Java 源集），
     * Kotlin 编译器对本模块 Java 源集的可见性受 AGP 版本与编译顺序影响
     * （CI #44 就栽在 `Unresolved reference: lwjgl`），反射只依赖运行时
     * 类是否存在，编译期零耦合；类没打进包时还有 dlsym 兜底。
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

    /** 返回 null 表示成功，否则是中文原因。 */
    fun attach(surface: Surface): String? {
        val direct = directAttach(surface)
        if (direct == null) return null
        // 兜底：dlopen + dlsym
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
