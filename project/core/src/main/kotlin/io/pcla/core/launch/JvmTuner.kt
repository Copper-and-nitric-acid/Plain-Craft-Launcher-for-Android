package io.pcla.core.launch

import io.pcla.core.device.DeviceProfile
import io.pcla.core.device.DeviceSpec
import io.pcla.core.device.Verdict

/**
 * Dynamic JVM args and render-distance decisions. This is PCLA's core difference from FCL:
 * it does not ask "how much memory", it draws the conclusion straight from one SoC's measurements.
 *
 * Origin of each coefficient (none guessed; attach measurements to any change):
 *
 * - Memory factor 1.5 from 1.18: measured with a 2048MB heap, 1.18 already holds 1.2GB live in-game,
 *   young GC reclaims only 5% (1004M→948M) and pauses climb from 135ms to 298ms.
 *   To give it room to fall back, 2048 → 3072.
 * - 1.20+ factor 1.25: on the same device the TNT trough recovers from 46 to 78, so the structural problem eased,
 *   but does not return to 1.16.5's 87, so it stays slightly above baseline.
 * - ≤1.16.5 factor 1.0: 2048 is enough; 1.16.5's TNT trough of 87 was measured on exactly this config.
 * - Each render distance above the sweet spot adds +128MB budget: chunk meshes are the largest live-data source,
 *   and RD is the coarsest lever measured (RD2 200fps → RD17 60fps).
 * - Xms = Xmx: a heap that has to grow triggers one expansion before GC, a common source of second-long stalls.
 * - ParallelGCThreads pinned low on purpose: MT6833 is 2×A76 + 6×A55, and the JVM spreads parallel GC onto
 *   the little cores, where the slowest worker drags the pause out; concurrent marking was measured starved to 8189ms.
 */
object JvmTuner {

    private const val MB_PER_MOD = 4
    private const val MOD_HEAP_CAP_MB = 1024
    private const val HEAP_FLOOR_MB = 1024
    private const val HEAP_CEILING_MB = 4096

    /** How far above the measured sweet spot we may go. More than that trades kill risk for frame rate that does not exist. */
    private const val HEAP_HEADROOM_MB = 1024

    /** Cap on heap as a share of physical RAM. Past this line Android kills the process while you are playing. */
    private const val RAM_FRACTION = 0.45

    fun tune(spec: DeviceSpec, profile: DeviceProfile, target: LaunchTarget): JvmConfig {
        val diagnostics = ArrayList<Diagnostic>()
        val rule = profile.rd

        // ---- 1. Version verdict ----
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

        // ---- 2. Render distance ----
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

        // ---- 3. Heap size ----
        val major = majorOf(target.mcVersion)
        val sweetHeap = profile.heapSweetMb
        val ceiling = safeCeilingMb(spec, profile)

        // Default heap = the baseline this device has actually run, nudged only by mod count.
        //
        // We once derived it as "version factor × sweet heap + 128MB per render-distance step", which gave 3686MB on an 8GB device;
        // but measured 1.18 at RD17 did not OOM with 2048MB — render distance eats VRAM and CPU, not heap.
        // Adding heap without evidence only gets the process killed in the background by Android.
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

        // ---- 4. GC threads ----
        val parallel = profile.gcParallelThreads
            ?: if (spec.ramMb <= 6144L) 2 else 4

        val args = buildList {
            add("-Xms${heap}M")
            add("-Xmx${heap}M")
            add("-XX:+UseG1GC")
            add("-XX:MaxGCPauseMillis=50")
            add("-XX:ParallelGCThreads=$parallel")
            add("-XX:ConcGCThreads=1")
            // perfdata shared memory sits on /data under heavy small-block IO, which amplifies GC tail latency
            add("-XX:+PerfDisableSharedMem")
            add("-Djava.awt.headless=true")
            add("-Dfile.encoding=UTF-8")
        }

        // ---- 5. Recommended values ----
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
     * Safe heap ceiling on this device: 45% of physical RAM, and never above 4096.
     * Past this line Android does not make the game "slower" — it kills the process outright.
     */
    fun safeCeilingMb(spec: DeviceSpec, profile: DeviceProfile? = null): Int {
        val byRam = (spec.ramMb * RAM_FRACTION).toInt()
        val byProfile = profile?.let { it.heapSweetMb + HEAP_HEADROOM_MB } ?: HEAP_CEILING_MB
        return minOf(byRam, byProfile, HEAP_CEILING_MB).coerceAtLeast(HEAP_FLOOR_MB)
    }

    /** Major version: "1.18.2" → 18; "1.7.10" → 7; returns 0 if parsing fails. */
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
