package io.pcla.core.launch

/**
 * AWT 那一侧：让 Minecraft 的窗口能在一台没有 X11 的 Android 上开出来。
 *
 * 链路是 Caciocavallo 的 CTC 工具包：AWT 的 Frame/Canvas 由它实现，
 * 真正的像素由 libpojavexec_awt.so / libawt_xawt.so 交给 Android。
 * 缺了这几个 jar，LWJGL 3 建 GLFW 窗口时会一路走到 AWT 并抛
 * java.awt.HeadlessException。
 */
object AwtRuntime {
    const val BUNDLE_DIR = "caciocavallo"

    /** 进 classpath 的顺序：shared 先，androidnw 后。 */
    val JARS = listOf(
        "cacio-shared-1.10-SNAPSHOT.jar",
        "cacio-androidnw-1.10-SNAPSHOT.jar",
    )

    /** 只给 JVM 用，不进 classpath。 */
    const val RESCONF = "ResConfHack.jar"

    val TOOLKIT = "net.java.openjdk.cacio.ctc.CTCToolkit"

    fun jvmProps(width: Int, height: Int): List<String> = listOf(
        "-Djava.awt.headless=false",
        "-Dawt.toolkit=$TOOLKIT",
        "-Dcacio.managed.screensize=${width}x$height",
    )
}
