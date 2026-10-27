package io.pcla.core.game

import java.io.File
import java.util.zip.ZipFile

/**
 * 把 natives jar 里的 .so 解到指定目录。
 *
 * 为什么不走 jniLibs：Android 的 jniLibs 里同名 .so 只能有一份，
 * 而 MC 的库之间会互相覆盖（同一个 liblwjgl.so 不同版本）。
 * 由启动器自己解到私有目录，再交给 -Djava.library.path。
 */
object NativeExtractor {

    private val LIB_SUFFIXES = listOf(".so", ".dylib", ".dll")

    data class Result(val extracted: Int, val skipped: Int, val libs: List<String>)

    fun extract(jar: File, destDir: File, excludes: List<String> = emptyList()): Result {
        if (!jar.isFile) return Result(0, 0, emptyList())
        destDir.mkdirs()
        var extracted = 0
        var skipped = 0
        val libs = ArrayList<String>()
        ZipFile(jar).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith("META-INF/")) continue
                if (excludes.any { name.startsWith(it.trimEnd('/')) }) {
                    skipped++
                    continue
                }
                if (LIB_SUFFIXES.none { name.endsWith(it, ignoreCase = true) }) {
                    skipped++
                    continue
                }
                val out = File(destDir, name.substringAfterLast('/'))
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out.setExecutable(true, false)
                libs.add(out.name)
                extracted++
            }
        }
        return Result(extracted, skipped, libs.sorted())
    }

    fun extractAll(
        jars: List<Pair<File, List<String>>>,
        destDir: File,
    ): Result {
        var total = 0
        var skipped = 0
        val libs = LinkedHashSet<String>()
        jars.forEach { (jar, excludes) ->
            val r = extract(jar, destDir, excludes)
            total += r.extracted
            skipped += r.skipped
            libs.addAll(r.libs)
        }
        return Result(total, skipped, libs.sorted())
    }
}
