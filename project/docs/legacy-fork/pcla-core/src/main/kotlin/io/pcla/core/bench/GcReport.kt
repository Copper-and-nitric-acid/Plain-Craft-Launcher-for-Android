package io.pcla.core.bench

/**
 * 把 GC 统计渲染成给人看的报告。
 *
 * 刻意不输出 JVM 原始字段名：这东西的读者是「觉得自己手机卡」的人，
 * 他们需要的是「你的卡顿里有 12% 是 GC 造成的」，不是 out_of_region_events。
 */
object GcReport {

    fun render(events: List<GcEvent>, clusterTop: Int = 3): String {
        if (events.isEmpty()) {
            return "没解析到 GC 事件。确认日志是用 -Xlog:gc* 或 -Xloggc 打的。"
        }
        val s = GcLogParser.summarize(events)
        return buildString {
            appendLine("=== GC 日志分析 ===")
            appendLine("  墙钟跨度          %.1fs".format(s.wallSeconds))
            appendLine("  STW 停顿次数      ${s.stopTheWorldCount} 次")
            appendLine("  平均 / 中位       %.1fms / %.1fms".format(s.meanMs, s.p50Ms))
            appendLine("  p95 / p99         %.1fms / %.1fms".format(s.p95Ms, s.p99Ms))
            appendLine("  最长单次          %.1fms".format(s.maxMs))
            appendLine("  停顿总和          %.0fms（占墙钟 %.2f%%）".format(s.totalStwMs, s.pctOfWall))
            appendLine("  >50ms / >100ms    ${s.over50Ms} 次 / ${s.over100Ms} 次")
            appendLine("  Full GC           ${s.fullGcCount} 次")
            appendLine("  并发周期          ${s.concurrentCycles} 次，最长 %.0fms（不停世界，不计入卡顿）".format(s.maxConcurrentMs))
            if (s.clusters.isNotEmpty()) {
                appendLine("  停顿簇（30s 桶内累计 >1s）:")
                s.clusters.take(clusterTop).forEach {
                    appendLine("    ${it.startSeconds}-${it.endSeconds}s  合计 %.0fms，最长 %.0fms".format(it.totalMs, it.maxMs))
                }
            }
            val density = s.stopTheWorldCount / maxOf(s.wallSeconds, 1.0)
            if (density < 0.05) {
                appendLine("  注意：%.2f 次/s 的事件密度偏低，这份日志可能被截断或采集不全。".format(density))
                appendLine("        上面的占比只能当下限看，别据此下结论。")
                appendLine()
            }
            append(verdict(s))
        }
    }

    /**
     * 判定阈值来自实测：MT6833V 上 1.18 那次 690s 里停顿占 6.7%，
     * 用户感知是「隔几秒顿一下」；而 3% 以下基本感觉不到。
     */
    fun verdict(s: GcSummary): String = when {
        s.maxMs > 200 -> "结论：存在超过 200ms 的单次停顿，这种停顿会被肉眼看到（不是「手机烂」）。"
        s.pctOfWall > 6 -> "结论：停顿占墙钟 %.1f%%，已经明显影响手感；优先查谷底与 GC 的关系。".format(s.pctOfWall)
        s.pctOfWall > 3 -> "结论：停顿占 %.1f%%，属于「能感觉到但不致命」，可尝试换堆大小或渲染距离。".format(s.pctOfWall)
        else -> "结论：GC 很干净（%.2f%%），掉帧要去查光照/区块加载/渲染路径，别怪 GC。".format(s.pctOfWall)
    }
}
