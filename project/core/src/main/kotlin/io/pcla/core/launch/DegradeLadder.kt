package io.pcla.core.launch

import io.pcla.core.device.DeviceProfile
import io.pcla.core.device.DeviceSpec
import io.pcla.core.device.Verdict

/** The four levers that move frame rate, ordered by measured gain, largest first. Heap is last on purpose: weakest gain. */
enum class Lever { RENDER_DISTANCE, VERSION, RENDERER, HEAP }

data class DegradeAction(
    val lever: Lever,
    val from: String,
    val to: String,
    val reason: String,
    val expected: String,
)

/**
 * What one run can observe once it finishes.
 *
 * PCLA has no in-process hook today and cannot read live frame rate, so these come only from user input or the log after the game exits.
 * So degrading can only be "after-the-fact advice", never "tuning while running" — saying that plainly beats pretending we can auto-tune.
 */
data class RunObservation(
    val avgFps: Double,
    val sampleSeconds: Int,
    val renderDistance: Int,
    val heapMb: Int,
    /** GC pause as a percentage of wall clock, from GcReport. null if unknown. */
    val gcPausePct: Double? = null,
    /** Reclaim rate of one young GC in %, from fields like 1004M->948M: (1004-948)/1004. null if unknown. */
    val gcReclaimPct: Double? = null,
    /** Peak live-object MB, from fields like 1197M->1144M in the GC log. null if unknown. */
    val liveSetMb: Int? = null,
    /** System free memory in MB. null if unknown. */
    val memAvailableMb: Int? = null,
)

data class DegradePlan(
    val actions: List<DegradeAction>,
    val heapVerdict: String,
    val notes: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = actions.isEmpty()
}

/**
 * Order of handling when frame rate drops below the threshold.
 *
 * One design rule: **move the high-gain levers first, the low-gain ones last, change nothing without evidence.**
 * Measured gains (MT6833V, 1.16.5): RD17→RD2 is 6.5×; the TNT trough from 1.18→1.16.5 goes 46→87;
 * and heap 2048→3072 only matters when "live objects fill the heap"; elsewhere it just makes GC more frequent.
 *
 * In particular: "low frame rate" alone **never triggers heap shrink**. Shrinking is right only when system memory is short,
 * which is a memory problem, not a frame-rate problem.
 */
object DegradeLadder {

    const val LOW_FPS = 20.0
    const val MIN_SAMPLE_SECONDS = 30

    /** Only a GC pause above this share of wall clock counts as "GC eating frames". Below it, heap is not the bottleneck. */
    const val GC_PAUSE_PCT = 5.0

    /** A young GC reclaim rate below this means each GC gets almost nothing back — the heap has no room left to fall. */
    private const val MIN_RECLAIM_PCT = 10.0

    /** Fallback without reclaim data: live objects above this share of the heap also counts as full. */
    private const val LIVE_SET_PRESSURE = 0.8

    /** How much to raise the heap per step. One step verifies the direction; do not double at once. */
    private const val GROW_STEP_MB = 1024

    /** When free memory is below this multiple of the heap, shrink it to give the system room. */
    private const val MEM_HEADROOM = 1.2

    fun plan(
        obs: RunObservation,
        spec: DeviceSpec,
        profile: DeviceProfile,
        version: String,
        tuned: JvmConfig? = null,
    ): DegradePlan {
        val notes = ArrayList<String>()

        if (obs.sampleSeconds < MIN_SAMPLE_SECONDS) {
            return DegradePlan(
                emptyList(),
                "采样 ${obs.sampleSeconds}s 不足 ${MIN_SAMPLE_SECONDS}s，不下结论。",
                listOf("短采样会把一次区块加载或一次编译当成常态，据此调参只会越调越糟。"),
            )
        }
        if (obs.avgFps >= LOW_FPS) {
            return DegradePlan(emptyList(), "当前 ${fmt(obs.avgFps)}fps，未触发 ${LOW_FPS.toInt()}fps 降级线。")
        }

        val actions = ArrayList<DegradeAction>()

        // ---- Step 1: render distance ----
        val rdNext = nextRdDown(obs.renderDistance, profile.rd.sweet)
        if (rdNext != null) {
            actions += DegradeAction(
                Lever.RENDER_DISTANCE,
                "${obs.renderDistance}",
                "$rdNext",
                "渲染距离是实测里最粗的杠杆：同一台机 RD17=22fps、RD2=144fps",
                gainText(profile, version),
            )
        } else {
            notes += "渲染距离已经在最低档（${obs.renderDistance}），再降没有收益，进入下一步。"
        }

        // ---- Step 2: switch version ----
        val measured = profile.measuredOrNull(version)
        val pick = VersionPolicy.pick(profile).version
        if (profile.verdictOf(version) == Verdict.AVOID && pick != null && pick != version) {
            actions += DegradeAction(
                Lever.VERSION,
                version,
                pick,
                measured?.note?.ifEmpty { null } ?: "该版本在这类机型上实测垫底",
                "综合分 ${measured?.score ?: 0} → 换到 $pick",
            )
        }

        // ---- Step 3: heap (only move it with evidence) ----
        val heapAction = heapStep(obs, spec, profile, tuned)
        if (heapAction != null) actions += heapAction
        val heapVerdict = heapVerdict(obs, spec, profile, tuned)

        if (actions.isEmpty()) notes += "四项都到边界了。这种情况基本是硬件上限，不是配置问题。"

        return DegradePlan(actions, heapVerdict, notes)
    }

