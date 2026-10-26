package io.pcla.core.launch

import io.pcla.core.device.DeviceProfile
import io.pcla.core.device.DeviceSpec
import io.pcla.core.device.Verdict

/**
 * 动态 JVM 参数与渲染距离决策。这是 PCLA 相对 FCL 的核心差异点：
 * 它不问你「内存填多少」，而是根据一颗 SoC 的实测数据直接给出结论。
 *
 * 每条系数的来历（都不是拍脑袋，改动请附实测记录）：
 *
 * - 1.18 起内存系数 1.5：实测 2048MB 堆下，1.18 游戏内活数据已达 1.2GB，
 *   young GC 只能回收 5%（1004M→948M），停顿从 135ms 爬到 298ms。
 *   要让它有回落空间，2048 → 3072。
 * - 1.20+ 系数 1.25：同一台机器的 TNT 谷底从 46 恢复到 78，说明结构性问题缓解，
 *   但没回到 1.16.5 的 87，所以仍比 baseline 高一点。
 * - ≤1.16.5 系数 1.0：2048 够用，1.16.5 的 TNT 谷底 87 就是在这个配置下测的。
 * - 渲染距离每超甜点 +1，预算 +128MB：区块网格是最大的活数据来源，
 *   RD 是实测里最粗的那根杠杆（RD2 200fps → RD17 60fps）。
 * - Xms = Xmx：堆动态扩张会先触发一次扩容再 GC，是秒级卡顿的常见来源。
 * - ParallelGCThreads 显式压低：MT6833 是 2×A76 + 6×A55，JVM 会把并行 GC 分摊到
 *   小核上，停顿被最慢的 worker 拖长；并发标记实测被饿到 8189ms。
 */
object JvmTuner {

    private const val MB_PER_MOD = 4
    private const val MOD_HEAP_CAP_MB = 1024
    private const val HEAP_FLOOR_MB = 1024
    private const val HEAP_CEILING_MB = 4096

    /** 允许在实测甜点之上再抬多少。再多就是拿进程被杀的风险换不存在的帧率。 */
    private const val HEAP_HEADROOM_MB = 1024

    /** 堆占物理内存的比例上限。超过这条线，Android 会在你玩游戏时把进程杀掉。 */
    private const val RAM_FRACTION = 0.45

