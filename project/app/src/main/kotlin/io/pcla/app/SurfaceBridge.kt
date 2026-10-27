package io.pcla.app

import android.view.Surface

/**
 * 把 Android 的 Surface 交给随包的 libpojavexec.so。
 *
 * 这一层非做不可：那个 so 里的入口名是按 FCL/Pojav 的包名生成的
 * （Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow），我们的包名是
 * io.pcla.app，Java 侧声明 native 永远对不上符号，只能 dlopen + dlsym
 * 直接调函数指针，见 src/main/cpp/surface_bridge.c。
 */
object SurfaceBridge {
    init {
        System.loadLibrary("pcla_surface")
    }

    private external fun install(surface: Surface): Int
    private external fun probe(): String

    /** libpojavexec.so 里有哪些 Surface 入口，给自检卡用。 */
    fun describe(): String = runCatching { probe() }.getOrElse { "加载失败：${it.message}" }

    /** 返回 null 表示成功，否则是中文原因。 */
    fun attach(surface: Surface): String? = when (val r = runCatching { install(surface) }.getOrElse { -99 }) {
        0 -> null
        -1 -> "dlopen libpojavexec.so 失败"
        -2 -> "so 里没有 Surface 入口"
        -3 -> "Surface 还没准备好"
        -99 -> "调用 native 时崩了，见 logcat（tag PclaSurface）"
        else -> "未知错误 $r"
    }
}
