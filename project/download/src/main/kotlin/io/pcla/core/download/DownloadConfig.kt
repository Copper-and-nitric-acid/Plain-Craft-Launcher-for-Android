package io.pcla.core.download

/**
 * 下载引擎的可调参数。玩家能在设置里改的只有并发数，其余是防呆边界。
 *
 * 为什么默认值这么定：本机实测（BMCLAPI，10.18MB 文件）
 *   单连接 1.37MB/s，4 段 1.27MB/s，8 段 0.90MB/s
 * 说明瓶颈在 wifi 链路（约 10.4Mbps），单文件切再多线程只会互相抢带宽。
 * 所以：小文件不切、中文件 4 路、大文件才 8 路，而且都给玩家留了开关。
 */
data class DownloadConfig(
    /** 小于这个大小强制单线程（切段开销 > 收益）。 */
    val singleThreadMaxBytes: Long = 10L * 1024 * 1024,
    /** 超过这个大小进入「火力全开」档。 */
    val parallelMaxBytes: Long = 60L * 1024 * 1024,
    /** 中文件默认并发。 */
    val parallelThreads: Int = 4,
    /** 大文件默认并发。 */
    val largeFileThreads: Int = 8,
    /** 每个分片的最小字节数。太碎会让 Range 请求本身成为开销。 */
    val minSegmentBytes: Long = 1L * 1024 * 1024,
    val minThreads: Int = 1,
    val maxThreads: Int = 16,
    val connectTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 30_000,
    val maxRetries: Int = 3,
) {
    /**
     * 玩家可能输入 9999。这里统一夹逼，绝不把非法值传进线程池——
     * newFixedThreadPool(9999) 会直接让进程死在 OOM 上，而不是「慢一点」。
     */
    fun sanitized(): DownloadConfig = copy(
        parallelThreads = parallelThreads.coerceIn(minThreads, maxThreads),
        largeFileThreads = largeFileThreads.coerceIn(minThreads, maxThreads),
        singleThreadMaxBytes = singleThreadMaxBytes.coerceAtLeast(0L),
        parallelMaxBytes = parallelMaxBytes.coerceAtLeast(singleThreadMaxBytes.coerceAtLeast(0L)),
        minSegmentBytes = minSegmentBytes.coerceAtLeast(256L * 1024),
        maxRetries = maxRetries.coerceIn(0, 10),
    )

    companion object {
        /** 设置页里给玩家看的边界，和 sanitized() 用同一组常量。 */
        fun validateThreads(raw: Int, cfg: DownloadConfig = DownloadConfig()): Int =
            raw.coerceIn(cfg.minThreads, cfg.maxThreads)

        fun fromUserSettings(
            parallelThreads: Int,
            largeFileThreads: Int,
            base: DownloadConfig = DownloadConfig(),
        ): DownloadConfig = base.copy(
            parallelThreads = parallelThreads,
            largeFileThreads = largeFileThreads,
        ).sanitized()
    }
}

enum class DownloadTier { SINGLE, PARALLEL, TURBO }

/** 闭区间 [start, end]，单位字节。 */
data class ByteRange(val index: Int, val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
    init {
        require(start >= 0 && end >= start) { "非法区间: $start-$end" }
    }
}

object SegmentPlanner {

    fun tierOf(sizeBytes: Long, cfg: DownloadConfig): DownloadTier {
        val c = cfg.sanitized()
        return when {
            sizeBytes < c.singleThreadMaxBytes -> DownloadTier.SINGLE
            sizeBytes <= c.parallelMaxBytes -> DownloadTier.PARALLEL
            else -> DownloadTier.TURBO
        }
    }

    /**
     * 玩家设置并发后，实际能开几个线程还要受「文件够不够切」约束：
     * 一个 10MB 的文件按 16 路切，每片只有 640KB，请求开销比省下来的时间还多。
     */
    fun threadsFor(sizeBytes: Long, cfg: DownloadConfig): Int {
        val c = cfg.sanitized()
        val wanted = when (tierOf(sizeBytes, c)) {
            DownloadTier.SINGLE -> 1
            DownloadTier.PARALLEL -> c.parallelThreads
            DownloadTier.TURBO -> c.largeFileThreads
        }
        val maxUseful = (sizeBytes / c.minSegmentBytes).toInt().coerceAtLeast(1)
        return minOf(wanted, maxUseful).coerceIn(1, c.maxThreads)
    }

    /** 把文件均分成 n 段，最后一段吃掉余数，保证不重叠、不遗漏。 */
    fun plan(sizeBytes: Long, cfg: DownloadConfig): List<ByteRange> {
        if (sizeBytes <= 0L) return emptyList()
        val n = threadsFor(sizeBytes, cfg)
        if (n == 1) return listOf(ByteRange(0, 0L, sizeBytes - 1))

        val chunk = sizeBytes / n
        val ranges = ArrayList<ByteRange>(n)
        var start = 0L
        for (i in 0 until n) {
            val isLast = i == n - 1
            val end = if (isLast) sizeBytes - 1 else (start + chunk - 1)
            ranges += ByteRange(i, start, end)
            start = end + 1
        }
        return ranges
    }
}
