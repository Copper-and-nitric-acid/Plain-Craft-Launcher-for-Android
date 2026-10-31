package io.pcla.core.device

import io.pcla.core.json.JsonParseException
import io.pcla.core.json.MiniJson

/**
 * One row of the profile table: measured conclusions and rules for one SoC (may cover several devices).
 *
 * The data comes from device_profiles.json, not from guessing. Any number change must carry a
 * measurement record, otherwise leave it out — once the table invents numbers, the product is moot.
 */
data class DeviceProfile(
    val id: String,
    val soc: String,
    val socAliases: List<String>,
    val gpu: String,
    val devices: List<String>,
    val heapSweetMb: Int,
    val rd: RenderDistanceRule,
    val measured: Map<String, Measured>,
    val rules: List<String>,
    /** G1 parallel threads. On big.LITTLE chips the default lengthens pauses, so clamp it to the little-core count. */
    val gcParallelThreads: Int? = null,
) {
    /** Version prefix match: "1.18.2" hits "1.18". Longest match wins, so "1.1" cannot eat 1.16.5. */
    fun measuredOrNull(version: String): Measured? {
        measured[version]?.let { return it }
        return measured.entries
            .filter { version.startsWith(it.key + ".") }
            .maxByOrNull { it.key.length }
            ?.value
    }

    fun verdictOf(version: String): Verdict = measuredOrNull(version)?.verdict ?: Verdict.UNKNOWN
}

/**
 * The whole profile table plus matching logic.
 *
 * Match priority: SoC alias > device name > GPU name. A GPU name alone cannot pin down an
 * SoC (Mali-G57 appears on several MediaTek chips), so it only adds points, never decides.
 */
class ProfileTable(val profiles: List<DeviceProfile>) {

    val generic: DeviceProfile
        get() = profiles.firstOrNull { it.id == "generic" }
            ?: error("档位表必须包含 generic 兜底项")

    fun byId(id: String): DeviceProfile? = profiles.firstOrNull { it.id == id }

    /**
     * Returns the best-matching profile, or generic if nothing matches (never null, so callers need no null checks).
     */
    fun match(spec: DeviceSpec): DeviceProfile {
        val socKeyUpper = spec.socKey.uppercase()
        val modelUpper = spec.deviceModel.uppercase()

        var best: DeviceProfile? = null
        var bestScore = 0
        for (p in profiles) {
            if (p.id == "generic") continue
            var score = 0
            // SoC: alias hit, highest weight
            if (p.soc.isNotBlank() && socKeyUpper.contains(p.soc.uppercase())) score += 100
            for (alias in p.socAliases) {
                if (alias.isNotBlank() && socKeyUpper.contains(alias.uppercase())) score += 90
            }
            // Device: a hit means it is exactly this machine
            for (d in p.devices) {
                if (d.isNotBlank() && modelUpper.contains(d.substringAfterLast(' ').uppercase())) score += 80
            }
            // GPU: corroboration only
            if (p.gpu.isNotBlank() && spec.glRenderer.contains(p.gpu, ignoreCase = true)) score += 40

            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        // A GPU-only hit (40 points) cannot establish the same SoC; fall back to generic
        return if (bestScore >= 80) best!! else generic
    }

    companion object {
        /** Parses the whole device_profiles.json. Throws [JsonParseException] / [IllegalArgumentException] on failure. */
        fun parse(json: String): ProfileTable {
            val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("根节点不是对象")
            val rawProfiles = root["profiles"] as? List<*> ?: throw IllegalArgumentException("缺少 profiles 数组")
            val list = rawProfiles.map { parseProfile(it as Map<*, *>) }
            require(list.any { it.id == "generic" }) { "profiles 中必须有一项 id=generic" }
            return ProfileTable(list)
        }

        private fun parseProfile(m: Map<*, *>): DeviceProfile {
            val rd = m["render_distance"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val measuredRaw = m["measured"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val measured = measuredRaw.entries.mapNotNull { (k, v) ->
                val version = k as? String ?: return@mapNotNull null
                val mv = v as? Map<*, *> ?: return@mapNotNull null
                val tnt = mv["tnt"] as? List<*>
                Measured(
                    version = version,
                    rd2Max = mv.intOrNull("rd2_max"),
                    rd17Max = mv.intOrNull("rd17_max"),
                    tntPeak = tnt?.getOrNull(0)?.let { (it as? Number)?.toInt() },
                    tntTrough = tnt?.getOrNull(1)?.let { (it as? Number)?.toInt() },
                    genSeconds = mv.intOrNull("gen_s"),
                    verdict = parseVerdict(mv["verdict"] as? String),
                    note = mv["note"] as? String ?: "",
                    reproducible = mv["reproducible"] as? Boolean ?: false,
                )
            }.associateBy { it.version }

            return DeviceProfile(
                id = m.string("id"),
                soc = m.string("soc"),
                socAliases = m.strList("soc_alias"),
                gpu = m.string("gpu"),
                devices = m.strList("devices"),
                heapSweetMb = m.intOrNull("jvm_heap_sweet_mb") ?: 2048,
                rd = RenderDistanceRule(
                    min = rd.intOrNull("min") ?: 2,
                    sweet = rd.intOrNull("sweet") ?: 4,
                    max = rd.intOrNull("max") ?: 6,
                    warnAt = rd.intOrNull("warn_at") ?: 8,
                    ban = rd.intOrNull("ban") ?: 12,
                ),
                measured = measured,
                rules = m.strList("rules"),
                gcParallelThreads = m.intOrNull("gc_parallel_threads"),
            )
        }

        private fun parseVerdict(s: String?): Verdict = when (s?.lowercase()) {
            "ok" -> Verdict.OK
            "avoid" -> Verdict.AVOID
            "untested" -> Verdict.UNTESTED
            else -> Verdict.UNKNOWN
        }

        private fun Map<*, *>.string(key: String): String = (this[key] as? String).orEmpty()

        private fun Map<*, *>.intOrNull(key: String): Int? = (this[key] as? Number)?.toInt()

        private fun Map<*, *>.strList(key: String): List<String> =
            (this[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    }
}
