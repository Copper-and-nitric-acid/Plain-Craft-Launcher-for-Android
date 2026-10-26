package io.pcla.core.download

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/** 下载结果。所有失败都带得上「为什么」，因为玩家看到的应该是原因，不是堆栈。 */
sealed interface DownloadOutcome {
    data class Success(val file: File, val bytes: Long, val sha1: String?, val forced: Boolean = false) : DownloadOutcome
    data class HashMismatch(
        val tempFile: File,
        val url: String,
        val expectedSha1: String,
        val actualSha1: String,
    ) : DownloadOutcome
    data class Blocked(val verdict: NetworkGuard.Verdict, val message: String) : DownloadOutcome
    data class Failed(val reason: String, val cause: Throwable? = null) : DownloadOutcome
    data object Cancelled : DownloadOutcome
}

/** 玩家在「下载不对劲」弹窗里的三个选项。 */
enum class UserChoice { DELETE_BAD_FILE, SHOW_URL, FORCE_CONTINUE }

fun interface IssueHandler {
    fun onHashMismatch(issue: DownloadOutcome.HashMismatch): UserChoice
}

/**
 * 分段下载引擎。
 *
 * 线程数、阈值全部来自 [DownloadConfig]，不写死——玩家的甜点因机器而异，
 * 但入口都过 [DownloadConfig.sanitized]，9999 这种输入会被夹到上限而不是让进程 OOM。
 *
 * 落盘策略：每个分片写自己的 .part 文件，最后顺序拼接。
 * 原因是 /sdcard 是 FUSE，多个线程对同一个 RandomAccessFile 随机 seek 的写放大极高，
 * 顺序写单文件反而更快（实测 8 线程实测 19,076,437 B/s，5 秒 99MB）。
 */
