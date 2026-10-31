package io.pcla.core.launch

/**
 * The LWJGL stand-in on phones.
 *
 * 1.16.5's manifest lists `lwjgl-3.2.2-natives-linux.jar` as the native library, built for x86_64 Linux;
 * on an arm64 phone the extracted .so fails to load with an ELF error. And LWJGL 3.2.2 has no
 * natives-linux-arm64 at all (404 on Maven; only 3.3.3 added it).
 *
 * So we take the old Pojav/FCL route: replace the whole org.lwjgl stack with the bundled 3.3.3 arm64 build.
 * What is swapped is the runtime: org.lwjgl.* libraries in the manifest are not downloaded, and classpath plus
 * java.library.path point at the copy extracted from assets. Other libraries (netty, gson, authlib…) download as usual.
 */
object AndroidRuntime {
    const val BUNDLE_DIR = "lwjgl/3.3.3"

    val JARS = listOf(
        // merged-modules is the core of FCL's Android build: the Java implementations of org.lwjgl.glfw / org.lwjgl.opengl
        // live inside it (the glfw one calls CallbackBridge in libpojavexec.so),
        // and it must come before lwjgl.jar, or the x86 bindings in the official jar get hit first.
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

    /** Strip every org.lwjgl library from the manifest; returns the new manifest and how many were dropped. */
    fun strip(meta: VersionMeta): Pair<VersionMeta, Int> {
        val keep = ArrayList<Library>()
        var dropped = 0
        meta.libraries.forEach { lib ->
            if (isLwjgl(lib.name)) dropped++ else keep.add(lib)
        }
        return meta.copy(libraries = keep) to dropped
    }
}
