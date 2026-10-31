package io.pcla.core.download

/**
 * Download engine knobs. Only the thread count is player-editable; the rest are guard rails.
 *
 * Why these defaults: measured here (BMCLAPI, 10.18MB file): 1 connection 1.37MB/s, 4 segments
 * 1.27MB/s, 8 segments 0.90MB/s — the bottleneck is the wifi link (~10.4Mbps), so splitting a
 * single file into more threads only fights over bandwidth: small files uncut, medium 4 ways,
 * large files 8 ways, and the player gets a switch anyway.
 */
data class DownloadConfig(
    /** Below this size force one thread (segmenting costs more than it saves). */
    val singleThreadMaxBytes: Long = 10L * 1024 * 1024,
    /** Above this size, go to the "everything on" tier. */
    val parallelMaxBytes: Long = 60L * 1024 * 1024,
    /** Default concurrency for medium files. */
    val parallelThreads: Int = 4,
    /** Default concurrency for large files. */
    val largeFileThreads: Int = 8,
    /** Minimum bytes per segment; too fine and the Range requests themselves become the cost. */
    val minSegmentBytes: Long = 1L * 1024 * 1024,
    val minThreads: Int = 1,
    val maxThreads: Int = 16,
    val connectTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 30_000,
    val maxRetries: Int = 3,
) {
    /**
     * The player might type 9999. Clamp here, never feed an illegal value to the pool —
     * newFixedThreadPool(9999) kills the process with OOM instead of just running slower.
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
        /** Bounds shown on the settings page; same constants as sanitized(). */
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

/** Inclusive range [start, end], in bytes. */
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
     * The player's thread count is further capped by whether the file can be split at all:
     * 16 ways over 10MB leaves 640KB per piece, and request overhead beats the time saved.
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

    /** Split the file into n even segments; the last takes the remainder, so no overlap or gaps. */
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
