// PCLA 的 Surface 桥：把 Java 侧拿到的 android.view.Surface 交给 libpojavexec.so。
//
// 为什么要这层 C：
//   libpojavexec.so 导出的 JNI 入口名是
//     Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow(JNIEnv*, jclass, jobject)
//   JNI 按「谁声明 native 方法」定符号名，我们的包名是 io.pcla.app，
//   所以 Java 里无论怎么写都命中不了这个符号。唯一不依赖上游源码的办法是
//   自己 dlopen 再 dlsym 拿到函数指针，直接用同一个 JNIEnv 调过去。
//   函数体只做 ANativeWindow_fromSurface(env, surface) 之后保存，不在乎 jclass。
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

// 返回 0 = 成功；-1 = dlopen 失败；-2 = 符号没找到。
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

// 给界面看的自检：哪些入口在这个 so 里真的存在。
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
