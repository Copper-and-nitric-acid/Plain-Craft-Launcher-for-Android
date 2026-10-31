// Two native things that must happen before starting the JVM in-process:
//   1) setenv  — renderer/library paths and temp dirs go through environment variables; Java cannot change this process's real env
//   2) dlopen  — JLI dlopens "libjli.so" by soname, so the absolute-path copy must be loaded
//                into the global symbol table first; only then does the soname lookup hit.
// Neither has a Java API (System.load only resolves filenames from nativeLibraryDir).
#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <unistd.h>
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

// Returns the handle (non-zero = success); on failure returns 0 and logs dlerror.
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
