package io.pcla.core.download

/**
 * Adaptive concurrency control on 429.
 *
 * Measured: same device, same BMCLAPI mirror, 4 threads 1.24 MB/s, 6 threads 1.06 MB/s,
 * and the log shows `HTTP 429，退避 32000ms`. So maxing out the thread count hits the CDN
 * rate limit and ends up slower. The player's setting is an **upper bound**, not a **target** —
 * on a 429 we should pull back on our own.
 *
 * Backoff halves the permits; recovery is linear, one permit at a time, so it doesn't flap
 * around the threshold.
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

    /** Call on 429/503; returns the new concurrency cap. */
    @Synchronized
    fun onThrottled(): Int {
        throttleCount++
        successStreak = 0
        permits = (permits / 2).coerceAtLeast(minPermits)
        return permits
    }

    /** Call after a success (one file finished); only reopen gradually after enough successes in a row. */
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

/** Parse Retry-After (seconds form only, HTTP-date ignored). Capped at 120 seconds. */
internal fun retryAfterMillis(header: String?): Long? {
    val seconds = header?.trim()?.toLongOrNull() ?: return null
    if (seconds < 0) return null
    return (seconds * 1000L).coerceIn(0L, 120_000L)
}
