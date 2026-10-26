package io.pcla.core.fcl

import io.pcla.core.device.DeviceProfile
import io.pcla.core.device.DeviceSpec
import io.pcla.core.device.ProfileTable
import io.pcla.core.launch.Diagnostic
import io.pcla.core.launch.JvmTuner
import io.pcla.core.launch.LaunchTarget
import io.pcla.core.launch.Loader

/**
 * FCL fork 的唯一接入点。
 *
 * 设计约束：这里是 FCL 的代码和 PCLA 的代码唯一的缝，所以故意做得很窄——
 * 几个基本类型进去，几个基本类型出来。FCL 那边不需要知道 MiniJson、
 * ProfileTable、DegradeLadder 的存在，将来也不会因为 core 内部重构而改动。
 *
 * 用法（在 FCL 的启动路径里，真正构建 JVM 命令行之前）：
 * ```
 * val result = PclaBridge.evaluate(profilesJson, PclaBridge.Input(
 *     mcVersion = version.id,
 *     socKey = DeviceProbe.socKey(context),
 *     deviceModel = Build.MODEL,
 *     glRenderer = GL.getRenderer(),
 *     glVersion = GL.getVersion(),
 *     ramMb = totalRamMb(context),
 *     renderDistance = instanceSetting.renderDistance,
 *     modCount = countMods(runDir),
 *     loader = detectLoader(version.id).name,
 * ))
 * // result.jvmArgs → VersionSetting.javaArgs
 * // result.recommendedRenderDistance → options.txt 的 renderDistance
 * ```
 */
object PclaBridge {

    /** FCL 侧需要提供的全部信息。字段都可空是为了让 FCL 少改动，缺的走保守默认。 */
    data class Input(
        val mcVersion: String,
        val socKey: String = "",
        val deviceModel: String = "",
        val glRenderer: String = "",
        val glVersion: String = "",
        val ramMb: Long = 0L,
        val renderDistance: Int = 4,
        val modCount: Int = 0,
        val loader: String = "VANILLA",
        val abi: String = "arm64-v8a",
    )

    data class Result(
        /** 命中的档位 id，没命中是 "generic"。用来在关于页显示「本机按 MT6833V 档位调优」。 */
        val profileId: String,
        val heapMb: Int,
        /** 可直接拼进 VersionSetting.javaArgs 的参数表。 */
        val jvmArgs: List<String>,
        val recommendedRenderDistance: Int,
        val recommendedVersion: String?,
        /** true 表示有 BLOCK 级诊断，FCL 那边应当先弹窗确认再继续。 */
        val blocked: Boolean,
        val diagnostics: List<Diagnostic>,
    ) {
        /** 拼成一行，直接塞进 FCL 的单行 javaArgs 输入框。 */
        fun jvmArgsLine(): String = jvmArgs.joinToString(" ")
    }

    /**
     * 主入口。不抛异常：解析失败就退回 generic 档位，宁可给保守参数也不能让启动器崩。
     */
    fun evaluate(profilesJson: String, input: Input): Result {
        val table = runCatching { ProfileTable.parse(profilesJson) }.getOrNull()
        val spec = DeviceSpec(
            socKey = input.socKey,
            deviceModel = input.deviceModel,
            glRenderer = input.glRenderer,
            glVersion = input.glVersion,
            ramMb = input.ramMb,
            abi = input.abi,
        )
        val profile: DeviceProfile = table?.match(spec) ?: fallbackProfile()
        val target = LaunchTarget(
            mcVersion = input.mcVersion,
            loader = parseLoader(input.loader),
            modCount = input.modCount.coerceAtLeast(0),
            renderDistance = input.renderDistance.coerceAtLeast(1),
        )
        val config = JvmTuner.tune(spec, profile, target)
        return Result(
            profileId = profile.id,
            heapMb = config.heapMb,
            jvmArgs = config.args,
            recommendedRenderDistance = config.recommendedRenderDistance,
            recommendedVersion = config.recommendedVersion,
            blocked = config.blocked,
            diagnostics = config.diagnostics,
        )
    }

    /** 给「关于 / 诊断」页用：这台机器上每个版本的判定，key 是版本前缀。 */
    fun verdicts(profilesJson: String, input: Input): Map<String, String> {
        val table = runCatching { ProfileTable.parse(profilesJson) }.getOrNull() ?: return emptyMap()
        val spec = DeviceSpec(
            socKey = input.socKey,
            deviceModel = input.deviceModel,
            glRenderer = input.glRenderer,
            glVersion = input.glVersion,
            ramMb = input.ramMb,
            abi = input.abi,
        )
        return table.match(spec).measured.mapValues { it.value.verdict.name }
    }

    private fun parseLoader(raw: String): Loader =
        runCatching { Loader.valueOf(raw.trim().uppercase()) }.getOrDefault(Loader.VANILLA)

    /** JSON 都没了也要能给出安全参数：1024MB + RD4。 */
    private fun fallbackProfile(): DeviceProfile = DeviceProfile(
        id = "generic",
        soc = "unknown",
        socAliases = emptyList(),
        gpu = "unknown",
        devices = emptyList(),
        heapSweetMb = 2048,
        rd = io.pcla.core.device.RenderDistanceRule(min = 2, sweet = 4, max = 6, warnAt = 8, ban = 12),
        measured = emptyMap(),
        rules = listOf("档位表缺失，使用保守默认值"),
    )
}
