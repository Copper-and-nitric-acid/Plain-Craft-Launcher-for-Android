package io.pcla.core.device

import io.pcla.core.json.JsonParseException
import io.pcla.core.json.MiniJson

/**
 * 档位表里的一行：一个 SoC（可能覆盖多个机型）的实测结论与规则。
 *
 * 数据来源是 device_profiles.json，不是猜的。任何数值改动都应当附上实测记录，
 * 否则宁可不写——档位表一旦开始编数字，整个产品就失去意义了。
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
    /** G1 并行线程数。大小核机型上默认值会把停顿拖长，需要显式压到小核数量以内。 */
    val gcParallelThreads: Int? = null,
) {
    /** 版本号前缀匹配："1.18.2" 命中 "1.18"。优先最长匹配，避免 1.1 吃掉 1.16.5。 */
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
 * 整体档位表 + 匹配逻辑。
 *
 * 匹配优先级：SoC 别名 > 机型名 > GPU 名。GPU 名单独不足以定位 SoC
 * （Mali-G57 出现在好几个联发科芯片上），所以只是加分项，不是主键。
 */
class ProfileTable(val profiles: List<DeviceProfile>) {

    val generic: DeviceProfile
        get() = profiles.firstOrNull { it.id == "generic" }
            ?: error("档位表必须包含 generic 兜底项")

    fun byId(id: String): DeviceProfile? = profiles.firstOrNull { it.id == id }

    /**
     * 返回最匹配的档位；都不匹配时返回 generic（永不返回 null，调用方不必处处判空）。
     */
    fun match(spec: DeviceSpec): DeviceProfile {
        val socKeyUpper = spec.socKey.uppercase()
        val modelUpper = spec.deviceModel.uppercase()

        var best: DeviceProfile? = null
        var bestScore = 0
        for (p in profiles) {
            if (p.id == "generic") continue
            var score = 0
            // SoC：别名命中，权重最高
            if (p.soc.isNotBlank() && socKeyUpper.contains(p.soc.uppercase())) score += 100
            for (alias in p.socAliases) {
                if (alias.isNotBlank() && socKeyUpper.contains(alias.uppercase())) score += 90
            }
            // 机型：命中说明就是这台机器
            for (d in p.devices) {
                if (d.isNotBlank() && modelUpper.contains(d.substringAfterLast(' ').uppercase())) score += 80
            }
            // GPU：只作辅证
            if (p.gpu.isNotBlank() && spec.glRenderer.contains(p.gpu, ignoreCase = true)) score += 40

            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        // 只靠 GPU 命中（40 分）不足以认定同一颗 SoC，回落到 generic
        return if (bestScore >= 80) best!! else generic
    }

    companion object {
        /** 解析 device_profiles.json 的全文。失败抛 [JsonParseException] / [IllegalArgumentException]。 */
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
