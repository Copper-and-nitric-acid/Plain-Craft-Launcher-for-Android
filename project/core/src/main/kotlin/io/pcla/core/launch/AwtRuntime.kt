package io.pcla.core.launch

/**
 * The AWT side: let Minecraft's window open on an Android device with no X11.
 *
 * The chain is Caciocavallo's CTC toolkit: AWT's Frame/Canvas are implemented by it,
 * and the actual pixels are handed to Android by libpojavexec_awt.so / libawt_xawt.so.
 * Without these jars, LWJGL 3 falls through to AWT when creating a GLFW window and throws
 * java.awt.HeadlessException.
 */
object AwtRuntime {
    const val BUNDLE_DIR = "caciocavallo"

    /** Classpath order: shared first, androidnw after. */
    val JARS = listOf(
        "cacio-shared-1.10-SNAPSHOT.jar",
        "cacio-androidnw-1.10-SNAPSHOT.jar",
    )

    /** For the JVM only, not on the classpath. */
    const val RESCONF = "ResConfHack.jar"

    val TOOLKIT = "net.java.openjdk.cacio.ctc.CTCToolkit"

    fun jvmProps(width: Int, height: Int): List<String> = listOf(
        "-Djava.awt.headless=false",
        "-Dawt.toolkit=$TOOLKIT",
        "-Dcacio.managed.screensize=${width}x$height",
    )
}
