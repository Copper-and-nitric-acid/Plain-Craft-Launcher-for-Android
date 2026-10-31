package io.pcla.app

import android.content.Context
import io.pcla.core.device.ProfileTable

/**
 * The profile table comes from assets/device_profiles.json.
 *
 * Deliberately in assets rather than hard-coded: the table is updated with community-measured
 * data, and updating it should not need a recompile (later: hot-update profiles, no APK change).
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
