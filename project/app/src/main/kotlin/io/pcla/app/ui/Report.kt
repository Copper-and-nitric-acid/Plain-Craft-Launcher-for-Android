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
 * Everything one probe produces. Both pages share one copy — probing separately would yield two different heap caps.
 * ranking is sorted by composite score (average fps + trough×2) descending, straight from measured data.
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
