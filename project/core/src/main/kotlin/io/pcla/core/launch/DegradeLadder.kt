package io.pcla.core.launch

import io.pcla.core.device.DeviceProfile
import io.pcla.core.device.DeviceSpec
import io.pcla.core.device.Verdict

/** 能拉动帧率的四根杠杆，按实测收益从大到小排列。堆排在最后不是随意：实测收益最弱。 */
enum class Lever { RENDER_DISTANCE, VERSION, RENDERER, HEAP }

data class DegradeAction(
    val lever: Lever,
    val from: String,
    val to: String,
    val reason: String,
    val expected: String,
)

/**
 * 一次运行结束后能拿到的观测。
 *
 * PCLA 现在没有进程内 hook，拿不到实时帧率，这些值只能来自用户手填或游戏退出后的日志。
 * 所以降级只能是「事后建议」，不是「运行中自动调参」——写清楚这一点，比假装能自动调更重要。
 */
data class RunObservation(
    val avgFps: Double,
    val sampleSeconds: Int,
    val renderDistance: Int,
    val heapMb: Int,
    /** GC 停顿占墙钟百分比，来自 GcReport。未知给 null。 */
    val gcPausePct: Double? = null,
    /** 一次 young GC 的回收率 %，来自 1004M->948M 这类字段：(1004-948)/1004。未知给 null。 */
    val gcReclaimPct: Double? = null,
    /** 峰值活对象 MB，来自 GC 日志的 1197M->1144M 这类字段。未知给 null。 */
    val liveSetMb: Int? = null,
    /** 系统剩余可用内存 MB。未知给 null。 */
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
 * 帧率低于阈值时的处置顺序。
 *
 * 设计原则只有一条：**先动收益大的，再动收益小的，没证据就不动。**
 * 实测收益率（MT6833V，1.16.5）：RD17→RD2 是 6.5 倍；1.18→1.16.5 的 TNT 谷底是 46→87；
 * 而堆从 2048 调到 3072 只在「活对象顶满」时才有意义，其余情况改它只是让 GC 更频繁。
 *
 * 特别地：单凭「帧率低」**永远不触发缩堆**。缩堆只有在系统内存不够时才是正确操作，
 * 那是内存问题，不是帧率问题。
 */
object DegradeLadder {

    const val LOW_FPS = 20.0
    const val MIN_SAMPLE_SECONDS = 30

    /** GC 停顿占墙钟超过这个比例，才算「GC 在吃帧」。低于它，堆不是瓶颈。 */
    const val GC_PAUSE_PCT = 5.0

    /** young GC 回收率低于这个比例，说明每次 GC 几乎什么都收不回来——堆已经没有回落空间。 */
    private const val MIN_RECLAIM_PCT = 10.0

    /** 没有回收率数据时的退路：活对象占堆超过这个比例也算顶满。 */
    private const val LIVE_SET_PRESSURE = 0.8

    /** 可用内存低于堆的这个倍数，就该缩堆给系统让路。 */
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

        // ---- 第 1 步：渲染距离 ----
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

        // ---- 第 2 步：换版本 ----
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

        // ---- 第 3 步：堆（只在有证据时动） ----
        val heapAction = heapStep(obs, spec, tuned)
        if (heapAction != null) actions += heapAction
        val heapVerdict = heapVerdict(obs, spec, tuned)

        if (actions.isEmpty()) notes += "四项都到边界了。这种情况基本是硬件上限，不是配置问题。"

        return DegradePlan(actions, heapVerdict, notes)
    }

    /** 渲染距离下一档：17 → 8 → 甜点。到甜点就停，因为再降只是损失视野。 */
    fun nextRdDown(current: Int, sweet: Int): Int? = when {
        current > 8 -> 8
        current > sweet -> sweet
        else -> null
    }

    private fun heapStep(obs: RunObservation, spec: DeviceSpec, tuned: JvmConfig?): DegradeAction? {
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

        val ceiling = JvmTuner.safeCeilingMb(spec)
        val target = minOf(ceiling, maxOf(tuned?.heapMb ?: (obs.heapMb + 512), (obs.liveSetMb ?: 0) * 2))
        if (target <= obs.heapMb) return null
        val evidence = obs.gcReclaimPct?.let { "young GC 只能回收 ${fmt(it)}%" }
            ?: "活对象 ${obs.liveSetMb}MB 顶满堆"
        return DegradeAction(
            Lever.HEAP, "${obs.heapMb}", "$target",
            "GC 占墙钟 ${fmt(pct)}%，且 $evidence",
            "方向是加不是减：给活对象留出回落空间，让 young GC 能真回收",
        )
    }

    private fun heapVerdict(obs: RunObservation, spec: DeviceSpec, tuned: JvmConfig?): String {
        val available = obs.memAvailableMb
        if (available != null && available < obs.heapMb * MEM_HEADROOM) {
            return "堆要缩：可用内存 ${available}MB 撑不住 ${obs.heapMb}MB 堆（内存问题，与帧率无关）。"
        }
        val pct = obs.gcPausePct ?: return "没有 GC 数据，不动堆——盲调堆只会制造新问题。"
        val ceiling = JvmTuner.safeCeilingMb(spec)
        return when {
            pct < GC_PAUSE_PCT ->
                "GC 只占墙钟 ${fmt(pct)}%，帧率瓶颈不在 GC，**缩堆无效**——缩了只会让 GC 更频繁。"
            heapTight(obs) ->
                "GC 占 ${fmt(pct)}% 且回不上来（${
                    obs.gcReclaimPct?.let { "回收率 ${fmt(it)}%" } ?: "活对象顶满"
                }），该加堆：${obs.heapMb} → ${minOf(ceiling, tuned?.heapMb ?: obs.heapMb + 512)}MB。"
            else ->
                "GC 占 ${fmt(pct)}%，但活对象还没顶满堆，先不动堆，按渲染距离/版本处理。"
        }
    }

    /**
     * 堆是不是真的紧。
     *
     * 判据不是「活对象占堆多少」——G1 在堆只用了 56% 时照样疯狂 young GC，
     * 因为 Eden 小、每次只回收 5%，回收的量还不够下一次分配。真正的信号是回收率。
     */
    private fun heapTight(obs: RunObservation): Boolean {
        obs.gcReclaimPct?.let { return it < MIN_RECLAIM_PCT }
        obs.liveSetMb?.let { return it > obs.heapMb * LIVE_SET_PRESSURE }
        return false
    }

    /** 缩堆目标：留出系统余量，但不低于 1024（再低 1.18 直接 OOM）。 */
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
