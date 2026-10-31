package io.pcla.app

import io.pcla.core.net.MemoryPulse
import io.pcla.core.net.Pulse

/**
 * Sampling side of the memory health check.
 *
 * Reading used/max off `Runtime` is enough to tell "constipation" (memory never reclaimed) from
 * "diarrhea" (constant churn): the difference is a **shape over time**, not an absolute value at
 * one instant, so several points must be collected before looking.
 *
 * When memory moves fast (typically right after entering a world with bulk chunk loading), the
 * once-a-second sampling here shows heavy jitter — and that jitter is exactly what "prevent
 * memory diarrhea" watches: jitter alone is fine, jitter GC can't keep up with is not.
 */
object MemorySampler {

    data class Snapshot(val usedMb: Int, val maxMb: Int, val pulse: Pulse)

    private const val WINDOW = 8

    private val samples = ArrayDeque<Int>()

    fun reset() = samples.clear()

    fun sample(): Snapshot {
        val rt = Runtime.getRuntime()
        val usedMb = ((rt.totalMemory() - rt.freeMemory()) / 1048576L).toInt()
        val maxMb = (rt.maxMemory() / 1048576L).toInt()
        samples.addLast(usedMb)
        while (samples.size > WINDOW) samples.removeFirst()
        return Snapshot(usedMb, maxMb, MemoryPulse.classify(samples.toList(), maxMb))
    }

    /**
     * Prevent memory constipation: give the GC a chance.
     *
     * No System.gc() here (that is a disaster in a game process), just a sample reset so the last
     * session's high-water mark doesn't pollute this session's judgement.
     */
    fun markLaunchStart() = reset()
}