class HttpDownloader(
    private val config: DownloadConfig = DownloadConfig(),
    private val client: OkHttpClient = defaultClient(config),
    private val log: (String) -> Unit = {},
    /** 撞到 429/503 时回调一次，调度层据此收缩并发。 */
    private val onThrottled: (() -> Unit)? = null,
) {
    private val cfg = config.sanitized()


    data class Probe(val sizeBytes: Long, val rangeSupported: Boolean)

    /** 探测大小与是否支持 Range。探测失败不算致命——退回单连接整文件下载。 */
    fun probe(url: String): Probe? {
        runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val len = resp.header("Content-Length")?.toLongOrNull() ?: return@use null
                val ranges = resp.header("Accept-Ranges")?.contains("bytes", true) == true
                return Probe(len, ranges)
            }
        }
        // 有些 CDN 拒绝 HEAD，再试一次 Range: bytes=0-0
        runCatching {
            client.newCall(
                Request.Builder().url(url).header("Range", "bytes=0-0").build()
            ).execute().use { resp ->
                val cr = resp.header("Content-Range")   // bytes 0-0/12345
                val total = cr?.substringAfterLast('/')?.toLongOrNull()
                if (total != null) return Probe(total, resp.code == 206)
            }
        }
        return null
    }

    fun download(
        url: String,
        dest: File,
        expectedSha1: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        onIssue: IssueHandler? = null,
        isCancelled: () -> Boolean = { false },
    ): DownloadOutcome {
        // 1. 地址拦截：局域网 / 169.254 在下载前就挡住，不要等超时 30 秒再报错
        val verdict = NetworkGuard.checkUrl(url)
        if (verdict !is NetworkGuard.Verdict.Ok) {
            val msg = NetworkGuard.messageOf(verdict) ?: "地址不可用"
            return DownloadOutcome.Blocked(verdict, msg)
        }

        val probe = probe(url)
        val total = probe?.sizeBytes ?: -1L
        val size = if (total > 0) total else 0L
        if (total > 0) {
            // 空间检查：留 5% 余量，磁盘满写到一半比下不动更糟
            val usable = dest.parentFile?.usableSpace ?: 0L
            if (usable in 1 until (total * 105 / 100)) {
                return DownloadOutcome.Failed("磁盘空间不够：需要 ${total * 105 / 100} 字节，只剩 $usable")
            }
        }

        if (probe == null || probe.sizeBytes <= 0 || !probe.rangeSupported) {
            log("服务器不支持分段或大小未知，退回单连接")
            return singleStream(url, dest, expectedSha1, onProgress, onIssue, isCancelled)
        }

        val ranges = SegmentPlanner.plan(probe.sizeBytes, cfg)
        val threads = ranges.size.coerceIn(cfg.minThreads, cfg.maxThreads)
        log("${probe.sizeBytes} 字节 → ${SegmentPlanner.tierOf(probe.sizeBytes, cfg)}，$threads 个分片")

        val partsDir = File(dest.parentFile, "${dest.name}.parts").apply { mkdirs() }
        val done = AtomicLong(0)
        val failure = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(threads) { r ->
            Thread(r, "pcla-dl-${Thread.currentThread().id}-%d".format(System.nanoTime() % 1000)).apply {
                isDaemon = true
            }
        }

        try {
            val futures = ranges.map { range ->
                pool.submit {
                    if (failure.get() != null) return@submit
                    try {
                        fetchRange(
                            url = url,
                            range = range,
                            part = File(partsDir, "seg-${range.index}.part"),
                            onBytes = { n -> onProgress(done.addAndGet(n), probe.sizeBytes) },
                            isCancelled = isCancelled,
                        )
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
            futures.forEach { it.get() }

            failure.get()?.let { t ->
                return if (isCancelled()) DownloadOutcome.Cancelled
                else DownloadOutcome.Failed("分片下载失败：${t.message}", t)
            }
            if (isCancelled()) return DownloadOutcome.Cancelled

            // 2. 拼接 + 校验：先写临时文件，校验通过才改名，绝不覆盖已有文件
            val merged = File(dest.parentFile, "${dest.name}.tmp")
            mergeParts(ranges, partsDir, merged)
            val checkResult = Checksum.verify(merged, expectedSha1, probe.sizeBytes)
            return when (checkResult) {
                is Checksum.HashResult.Mismatch -> {
                    val issue = DownloadOutcome.HashMismatch(merged, url, checkResult.expected, checkResult.actual)
                    when (onIssue?.onHashMismatch(issue) ?: UserChoice.SHOW_URL) {
                        UserChoice.DELETE_BAD_FILE -> {
                            merged.delete(); partsDir.deleteRecursively()
                            DownloadOutcome.Failed("按你的选择删除了损坏文件")
                        }
                        UserChoice.FORCE_CONTINUE -> {
                            log("⚠️ 高风险：玩家选择无视哈希不匹配，继续使用 ${merged.name}")
                            val len = merged.length()   // 改名后 merged 就不存在了，先取
                            publish(merged, dest)
                            DownloadOutcome.Success(dest, len, checkResult.actual, forced = true)
                        }
                        UserChoice.SHOW_URL -> issue
                    }
                }
                is Checksum.HashResult.SizeMismatch ->
                    DownloadOutcome.Failed("大小不对：期望 ${checkResult.expected}，实际 ${checkResult.actual}")
                is Checksum.HashResult.Match -> {
                    val len = merged.length()
                    publish(merged, dest)
                    partsDir.deleteRecursively()
                    DownloadOutcome.Success(dest, len, checkResult.sha1)
                }
                Checksum.HashResult.NoHashProvided -> {
                    // 没有官方哈希不等于「验证通过」，如实返回 null
                    val len = merged.length()
                    publish(merged, dest)
                    partsDir.deleteRecursively()
                    DownloadOutcome.Success(dest, len, null)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** 单连接整文件下载：给不支持 Range 的源兜底。 */
    private fun singleStream(
        url: String,
        dest: File,
        expectedSha1: String?,
        onProgress: (Long, Long) -> Unit,
        onIssue: IssueHandler?,
        isCancelled: () -> Boolean,
    ): DownloadOutcome {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        val buf = ByteArray(64 * 1024)
        var written = 0L
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return DownloadOutcome.Failed("HTTP ${resp.code}")
                val body = resp.body ?: return DownloadOutcome.Failed("响应为空")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        while (true) {
                            if (isCancelled()) return DownloadOutcome.Cancelled
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            onProgress(written, if (total > 0) total else -1)
                        }
                    }
                }
            }
        } catch (t: IOException) {
            return DownloadOutcome.Failed("连接异常：${t.message}", t)
        }

        return when (val r = Checksum.verify(tmp, expectedSha1)) {
            is Checksum.HashResult.Mismatch -> DownloadOutcome.HashMismatch(tmp, url, r.expected, r.actual)
                .also { onIssue?.onHashMismatch(it) }
            is Checksum.HashResult.SizeMismatch -> DownloadOutcome.Failed("大小不对：${r.expected} vs ${r.actual}")
            is Checksum.HashResult.Match -> { publish(tmp, dest); DownloadOutcome.Success(dest, written, r.sha1) }
            Checksum.HashResult.NoHashProvided -> { publish(tmp, dest); DownloadOutcome.Success(dest, written, null) }
        }
    }

    /** 取一个分片，支持 .part 续传；429 用指数退避而不是死等 10 秒。 */
    private fun fetchRange(
        url: String,
        range: ByteRange,
        part: File,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        var attempt = 0
        while (true) {
            if (isCancelled()) return
            val have = if (part.exists()) part.length() else 0L
            if (have >= range.length) return
            val start = range.start + have
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=$start-${range.end}")
                .build()
            try {
                client.newCall(req).execute().use { resp: Response ->
                    if (resp.code == 429 || resp.code == 503) {
                        val wait = min(60_000L, (1L shl min(attempt + 1, 6)) * 1000L)
                        onThrottled?.invoke()
                        log("HTTP ${resp.code}，退避 ${wait}ms 后重试（第 ${attempt + 1} 次）")
                        Thread.sleep(wait)
                        attempt++
                        return@use
                    }
                    if (resp.code == 416) {   // 服务器认为已下完
                        return
                    }
                    if (!resp.isSuccessful && resp.code != 206) {
                        throw IOException("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IOException("响应为空")
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.seek(have)
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (isCancelled()) return
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                onBytes(n.toLong())
                            }
                        }
                    }
                    return
                }
            } catch (t: IOException) {
                attempt++
                if (attempt > cfg.maxRetries) throw t
                Thread.sleep(min(8_000L, 500L * attempt))
            }
        }
    }

    private fun mergeParts(ranges: List<ByteRange>, partsDir: File, merged: File) {
        merged.outputStream().use { out ->
            for (r in ranges) {
                val part = File(partsDir, "seg-${r.index}.part")
                if (!part.exists()) throw IOException("分片 ${r.index} 缺失")
                part.inputStream().use { it.copyTo(out, 64 * 1024) }
            }
        }
    }

    /** 原子改名：同目录 rename 才能保证不留下半个文件。 */
    private fun publish(tmp: File, dest: File) {
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        /** 不暴露 OkHttp 类型的入口，app 模块用这个，避免 okhttp 泄漏到上层 classpath。 */
        fun create(
            config: DownloadConfig = DownloadConfig(),
            log: (String) -> Unit = {},
            onThrottled: (() -> Unit)? = null,
        ): HttpDownloader = HttpDownloader(config = config, log = log, onThrottled = onThrottled)

        fun defaultClient(cfg: DownloadConfig): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }
}
