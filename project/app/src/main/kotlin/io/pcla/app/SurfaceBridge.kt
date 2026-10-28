package io.pcla.app

import android.view.Surface

/**
 * 把 Android 的 Surface 交给随包的 libpojavexec.so。
 *
 * 入口名是 Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow——JNI 的符号名
 * 由「声明 native 方法的类」决定，所以包里有 app/src/main/java/org/lwjgl/glfw/
 * CallbackBridge.java 这个同名类，直接声明 native 即可正常解析（首选）。
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
     * 返回 null 表示成功，否则是中文原因。
     *
     * 优先走「同名类 + 真 JNI」这条路：App 进程里放一个与上游全同名的
     * `org.lwjgl.glfw.CallbackBridge`，native 方法由 ART 正常解析到
     * libpojavexec.so 里已编译好的实现。这比 dlsym 直接调函数指针更干净——
     * JNIEnv 是当前线程的、jclass 是真实的类、异常也能照常抛出来。
     * dlsym 那条留着兜底（上面的类万一没打进包）。
     */
    fun attach(surface: Surface): String? {
        val direct = runCatching { org.lwjgl.glfw.CallbackBridge.setupBridgeWindow(surface) }
            .exceptionOrNull()
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
