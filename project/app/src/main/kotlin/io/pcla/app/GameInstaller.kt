package io.pcla.app

import android.content.Context
import io.pcla.core.asset.AssetIndex
import io.pcla.core.game.GamePlan
import io.pcla.core.game.NativeExtractor
import io.pcla.core.game.RemoteFile
import io.pcla.core.launch.AndroidRuntime
import io.pcla.core.launch.AwtRuntime
import io.pcla.core.launch.ClasspathBuilder
import io.pcla.core.launch.JavaPolicy
import io.pcla.core.launch.LaunchCommand
import io.pcla.core.launch.LaunchPlan
import io.pcla.core.launch.VersionMeta
import io.pcla.core.version.VersionManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 从零装一个版本：manifest → 版本 json → 库/资源/client.jar → 解 natives → 拼命令。
 *
 * 网络请求刻意用 HttpURLConnection 而不是复用 download 模块：
 * 这里是「几个 json + 上千个小文件」的批量场景，需要的是并发铺满和
 * 单文件失败重试，和下载引擎那套分片/限流不是一回事。
 */
object GameInstaller {

    private const val BMCL = "https://bmclapi2.bangbang93.com"
    private val MANIFEST_URLS = listOf(
        "$BMCL/mc/game/version_manifest_v2.json",
        "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json",
    )

    data class Progress(
        val stage: String,
        val done: Int = 0,
        val total: Int = 0,
        val bytes: Long = 0,
        val message: String = "",
    )

    data class Installed(
        val versionId: String,
        val root: File,
        val plan: LaunchPlan,
        val javaMajor: Int,
    )

    /** Mojang 的下载地址在国内经常超时，统一换成 BMCLAPI 镜像。 */
    fun mirrored(url: String): String = url
        .replace("https://launchermeta.mojang.com", BMCL)
        .replace("https://piston-meta.mojang.com", BMCL)
        .replace("https://launcher.mojang.com", BMCL)
        .replace("https://resources.download.minecraft.net", "$BMCL/assets")

