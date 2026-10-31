// PCLA's Surface bridge: hands the android.view.Surface obtained on the Java side to libpojavexec.so.
//
// Why this C layer is needed:
//   libpojavexec.so exports the JNI entry
//     Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow(JNIEnv*, jclass, jobject)
//   JNI names symbols after "which class declares the native method", and our package is io.pcla.app,
//   so no Java signature can hit that symbol. The only way without patching upstream is to
//   dlopen it ourselves, dlsym the function pointer, and call it with the same JNIEnv.
//   The body only does ANativeWindow_fromSurface(env, surface) and stores it; jclass does not matter.
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string.h>

#define TAG "PclaSurface"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

typedef void (*bridge_fn)(JNIEnv *, jclass, jobject);

static const char *SYMS[] = {
    "Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow",
    "Java_com_tungsten_fclauncher_bridge_FCLBridge_setupBridgeSurfaceAWT",
};

static void *open_bridge(void) {
    void *h = dlopen("libpojavexec.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) LOGI("dlopen libpojavexec.so 失败: %s", dlerror());
    return h;
}

// Returns 0 = success; -1 = dlopen failed; -2 = symbol not found.
JNIEXPORT jint JNICALL
Java_io_pcla_app_SurfaceBridge_install(JNIEnv *env, jclass clazz, jobject surface) {
    (void) clazz;
    if (surface == NULL) return -3;
    void *h = open_bridge();
    if (!h) return -1;
    for (unsigned i = 0; i < sizeof(SYMS) / sizeof(SYMS[0]); i++) {
        bridge_fn fn = (bridge_fn) dlsym(h, SYMS[i]);
        if (!fn) continue;
        LOGI("调用 %s", SYMS[i]);
        fn(env, NULL, surface);
        return 0;
    }
    LOGI("libpojavexec.so 里找不到 Surface 入口");
    return -2;
}

// Self-check for the UI: which entries really exist in this .so.
JNIEXPORT jstring JNICALL
Java_io_pcla_app_SurfaceBridge_probe(JNIEnv *env, jclass clazz) {
    (void) clazz;
    void *h = open_bridge();
    if (!h) return (*env)->NewStringUTF(env, "libpojavexec.so 打不开");
    char buf[512];
    buf[0] = 0;
    for (unsigned i = 0; i < sizeof(SYMS) / sizeof(SYMS[0]); i++) {
        if (dlsym(h, SYMS[i])) {
            strncat(buf, SYMS[i] + 4, sizeof(buf) - strlen(buf) - 2);
            strncat(buf, " ", sizeof(buf) - strlen(buf) - 2);
        }
    }
    if (buf[0] == 0) return (*env)->NewStringUTF(env, "没找到 Surface 入口");
    return (*env)->NewStringUTF(env, buf);
}
