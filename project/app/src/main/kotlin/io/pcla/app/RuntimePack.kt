package io.pcla.app

import android.content.Context
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * 内置 JRE 的解包器。
 *
 * assets 里是 FCL 那套 `bin-arm64.tar.xz`（每套 4–7 MB），首次启动时解到
 * filesDir/runtime/jre<N>/，之后直接复用。不放进 assets 直接当 java 用，
 * 因为 assets 是压缩流，不能 exec，也没有可执行位。
 */
object RuntimePack {

    data class Installed(val major: Int, val home: File) {
        val javaBin: File get() = File(home, "bin/java")
    }

    /** 解压后写这个标记，避免解到一半被系统杀掉后误判为已完成。 */
    private const val MARKER = ".pcla-ok"

    fun home(context: Context, major: Int): File = File(context.filesDir, "runtime/jre$major")

    fun isInstalled(context: Context, major: Int): Boolean =
        File(home(context, major), MARKER).exists() &&
            File(File(home(context, major), "bin"), "java").exists()

    /**
     * 确保某个 JRE 已解包。阻塞调用，放后台线程。
     * @param onProgress 已读字节回调，解压 xz 时进度是「读取进度」不是「磁盘写入进度」。
     */
    fun ensure(context: Context, major: Int, onProgress: (Long) -> Unit = {}): Installed {
        val finalDir = home(context, major)
        if (isInstalled(context, major)) return Installed(major, finalDir)

        val staging = File(context.filesDir, "runtime/.staging-jre$major")
        staging.deleteRecursively()
        staging.mkdirs()

        context.assets.open("java/jre$major/bin-arm64.tar.xz").use { raw ->
            XZInputStream(raw).use { xz ->
                untar(CountingStream(xz, onProgress), staging)
            }
        }
        File(staging, MARKER).writeText(major.toString())
        finalDir.deleteRecursively()
        finalDir.parentFile?.mkdirs()
        if (!staging.renameTo(finalDir)) {
            staging.copyRecursively(finalDir, overwrite = true)
            staging.deleteRecursively()
        }
        return Installed(major, finalDir)
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

    /** 极简 tar 读取：只处理普通文件、目录、符号链接、GNU 长文件名。 */
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
                        // 不支持符号链接就退化成写一个空文件，让 bin/java 的存在性检查仍能给出明确报错
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
                    // tar 里的可执行位在 mode 字段，但只有 bin/ 下的需要
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
