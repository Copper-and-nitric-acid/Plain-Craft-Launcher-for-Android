package io.pcla.app

import android.content.Context
import io.pcla.core.device.ProfileTable

/**
 * 档位表来自 assets/device_profiles.json。
 *
 * 刻意放在 assets 而不是硬编码：这份表会随着社区提交的实测数据更新，
 * 而更新它不应该需要重新编译（后续可以加「热更新 profile」而不动 APK）。
 */
object ProfileLoader {
    private var cached: ProfileTable? = null

    fun load(context: Context): ProfileTable = cached ?: synchronized(this) {
        cached ?: run {
            val json = context.assets.open("device_profiles.json").bufferedReader().use { it.readText() }
            ProfileTable.parse(json).also { cached = it }
        }
    }
}
