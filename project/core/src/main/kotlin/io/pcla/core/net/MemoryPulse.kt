package io.pcla.core.net

/** 内存体检的三种结论。名字是用户起的，医学上不严谨，但一眼就懂。 */
enum class Verdict { 正常, 便秘, 拉稀 }

data class Pulse(val verdict: Verdict, val detail: String)

/**
 * 内存体检：区分「便秘」和「拉稀」。
 *
 * - **便秘**：占用一直顶在高位、GC 之后也下不来 —— 这才是泄漏的典型形态；
 * - **拉稀**：占用在极短时间里剧烈上蹿下跳 —— 不是泄漏，是分配/回收太频繁，
 *   表现为 GC 抖动、帧时间锯齿。两种情况的对策完全相反，所以必须分开判断。
 *
 * 输入是最近若干个采样点（MB，等间隔），以及堆上限。
 */
object MemoryPulse {

    /** 判定「拉稀」的抖动阈值：堆上限的 3%，且不低于 16MB。 */
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
