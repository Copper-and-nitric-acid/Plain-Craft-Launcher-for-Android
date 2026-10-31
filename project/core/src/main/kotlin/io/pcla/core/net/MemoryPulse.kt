package io.pcla.core.net

/** The three verdicts of a memory checkup. The user coined the names; medically loose but instantly clear. */
enum class Verdict { 正常, 便秘, 拉稀 }

data class Pulse(val verdict: Verdict, val detail: String)

/**
 * Memory checkup: tell "constipation" from "diarrhea".
 *
 * - **便秘** (constipation): usage stays pinned high and will not come down after GC — the classic shape of a leak;
 * - **拉稀** (diarrhea): usage swings wildly within a very short time — not a leak but allocate/reclaim too often,
 *   showing up as GC jitter and sawtooth frame times. The two need opposite responses, so they must be judged apart.
 *
 * Input is the latest few sample points (MB, evenly spaced) and the heap cap.
 */
object MemoryPulse {

    /** Jitter threshold for "diarrhea": 3% of the heap cap, and never below 16MB. */
    private fun churnThreshold(maxMb: Int): Int = maxOf(16, maxMb * 3 / 100)

    fun classify(samplesMb: List<Int>, maxMb: Int): Pulse {
        if (samplesMb.size < 3) return Pulse(Verdict.正常, "采样太少（${samplesMb.size} 个），再等等")
        val deltas = samplesMb.zipWithNext { a, b -> kotlin.math.abs(b - a) }
        val avgDelta = deltas.average()
        val last = samplesMb.last()
        val lo = samplesMb.min()
        val hi = samplesMb.max()
        val tension = if (maxMb > 0) last * 100 / maxMb else 0

        val churn = churnThreshold(maxMb)
        return when {
            avgDelta >= churn ->
                Pulse(Verdict.拉稀, "内存变化太快：每次平均抖 ${"%.0f".format(avgDelta)}MB（阈值 ${churn}MB），GC 在追着跑")
            tension >= 85 && hi - lo <= maxMb * 2 / 100 ->
                Pulse(Verdict.便秘, "占用 ${last}MB / ${maxMb}MB（$tension%）居高不下，回收不动，像泄漏")
            tension >= 85 ->
                Pulse(Verdict.正常, "占用偏高（$tension%）但还在回落，先不动它")
            else ->
                Pulse(Verdict.正常, "占用 ${last}MB / ${maxMb}MB（$tension%），波动 ${hi - lo}MB")
        }
    }
}
