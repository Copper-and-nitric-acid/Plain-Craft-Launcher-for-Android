package io.pcla.app

import io.pcla.core.net.MemoryPulse
import io.pcla.core.net.Pulse

/**
 * 内存体检的采样端。
 *
 * 只读 `Runtime` 的 used/max 就够判断「便秘 vs 拉稀」：这两者的区别是
 * **时间维度上的形态**，不是某一时刻的绝对值，所以必须攒够若干个点再看。
 *
 * 内存变化太快的话（典型是刚进世界、区块批量加载），这里每秒一次采样会
 * 看到很大的抖动 —— 那就是「防止内存拉稀」要盯的指标：抖动本身不是坏事，
 * 抖到 GC 都追不上才是。
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
     * 防止内存便秘：给垃圾回收留出机会。
     *
     * 这里不调 System.gc()（在游戏进程里那是灾难），只是把采样重置，
     * 避免上一局的高水位污染这一局的判断。
     */
    fun markLaunchStart() = reset()
}
