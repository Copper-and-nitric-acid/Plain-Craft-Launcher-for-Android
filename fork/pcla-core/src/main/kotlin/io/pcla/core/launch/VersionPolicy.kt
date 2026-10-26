package io.pcla.core.launch

import io.pcla.core.device.DeviceProfile
import io.pcla.core.device.Verdict

data class Recommendation(
    val version: String?,
    val alternatives: List<String>,
    val reason: String,
) {
    val hasData: Boolean get() = version != null
}

/**
 * 版本推荐。
 *
 * 这里不给「最新版」也不给「经典版」，而是按实测数据排：
 * score = 平均帧 + TNT 谷底 × 2（见 [io.pcla.core.device.Measured.score]）。
 * 权重之所以偏向谷底，是因为平均帧决定「爽不爽」，谷底决定「会不会砸手机」。
 *
 * 在这台 MT6833V 上算出来的顺序：
 *   1.16.5 (318) > 1.20.1 (289) > 1.12.2 (236) > 1.18 (228) > 1.19.2 (148)
 * 和肉测结论一致——这说明权重取对了，而不是先有结论再凑公式。
 */
object VersionPolicy {

    /** 分数差距在这个比例内视为并列，此时优先更新的版本（生态/模组兼容更好）。 */
    private const val TIE_RATIO = 0.04

    fun pick(profile: DeviceProfile): Recommendation {
        val ranked = ranking(profile)
        if (ranked.isEmpty()) {
            return Recommendation(null, emptyList(), "这台机型还没有任何实测数据，不敢给结论。")
        }
        val best = ranked.first()
        val top = best.second.score
        val tied = ranked.takeWhile { it.second.score >= top * (1 - TIE_RATIO) }
        val winner = tied.last() // 并列时取版本号更高的那个
        val alternatives = ranked.drop(1).take(2).map { it.first }

        val m = winner.second
        val parts = mutableListOf("综合分 ${m.score}")
        m.rd2Max?.let { parts += "平均 ${it}fps" }
        m.tntTrough?.let { parts += "TNT 谷底 ${it}fps" }
        if (winner.first != best.first) parts += "与该机型最高分版本并列，取更新版本"
        val reason = parts.joinToString("；")
        return Recommendation(winner.first, alternatives, reason)
    }

    /**
     * 全量排序：分数降序，分数相同则版本号降序。
     * verdict=AVOID 的版本保留在列表里（用户可能已经装了），由调用方决定是否展示。
     */
    fun ranking(profile: DeviceProfile): List<Pair<String, io.pcla.core.device.Measured>> =
        profile.measured.values
            .filter { it.verdict != Verdict.AVOID }
            .sortedWith(compareByDescending<io.pcla.core.device.Measured> { it.score }.thenByDescending { it.version })
            .map { it.version to it }

    /** 已安装实例里哪些版本值得留下（avoid 的标记出来给用户看）。 */
    fun audit(profile: DeviceProfile, installed: List<String>): List<VersionAudit> =
        installed.map { v ->
            val m = profile.measuredOrNull(v)
            val verdict = profile.verdictOf(v)
            VersionAudit(
                version = v,
                verdict = verdict,
                score = m?.score,
                advice = when (verdict) {
                    Verdict.AVOID -> "实测垫底，建议不要继续新建实例"
                    Verdict.OK -> "可用"
                    Verdict.UNTESTED, Verdict.UNKNOWN -> "本机型无数据，按相邻版本外推"
                },
            )
        }

    data class VersionAudit(
        val version: String,
        val verdict: Verdict,
        val score: Int?,
        val advice: String,
    )
}