    fun tune(spec: DeviceSpec, profile: DeviceProfile, target: LaunchTarget): JvmConfig {
        val diagnostics = ArrayList<Diagnostic>()
        val rule = profile.rd

        // ---- 1. 版本判定 ----
        val measured = profile.measuredOrNull(target.mcVersion)
        when (profile.verdictOf(target.mcVersion)) {
            Verdict.AVOID -> diagnostics += Diagnostic(
                "VERSION_AVOID", Severity.BLOCK,
                "${target.mcVersion} 在这类机型上实测表现垫底（世界生成 125s、RD2 仅 52fps），不建议新建实例。",
            )
            Verdict.UNTESTED, Verdict.UNKNOWN -> diagnostics += Diagnostic(
                "VERSION_UNTESTED", Severity.INFO,
                "${target.mcVersion} 没有本机型的实测数据，参数按相邻版本的规律外推，仅供参考。",
            )
            Verdict.OK -> Unit
        }

        // ---- 2. 渲染距离 ----
        val rd = target.renderDistance
        if (rd >= rule.ban) {
            diagnostics += Diagnostic(
                "RD_BAN", Severity.BLOCK,
                "渲染距离 $rd 超出实测边界：RD17 时所有版本都掉到 5–23fps。建议 $rd → ${rule.sweet}。",
            )
        } else if (rd >= rule.warnAt) {
            diagnostics += Diagnostic(
                "RD_WARN", Severity.WARN,
                "渲染距离 $rd 偏大，实测 RD8 起帧率明显下滑。建议 $rd → ${rule.sweet}。",
            )
        }

        // ---- 3. 堆大小 ----
        val major = majorOf(target.mcVersion)
        val sweetHeap = profile.heapSweetMb
        val ceiling = safeCeilingMb(spec, profile)

        // 默认堆 = 这台机型实测跑过的基线，只按 mod 数量微调。
        //
        // 曾经按「版本系数 × 甜点堆 + 每级渲染距离 128MB」推导，8GB 机器上会算出 3686MB；
        // 但实测 1.18 在 RD17 下用 2048MB 也没 OOM——渲染距离吃的是显存和 CPU，不是堆。
        // 把没有证据的加法堆上去，只会让 Android 在后台把进程杀掉。
        val requested = target.heapOverrideMb
            ?: (sweetHeap + (target.modCount * MB_PER_MOD).coerceAtMost(MOD_HEAP_CAP_MB))
        val heap = requested.coerceIn(HEAP_FLOOR_MB, ceiling)

        if (heap < requested) {
            diagnostics += Diagnostic(
                "HEAP_CLAMPED", Severity.WARN,
                "请求 ${requested}MB 超过本机安全线 ${ceiling}MB，已压到 $heap MB。" +
                    "再高会在游戏中被系统直接杀掉，而不是变卡。",
            )
        }
        if (major in 18..19 && heap < 3072) {
            diagnostics += Diagnostic(
                "GC_RISK_118", Severity.WARN,
                "1.18/1.19 的活数据在这类机型上会顶到 1.2GB。若游戏内 GC 只占墙钟 5% 以内" +
                    "先别动；一旦 young GC 回收率低于 10%（每次只收个位数百分比），再把堆提到 " +
                    "3072MB——停顿会从 135ms 爬到 298ms 就是它干的。",
            )
        }

        // ---- 4. GC 线程 ----
        val parallel = profile.gcParallelThreads
            ?: if (spec.ramMb <= 6144L) 2 else 4

        val args = buildList {
            add("-Xms${heap}M")
            add("-Xmx${heap}M")
            add("-XX:+UseG1GC")
            add("-XX:MaxGCPauseMillis=50")
            add("-XX:ParallelGCThreads=$parallel")
            add("-XX:ConcGCThreads=1")
            // perfdata 共享内存写在海量小块 IO 的 /data 上，会放大 GC 尾延迟
            add("-XX:+PerfDisableSharedMem")
            add("-Djava.awt.headless=true")
            add("-Dfile.encoding=UTF-8")
        }

        // ---- 5. 推荐值 ----
        val recommendedRd = if (profile.verdictOf(target.mcVersion) == Verdict.AVOID) rule.min else rule.sweet
        if (rd != recommendedRd && rd < rule.warnAt) {
            diagnostics += Diagnostic(
                "RD_INFO", Severity.INFO,
                "本机型甜点是渲染距离 $recommendedRd（实测 RD2=200fps / RD4 稳定 60fps 档）。",
            )
        }

        if (measured?.note?.isNotEmpty() == true) {
            diagnostics += Diagnostic("MEASURED_NOTE", Severity.INFO, measured.note)
        }

        return JvmConfig(
            heapMb = heap,
            args = args,
            diagnostics = diagnostics,
            recommendedRenderDistance = recommendedRd,
            recommendedVersion = VersionPolicy.pick(profile).version,
        )
    }

    /**
     * 本机堆的安全上限：物理内存的 45%，且不超过 4096。
     * 超过这条线 Android 不会让游戏「变慢」，而是直接把进程杀掉。
     */
    fun safeCeilingMb(spec: DeviceSpec, profile: DeviceProfile? = null): Int {
        val byRam = (spec.ramMb * RAM_FRACTION).toInt()
        val byProfile = profile?.let { it.heapSweetMb + HEAP_HEADROOM_MB } ?: HEAP_CEILING_MB
        return minOf(byRam, byProfile, HEAP_CEILING_MB).coerceAtLeast(HEAP_FLOOR_MB)
    }

    /** 主版本号："1.18.2" → 18；"1.7.10" → 7；解析失败返回 0。 */
    fun majorOf(version: String): Int {
        val parts = version.trim().split('.')
        if (parts.size < 2) return 0
        return parts[1].toIntOrNull() ?: 0
    }

    fun heapMultiplier(major: Int): Double = when {
        major >= 20 -> 1.25
        major >= 18 -> 1.50
        major >= 17 -> 1.40
        else -> 1.00
    }
}