    /** Next step down in render distance: 17 → 8 → sweet spot. Stop at the sweet spot; lower only loses view distance. */
    fun nextRdDown(current: Int, sweet: Int): Int? = when {
        current > 8 -> 8
        current > sweet -> sweet
        else -> null
    }

    private fun heapStep(obs: RunObservation, spec: DeviceSpec, profile: DeviceProfile, tuned: JvmConfig?): DegradeAction? {
        val available = obs.memAvailableMb
        if (available != null && available < obs.heapMb * MEM_HEADROOM) {
            val target = shrinkTarget(spec, obs.heapMb)
            if (target < obs.heapMb) {
                return DegradeAction(
                    Lever.HEAP, "${obs.heapMb}", "$target",
                    "系统可用内存只剩 ${available}MB，堆 ${obs.heapMb}MB 会把后台挤死",
                    "这是内存不够，不是帧率问题；缩堆避免被系统杀进程",
                )
            }
        }

        val pct = obs.gcPausePct ?: return null
        if (pct < GC_PAUSE_PCT || !heapTight(obs)) return null

        val ceiling = JvmTuner.safeCeilingMb(spec, profile)
        // Raising the heap looks at "current heap + one step", not at JvmTuner's recommended value —
        // that value is already squeezed hard against the measured baseline; using it as the target means "never needs to move".
        val target = minOf(
            ceiling,
            maxOf(obs.heapMb + GROW_STEP_MB, (obs.liveSetMb ?: 0) * 2, tuned?.heapMb ?: 0),
        )
        if (target <= obs.heapMb) return null
        val evidence = obs.gcReclaimPct?.let { "young GC 只能回收 ${fmt(it)}%" }
            ?: "活对象 ${obs.liveSetMb}MB 顶满堆"
        return DegradeAction(
            Lever.HEAP, "${obs.heapMb}", "$target",
            "GC 占墙钟 ${fmt(pct)}%，且 $evidence",
            "方向是加不是减：给活对象留出回落空间，让 young GC 能真回收",
        )
    }

    private fun heapVerdict(obs: RunObservation, spec: DeviceSpec, profile: DeviceProfile, tuned: JvmConfig?): String {
        val available = obs.memAvailableMb
        if (available != null && available < obs.heapMb * MEM_HEADROOM) {
            return "堆要缩：可用内存 ${available}MB 撑不住 ${obs.heapMb}MB 堆（内存问题，与帧率无关）。"
        }
        val pct = obs.gcPausePct ?: return "没有 GC 数据，不动堆——盲调堆只会制造新问题。"
        val ceiling = JvmTuner.safeCeilingMb(spec, profile)
        val growTarget = minOf(ceiling, maxOf(obs.heapMb + GROW_STEP_MB, tuned?.heapMb ?: 0))
        return when {
            pct < GC_PAUSE_PCT ->
                "GC 只占墙钟 ${fmt(pct)}%，帧率瓶颈不在 GC，**缩堆无效**——缩了只会让 GC 更频繁。"
            heapTight(obs) ->
                "GC 占 ${fmt(pct)}% 且回不上来（${
                    obs.gcReclaimPct?.let { "回收率 ${fmt(it)}%" } ?: "活对象顶满"
                }），该加堆：${obs.heapMb} → ${growTarget}MB。"
            else ->
                "GC 占 ${fmt(pct)}%，但活对象还没顶满堆，先不动堆，按渲染距离/版本处理。"
        }
    }

    /**
     * Is the heap really tight?
     *
     * The criterion is not "how much of the heap live objects use" — G1 still runs young GC like mad at 56% heap,
     * because Eden is small and each cycle reclaims 5%, not enough for the next allocation. The real signal is reclaim rate.
     */
    private fun heapTight(obs: RunObservation): Boolean {
        obs.gcReclaimPct?.let { return it < MIN_RECLAIM_PCT }
        obs.liveSetMb?.let { return it > obs.heapMb * LIVE_SET_PRESSURE }
        return false
    }

    /** Shrink target: leave the system headroom, but never below 1024 (below that 1.18 OOMs outright). */
    private fun shrinkTarget(spec: DeviceSpec, current: Int): Int =
        minOf(current, maxOf(1024, (spec.ramMb * 0.35).toInt()))

    private fun gainText(profile: DeviceProfile, version: String): String {
        val m = profile.measuredOrNull(version) ?: return "预计明显回升"
        val hi = m.rd2Max
        val lo = m.rd17Max
        return if (hi != null && lo != null && lo > 0) {
            "该版本实测 RD17=${lo}fps / RD2=${hi}fps"
        } else {
            "预计明显回升"
        }
    }

    private fun fmt(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
}
