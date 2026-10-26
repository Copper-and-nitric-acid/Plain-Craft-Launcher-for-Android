package io.pcla.core.download

/**
 * 429 自适应并发控制。
 *
 * 实测依据：同一台机、同一个 BMCLAPI 镜像，4 线程 1.24 MB/s，6 线程 1.06 MB/s
 * 且日志里出现 `HTTP 429，退避 32000ms`。也就是说「把线程数拉满」会撞上 CDN 的限流，
 * 反而更慢。玩家的设置是**上限**，不是**目标**——撞到 429 就该自己往回收。
 *
 * 回收是指数的一半，恢复是线性的一格一格，避免在阈值附近来回抖。
 */
class AdaptiveLimiter(
    val maxPermits: Int,
    val minPermits: Int = 1,
    private val recoverAfter: Int = 8,
) {
    @Volatile
    var permits: Int = maxPermits
        private set

    private var successStreak = 0
    private var throttleCount = 0

    /** 撞到 429/503 时调用，返回新的并发上限。 */
    @Synchronized
    fun onThrottled(): Int {
        throttleCount++
        successStreak = 0
        permits = (permits / 2).coerceAtLeast(minPermits)
        return permits
    }

    /** 一次成功（一个文件下完）后调用，连续成功够多才慢慢放开。 */
    @Synchronized
    fun onSuccess(): Int {
        if (permits >= maxPermits) return permits
        successStreak++
        if (successStreak >= recoverAfter) {
            permits = (permits + 1).coerceAtMost(maxPermits)
            successStreak = 0
        }
        return permits
    }

    @Synchronized
    fun throttleCount(): Int = throttleCount

    @Synchronized
    fun reset() {
        permits = maxPermits
        successStreak = 0
    }
}

/** 解析 Retry-After（只支持秒数形式，HTTP-date 形式忽略）。上限 120 秒。 */
internal fun retryAfterMillis(header: String?): Long? {
    val seconds = header?.trim()?.toLongOrNull() ?: return null
    if (seconds < 0) return null
    return (seconds * 1000L).coerceIn(0L, 120_000L)
}
