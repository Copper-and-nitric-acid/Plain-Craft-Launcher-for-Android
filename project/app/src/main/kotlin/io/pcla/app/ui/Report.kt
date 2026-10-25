package io.pcla.app.ui

import android.content.Context
import io.pcla.app.DeviceProbe
import io.pcla.app.ProfileLoader
import io.pcla.core.device.DeviceProfile
import io.pcla.core.device.DeviceSpec
import io.pcla.core.device.Measured
import io.pcla.core.launch.JvmConfig
import io.pcla.core.launch.JvmTuner
import io.pcla.core.launch.LaunchTarget
import io.pcla.core.launch.Loader
import io.pcla.core.launch.Recommendation
import io.pcla.core.launch.VersionPolicy

/**
 * 一次探测的全部结果。两个页面共用同一份——各探各的会出现两套堆上限。
 * ranking 按综合分（平均帧 + 谷底×2）降序，直接来自实测数据。
 */
internal data class Report(
    val spec: DeviceSpec,
    val profile: DeviceProfile,
    val recommendation: Recommendation,
    val ranking: List<Pair<String, Measured>>,
    val configs: Map<String, JvmConfig>,
)

internal fun buildReport(context: Context): Report {
    val table = ProfileLoader.load(context)
    val spec = DeviceProbe.probe(context)
    val profile = table.match(spec)
    val ranking = VersionPolicy.ranking(profile)
    val versions = ranking.map { it.first }.ifEmpty { listOf("1.16.5", "1.18", "1.20.1") }
    val configs = versions.associateWith { version ->
        JvmTuner.tune(spec, profile, LaunchTarget(version, Loader.FORGE, 0, profile.rd.sweet))
    }
    return Report(spec, profile, VersionPolicy.pick(profile), ranking, configs)
}
