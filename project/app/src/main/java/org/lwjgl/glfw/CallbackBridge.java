/*
 * 这个类只为一件事存在：让 libpojavexec.so 里的
 *   Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow(JNIEnv*, jclass, jobject)
 * 能被真正的 JNI 调用命中。
 *
 * 为什么不能省：JNI 的符号名由「声明 native 方法的类」决定，而不是调用方。
 * 游戏进程里的 CallbackBridge 来自 lwjgl-3.3.3-merged-modules.jar，那是 JVM 侧
 * （JLI 自己的类加载器）的类；App 进程看不到它，也没法替它把 Surface 塞进去。
 * 于是在 App 进程里放一个同名类，方法签名与上游完全一致，静态块里把
 * libpojavexec.so 载进来，native 方法就自然解析到那个已编译好的实现上。
 *
 * 与 merged-modules 里的同名类不冲突：一个是 App dex（ART），一个是 JVM
 * classpath（HotSpot 式加载器），两个世界互不可见。
 *
 * 上游：FoldCraftLauncher / PojavLauncher（GPL-3.0），此处仅保留签名声明。
 */
package org.lwjgl.glfw;

public final class CallbackBridge {

    static {
        System.loadLibrary("pojavexec");
    }

    private CallbackBridge() {
    }

    /**
     * 把 android.view.Surface 交给 native，内部转成 ANativeWindow 保存，
     * 之后 glfwCreateWindow 才有窗口可用。
     */
    public static native void setupBridgeWindow(Object surface);
}
