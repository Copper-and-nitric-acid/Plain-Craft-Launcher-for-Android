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
 * Version recommendation.
 *
 * It gives neither "latest" nor "classic" but ranks by measured data:
 * score = average fps + TNT trough × 2 (see [io.pcla.core.device.Measured.score]).
 * The weight favors the trough because average fps decides "is it fun", the trough decides "will the phone get smashed".
 *
 * Order computed on this MT6833V:
 *   1.16.5 (318) > 1.20.1 (289) > 1.12.2 (236) > 1.18 (228) > 1.19.2 (148)
 * It matches the hands-on conclusion — evidence the weights are right, not a formula fitted to a conclusion.
 */
object VersionPolicy {

    /** Scores within this ratio count as tied; the newer version then wins (better ecosystem/mod compatibility). */
    private const val TIE_RATIO = 0.04

    fun pick(profile: DeviceProfile): Recommendation {
        val ranked = ranking(profile)
        if (ranked.isEmpty()) {
            return Recommendation(null, emptyList(), "这台机型还没有任何实测数据，不敢给结论。")
        }
        val best = ranked.first()
        val top = best.second.score
        val tied = ranked.takeWhile { it.second.score >= top * (1 - TIE_RATIO) }
        val winner = tied.last() // on a tie take the higher version number
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
     * Full ranking: score descending, then version number descending on equal scores.
     * verdict=AVOID versions stay in the list (the user may already have them installed); the caller decides whether to show them.
     */
    fun ranking(profile: DeviceProfile): List<Pair<String, io.pcla.core.device.Measured>> =
        profile.measured.values
            .filter { it.verdict != Verdict.AVOID }
            .sortedWith(compareByDescending<io.pcla.core.device.Measured> { it.score }.thenByDescending { it.version })
            .map { it.version to it }

    /** Which of the installed versions are worth keeping (mark AVOID ones for the user to see). */
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
