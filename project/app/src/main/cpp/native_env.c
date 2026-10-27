// 同进程起 JVM 之前必须做的两件 native 事：
//   1) setenv  —— 渲染器/库路径/临时目录都靠环境变量传，Java 侧改不了本进程的真环境
//   2) dlopen  —— JLI 里是按 soname dlopen("libjli.so") 的，必须先把绝对路径的那份
//                载进全局符号表，之后按 soname 才能命中。
// 这两件事都没有 Java API 可用（System.load 只能按文件名从 nativeLibraryDir 找）。
#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <android/log.h>

#define TAG "PclaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_io_pcla_app_NativeBootstrap_setenv(JNIEnv *env, jclass clazz, jstring key, jstring value) {
    (void) clazz;
    const char *k = (*env)->GetStringUTFChars(env, key, NULL);
    const char *v = (*env)->GetStringUTFChars(env, value, NULL);
    int r = setenv(k, v, 1);
    LOGI("setenv %s=%s", k, v);
    (*env)->ReleaseStringUTFChars(env, key, k);
    (*env)->ReleaseStringUTFChars(env, value, v);
    return r;
}

// 返回 handle（非 0 = 成功），失败返回 0 并把 dlerror 写进日志。
JNIEXPORT jlong JNICALL
Java_io_pcla_app_NativeBootstrap_dlopen(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    void *h = dlopen(p, RTLD_NOW | RTLD_GLOBAL);
    if (!h) LOGE("dlopen 失败 %s: %s", p, dlerror());
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong) (intptr_t) h;
}

JNIEXPORT jint JNICALL
Java_io_pcla_app_NativeBootstrap_chdir(JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    int r = chdir(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return r;
}