    suspend fun install(
        context: Context,
        versionId: String,
        heapMb: Int,
        onProgress: (Progress) -> Unit,
    ): Installed = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "game").apply { mkdirs() }
        onProgress(Progress("读取版本清单"))
        val manifestText = fetchText(MANIFEST_URLS.first())
        val entry = VersionManifest.find(VersionManifest.parse(manifestText), versionId)
            ?: error("清单里没有版本 $versionId")

        val versionDir = File(root, "versions/$versionId").apply { mkdirs() }
        val versionJson = File(versionDir, "$versionId.json")
        writeText(versionJson, fetchText(mirrored(entry.url)))

        val meta = VersionMeta.parse(versionJson.readText())
        val assetsName = meta.assetsIndex ?: versionId
        val indexFile = File(root, "assets/indexes/$assetsName.json").apply { parentFile?.mkdirs() }
        meta.assetIndexUrl?.let { writeText(indexFile, fetchText(mirrored(it))) }
        val assets = if (indexFile.isFile) AssetIndex.parse(indexFile.readText()) else emptyList()

        val (runtimeMeta, droppedLwjgl) = AndroidRuntime.strip(meta)
        onProgress(Progress("换上随包的 LWJGL 3.3.3（清单里 $droppedLwjgl 条 org.lwjgl 库不再下载）", 0, 0))
        val plan = GamePlan.build(runtimeMeta, assets, versionId, clientUrl = meta.clientUrl?.let { mirrored(it) })
        if (plan.missingUrls.isNotEmpty()) {
            onProgress(Progress("清单里有 ${plan.missingUrls.size} 个库没有下载地址", message = plan.missingUrls.take(3).joinToString()))
        }

        val files = plan.all
        var done = 0
        var bytes = 0L
        onProgress(Progress("下载 ${files.size} 个文件", 0, files.size))
        val gate = Semaphore(4)
        coroutineScope {
            files.map { f ->
                async {
                    gate.withPermit {
                        val target = File(root, f.relativePath)
                        if (!isUpToDate(target, f.sha1)) {
                            target.parentFile?.mkdirs()
                            fetchTo(target, f.url)
                            if (f.sha1 != null && !sha1(target).equals(f.sha1, true)) {
                                target.delete()
                                error("校验失败：${f.relativePath}")
                            }
                        }
                        synchronized(gate) {
                            done++
                            bytes += target.length()
                            onProgress(Progress("下载文件", done, files.size, bytes))
                        }
                    }
                }
            }.awaitAll()
        }

        onProgress(Progress("解压原生库"))
        val nativesDir = File(root, "natives").apply { deleteRecursively(); mkdirs() }
        NativeExtractor.extractAll(plan.natives.map { File(root, it.relativePath) to emptyList() }, nativesDir)

        val lwjgl = LwjglAssets.install(context, root)
        if (lwjgl.copied > 0) onProgress(Progress("拷贝 LWJGL 运行库 ${lwjgl.copied} 个", 0, 0))

        val clientJar = plan.clientJar?.let { File(root, it.relativePath).absolutePath } ?: ""
        val placeholders = ClasspathBuilder.placeholders(
            classpath = "",
            nativesDir = listOf(
                nativesDir.absolutePath,
                lwjgl.nativesDir,
                // jniLibs 里解出来的 libpojavexec.so 在这；glfw 的输入回调靠它
                context.applicationInfo.nativeLibraryDir,
            ).joinToString(":"),
            gameDir = root.absolutePath,
            assetsDir = File(root, "assets").absolutePath,
            assetsIndex = assetsName,
            versionName = versionId,
        )
        val launchPlan = ClasspathBuilder.build(
            runtimeMeta, File(root, "libraries").absolutePath, clientJar, placeholders, lwjgl.jars,
        )
        val major = meta.javaMajor ?: JavaPolicy.requiredMajor(versionId)
        onProgress(Progress("准备完成", files.size, files.size, bytes, "Java $major"))
        Installed(versionId, root, launchPlan, major)
    }

    /**
     * 从磁盘上已经下好的文件重建 [Installed]，不联网。
     *
     * 为什么需要它：下载那一步在启动页，真正起 JVM 的是另一个 Activity
     * （全屏 Surface 在那边），Intent 里塞不下 LaunchPlan，所以按版本号
     * 重新拼一遍。这里只读本地 json 和已下载的库，不触发任何请求。
     */
    fun assemble(context: Context, versionId: String): Installed {
        val root = File(context.filesDir, "game")
        val versionJson = File(root, "versions/$versionId/$versionId.json")
        require(versionJson.isFile) {
            "还没下载 $versionId（找不到 ${versionJson.absolutePath}），先回启动页点①"
        }
        val meta = VersionMeta.parse(versionJson.readText())
        val assetsName = meta.assetsIndex ?: versionId
        val indexFile = File(root, "assets/indexes/$assetsName.json")
        val assets = if (indexFile.isFile) AssetIndex.parse(indexFile.readText()) else emptyList()

        val (runtimeMeta, _) = AndroidRuntime.strip(meta)
        // clientUrl 这里只是个占位（GamePlan 用它算相对路径），不会再下载
        val plan0 = GamePlan.build(runtimeMeta, assets, versionId, clientUrl = meta.clientUrl?.let { mirrored(it) })

        val nativesDir = File(root, "natives")
        val lwjgl = LwjglAssets.install(context, root)
        val clientJar = plan0.clientJar?.let { File(root, it.relativePath).absolutePath } ?: ""
        val placeholders = ClasspathBuilder.placeholders(
            classpath = "",
            nativesDir = listOf(
                nativesDir.absolutePath,
                lwjgl.nativesDir,
                context.applicationInfo.nativeLibraryDir,
            ).joinToString(":"),
            gameDir = root.absolutePath,
            assetsDir = File(root, "assets").absolutePath,
            assetsIndex = assetsName,
            versionName = versionId,
        )
        val launchPlan = ClasspathBuilder.build(
            runtimeMeta, File(root, "libraries").absolutePath, clientJar, placeholders, lwjgl.jars,
        )
        val major = meta.javaMajor ?: JavaPolicy.requiredMajor(versionId)
        return Installed(versionId, root, launchPlan, major)
    }

    /** 三处 natives 都进 java.library.path：清单解出来的、随包 LWJGL 的、jniLibs 的。 */
    fun nativesPath(context: Context, root: File): String = listOf(
        // 顺序即优先级：随包的 arm64 LWJGL 必须排在清单解出来的 x86 前面，
        // 否则同名 liblwjgl.so 会先命中 x86 那份（wrong ELF class）
        File(root, AndroidRuntime.BUNDLE_DIR + "/natives").absolutePath,
        File(root, "natives").absolutePath,
        context.applicationInfo.nativeLibraryDir,
    ).joinToString(":")

    /** 解包 JRE 并起进程；日志按行回调。返回的 Process 由调用方负责 waitFor。 */
    suspend fun launch(
        context: Context,
        installed: Installed,
        heapMb: Int,
        onLine: (String) -> Unit,
    ): Process = withContext(Dispatchers.IO) {
        val runtime = RuntimePack.ensure(context, installed.javaMajor)
        val argv = LaunchCommand.build(
            javaBin = runtime.javaBin.absolutePath,
            plan = installed.plan,
            heapMb = heapMb,
            nativesDir = nativesPath(context, installed.root),
            extraJvm = listOf("-Djava.home=" + runtime.home.absolutePath) + AwtRuntime.jvmProps(1280, 720),
        )
        onLine("$ " + LaunchCommand.render(argv).take(4000))
        val process = ProcessBuilder(argv)
            .directory(installed.root)
            .redirectErrorStream(true)
            .apply {
                // Android 版 JRE 是 bionic 链接的，不设这两项连 libjli 都找不到
                environment()["LD_LIBRARY_PATH"] = RuntimePack.libraryPath(runtime)
                environment()["HOME"] = installed.root.absolutePath
                environment()["TMPDIR"] = File(installed.root, "tmp").apply { mkdirs() }.absolutePath
                // 让 LWJGL 的 GL 桥去载 gl4es（jniLibs 里的 libgl4es_114.so），
                // 走 GLES2 翻译 OpenGL 1.16.5 需要的那部分
                environment()["LIBGL_NAME"] = "libgl4es_114.so"
                environment()["LIBGL_ES"] = "2"
                environment()["LIBGL_NOINIT"] = "0"
            }
            .start()
        Thread {
            process.inputStream.bufferedReader().forEachLine { line -> onLine(line) }
        }.apply { isDaemon = true }.start()
        process
    }

    // ---- 网络与文件 ----

    private fun fetchText(url: String): String = URL(url).openConnection().let { conn ->
        (conn as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PCLA/0.5")
        }
        conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun fetchTo(target: File, url: String, attempts: Int = 3) {
        var last: Exception? = null
        repeat(attempts) { i ->
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "PCLA/0.5")
                }
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                return
            } catch (e: Exception) {
                last = e
                Thread.sleep(800L * (i + 1))
            }
        }
        throw last ?: IllegalStateException("下载失败 $url")
    }

    private fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun isUpToDate(file: File, sha1: String?): Boolean =
        file.isFile && file.length() > 0 && (sha1 == null || sha1(file).equals(sha1, true))

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
