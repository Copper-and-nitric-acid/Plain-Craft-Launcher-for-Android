/*
 * This class exists for one thing: so that the
 *   Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow(JNIEnv*, jclass, jobject)
 * symbol in libpojavexec.so can be hit by a real JNI call.
 *
 * Why it cannot be skipped: a JNI symbol name is fixed by the class that declares the native method, not by the caller.
 * The game process's CallbackBridge comes from lwjgl-3.3.3-merged-modules.jar — a JVM-side
 * class (JLI's own class loader); the app process cannot see it, and cannot push the Surface into it.
 * So we put a same-named class in the app process with a signature identical to upstream; the static
 * block loads libpojavexec.so and the native method then resolves to that prebuilt implementation.
 *
 * No clash with the same-named class in merged-modules: one is app dex (ART), the other is the JVM
 * classpath (a HotSpot-style loader); the two worlds cannot see each other.
 *
 * Upstream: FoldCraftLauncher / PojavLauncher (GPL-3.0); only the signature declaration is kept here.
 */
package org.lwjgl.glfw;

public final class CallbackBridge {

    static {
        System.loadLibrary("pojavexec");
    }

    private CallbackBridge() {
    }

    /**
     * Hands the android.view.Surface to native, which turns it into an ANativeWindow and stores it;
     * only then does glfwCreateWindow have a window to use.
     */
    public static native void setupBridgeWindow(Object surface);
}
