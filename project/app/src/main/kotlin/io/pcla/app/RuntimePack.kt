package io.pcla.app

import android.content.Context
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * Unpacker for the bundled JRE.
 *
 * assets holds FCL's runtime, one Java split into two parts:
 *   universal.tar.xz (rt.jar.pack / lib/security / fonts and other shared bits, 9–33 MB)
 *   bin-arm64.tar.xz (the java executable plus libjli and libjvm, 4–7 MB)
 * They must be stacked in universal → bin order; bin alone lacks a lot beyond lib/modules.
 *
 * First launch unpacks into filesDir/runtime/jre<N>/ and reuses it afterwards. It is not run
 * straight out of assets because assets is a compressed stream: no exec, no executable bit.
 */
object RuntimePack {

    data class Installed(val major: Int, val home: File) {
        val javaBin: File get() = File(home, "bin/java")
    }

    /** Marker written after unpacking, so a kill mid-unpack isn't mistaken for completion. */
    private const val MARKER = ".pcla-ok"

    fun home(context: Context, major: Int): File = File(context.filesDir, "runtime/jre$major")

    fun isInstalled(context: Context, major: Int): Boolean =
        File(home(context, major), MARKER).exists() &&
            File(File(home(context, major), "bin"), "java").exists()

    /**
     * Make sure a JRE is unpacked. Blocking call, run it off the main thread.
     * @param onProgress bytes-read callback; for xz this is read progress, not disk-write progress.
     */
    fun ensure(context: Context, major: Int, onProgress: (Long) -> Unit = {}): Installed {
        val finalDir = home(context, major)
        if (isInstalled(context, major)) return Installed(major, finalDir)

        val staging = File(context.filesDir, "runtime/.staging-jre$major")
        staging.deleteRecursively()
        staging.mkdirs()

        // universal is the shared part, bin is this machine's executable and libjvm; order matters:
        // lib/*.so from bin overwrites any same-named file from universal.
        listOf("universal.tar.xz", "bin-arm64.tar.xz").forEach { name ->
            val asset = "java/jre$major/$name"
            if (!assetExists(context, asset)) {
                if (name == "universal.tar.xz") return@forEach
                error("本包没内置 Java $major 的运行时（只带 8 和 17）")
            }
            context.assets.open(asset).use { raw ->
                XZInputStream(raw).use { xz ->
                    untar(CountingStream(xz, onProgress), staging)
                }
            }
        }

        // tar's mode field isn't reliably written by every packing tool; re-add the exec bit to
        // everything under bin/, or exec returns "Permission denied" and looks like a broken JRE
        File(staging, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(staging, "lib/jspawnhelper").takeIf { it.isFile }?.setExecutable(true, false)

        File(staging, MARKER).writeText(major.toString())
        finalDir.parentFile?.mkdirs()
        finalDir.deleteRecursively()
        if (!staging.renameTo(finalDir)) error("运行时解包失败：无法重命名到 ${finalDir.absolutePath}")
        return Installed(major, finalDir)
    }

    /**
     * What comes out is the Android build of java (bionic); the caller must set LD_LIBRARY_PATH.
     *
     * The directory layout must not be guessed: in the pack, Java 8's layout is
     *   bin/java (a 7552-byte little launcher)
     *   lib/aarch64/jli/libjli.so     ← the first thing bin/java looks for
     *   lib/aarch64/server/libjvm.so
     *   lib/aarch64/jvm.cfg
     * while Java 17 uses lib/libjli.so + lib/server/libjvm.so. Both sets are listed;
     * missing directories don't affect loading.
     */
    fun libraryPath(installed: Installed): String {
        val home = installed.home
        return listOf(
            "lib/aarch64/jli", "lib/aarch64/server", "lib/aarch64",
            "lib/jli", "lib/server", "lib",
        )
            .map { File(home, it) }
            .filter { it.isDirectory }
            .joinToString(":") { it.absolutePath }
            .ifEmpty { File(home, "lib").absolutePath }
    }

    private fun assetExists(context: Context, path: String): Boolean = try {
        context.assets.open(path).close(); true
    } catch (_: Exception) {
        false
    }

    private class CountingStream(private val inner: InputStream, private val cb: (Long) -> Unit) : InputStream() {
        private var total = 0L
        private var last = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) tick(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) tick(it.toLong()) }
        override fun close() = inner.close()
        private fun tick(n: Long) {
            total += n
            if (total - last > 256 * 1024) { last = total; cb(total) }
        }
    }

    /** Minimal tar reader: only regular files, directories, symlinks and GNU long names. */
    private fun untar(input: InputStream, dest: File) {
        val header = ByteArray(512)
        var pendingLongName: String? = null
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it == 0.toByte() }) continue

            val rawName = pendingLongName ?: cstr(header, 0, 100)
            pendingLongName = null
            val size = oct(header, 124, 12)
            val type = header[156].toInt().toChar()
            val prefix = cstr(header, 345, 155)
            val name = if (prefix.isEmpty()) rawName else "$prefix/$rawName"

            if (type == 'L') {
                val buf = ByteArray(size.toInt()); readFully(input, buf)
                pendingLongName = String(buf, Charsets.UTF_8).trimEnd('\u0000')
                skipPadding(input, size)
                continue
            }

            val target = resolve(dest, name)
            when (type) {
                '5' -> target.mkdirs()
                '2' -> {
                    val buf = ByteArray(size.toInt()); readFully(input, buf)
                    val link = String(buf, Charsets.UTF_8).trimEnd('\u0000')
                    target.parentFile?.mkdirs()
                    try {
                        Files.deleteIfExists(target.toPath())
                        Files.createSymbolicLink(target.toPath(), File(link).toPath())
                    } catch (_: Throwable) {
                        // no symlink support → write an empty file, so the bin/java existence check still errors clearly
                        target.parentFile?.mkdirs()
                        target.writeText("")
                    }
                }
                else -> {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        var left = size
                        val buf = ByteArray(64 * 1024)
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                    // the exec bit is in tar's mode field, but only what's under bin/ needs it
                    if (name.contains("/bin/") || name.contains("lib/jspawnhelper")) {
                        target.setExecutable(true, false)
                    }
                }
            }
            if (type == '0' || type == 0.toChar() || type == '2' || type == 'L') skipPadding(input, size)
        }
    }

    private fun resolve(root: File, name: String): File {
        val clean = name.trimStart('/')
        require(!clean.contains("..")) { "tar 里有非法路径: $name" }
        return File(root, clean)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off > 0
            off += n
        }
        return true
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        var skipped = 0
        while (skipped < pad) {
            val n = input.read(ByteArray(pad - skipped))
            if (n < 0) return
            skipped += n
        }
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun oct(b: ByteArray, off: Int, len: Int): Long {
        val s = cstr(b, off, len).trim().trimEnd('\u0000')
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8) ?: 0
    }
}
