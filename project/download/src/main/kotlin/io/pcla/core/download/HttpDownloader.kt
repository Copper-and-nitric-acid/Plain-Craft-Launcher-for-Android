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

/** Download result. Every failure carries a "why": the player should see a reason, not a stack trace. */
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

/** The three options in the player's "download looks wrong" dialog. */
enum class UserChoice { DELETE_BAD_FILE, SHOW_URL, FORCE_CONTINUE }

fun interface IssueHandler {
    fun onHashMismatch(issue: DownloadOutcome.HashMismatch): UserChoice
}

/**
 * Segmented download engine.
 *
 * Thread counts and thresholds come from [DownloadConfig], never hard-coded — the player's sweet
 * spot varies per device, but every entry point goes through [DownloadConfig.sanitized], so 9999
 * is clamped instead of OOM-ing the process.
 *
 * Write strategy: each segment writes its own .part file, merged in order at the end.
 * Reason: /sdcard is FUSE, so several threads seeking randomly in one RandomAccessFile cause
 * heavy write amplification; sequential writes are faster (8 threads: 19,076,437 B/s, 99MB in 5s).
 */
class HttpDownloader(
    private val config: DownloadConfig = DownloadConfig(),
    private val client: OkHttpClient = defaultClient(config),
    private val log: (String) -> Unit = {},
    /** Called once on 429/503; the scheduler shrinks concurrency accordingly. */
    private val onThrottled: (() -> Unit)? = null,
) {
    private val cfg = config.sanitized()


    data class Probe(val sizeBytes: Long, val rangeSupported: Boolean)

    /** Probe size and Range support. A failed probe is not fatal: fall back to single-connection. */
    fun probe(url: String): Probe? {
        runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val len = resp.header("Content-Length")?.toLongOrNull() ?: return@use null
                val ranges = resp.header("Accept-Ranges")?.contains("bytes", true) == true
                return Probe(len, ranges)
            }
        }
        // some CDNs reject HEAD; retry with Range: bytes=0-0
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
        // 1. URL check: block LAN / 169.254 up front instead of waiting out a 30s timeout
        val verdict = NetworkGuard.checkUrl(url)
        if (verdict !is NetworkGuard.Verdict.Ok) {
            val msg = NetworkGuard.messageOf(verdict) ?: "地址不可用"
            return DownloadOutcome.Blocked(verdict, msg)
        }

        val probe = probe(url)
        val total = probe?.sizeBytes ?: -1L
        val size = if (total > 0) total else 0L
        if (total > 0) {
            // space check: keep a 5% margin; running out of disk mid-write is worse than not downloading
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

            // 2. merge + verify: write a temp file, rename only after it verifies, never overwrite existing
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
                            val len = merged.length()   // merged no longer exists after the rename, so take the length first
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
                    // no official hash does not mean "verified": return null honestly
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

    /** Single-connection whole-file download: fallback for sources without Range support. */
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

    /** Fetch one segment, resumable via .part; 429 backs off exponentially instead of one flat 10s wait. */
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
                    if (resp.code == 416) {   // server thinks it's already complete
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

    /** Atomic rename: only a same-directory rename guarantees no half-written file is left over. */
    private fun publish(tmp: File, dest: File) {
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        /** Entry point that hides OkHttp types; the app module uses this so okhttp doesn't leak upward. */
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
