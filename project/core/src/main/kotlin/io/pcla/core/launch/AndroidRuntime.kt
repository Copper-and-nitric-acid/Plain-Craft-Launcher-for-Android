package io.pcla.core.launch

/**
 * 手机上的 LWJGL 替身。
 *
 * 1.16.5 的清单里原生库是 `lwjgl-3.2.2-natives-linux.jar`，那是给 x86_64 Linux 编的，
 * 在 arm64 手机上解出来的 .so 一加载就是 ELF 报错。而 LWJGL 3.2.2 官方根本没有
 * natives-linux-arm64（Maven 上 404，3.3.3 才补上）。
 *
 * 所以走 Pojav/FCL 的老办法：把整个 org.lwjgl 栈换成随包带的 3.3.3 arm64 构建。
 * 换的是运行期：清单里 org.lwjgl.* 的库不下载，classpath 与 java.library.path
 * 指向 assets 里拷出来的这一套。其它库（netty、gson、authlib…）照常下载。
 */
object AndroidRuntime {
    const val BUNDLE_DIR = "lwjgl/3.3.3"

    val JARS = listOf(
        // merged-modules 是 FCL 的 Android 版核心：org.lwjgl.glfw / org.lwjgl.opengl 的
        // Java 实现都在里面（glfw 那套要调 libpojavexec.so 的 CallbackBridge），
        // 必须排在 lwjgl.jar 之前，否则先命中官方 jar 里的 x86 绑定。
        "lwjgl-3.3.3-merged-modules.jar",
        "lwjgl.jar",
        "lwjgl-lwjglx.jar",
        "lwjgl-freetype.jar",
        "lwjgl-stb.jar",
        "lwjgl-tinyfd.jar",
        "lwjgl-openal.jar",
        "lwjgl-nanovg.jar",
        "lwjgl-vma.jar",
    )

    val NATIVES = listOf(
        "liblwjgl.so",
        "liblwjgl_opengl.so",
        "liblwjgl_stb.so",
        "liblwjgl_tinyfd.so",
        "liblwjgl_nanovg.so",
        "liblwjgl_vma.so",
        "libfreetype.so",
    )

    fun isLwjgl(path: String): Boolean {
        val p = path.replace('\\', '/')
        return p.contains("org/lwjgl/") || p.contains("org.lwjgl")
    }

    /** 去掉清单里所有 org.lwjgl 的库，返回新清单与被去掉的条数。 */
    fun strip(meta: VersionMeta): Pair<VersionMeta, Int> {
        val keep = ArrayList<Library>()
        var dropped = 0
        meta.libraries.forEach { lib ->
            if (isLwjgl(lib.name)) dropped++ else keep.add(lib)
        }
        return meta.copy(libraries = keep) to dropped
    }
}
