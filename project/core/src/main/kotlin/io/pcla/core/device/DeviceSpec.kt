package io.pcla.core.device

/**
 * Every fact about one phone that "launch decision" needs.
 *
 * Pure data: the core module does not know Android exists; probing happens in the app
 * layer (the EGL14 impl in DeviceProbe.kt), so decision logic is unit-testable on desktop.
 */
data class DeviceSpec(
    /** SoC identification string, sources joined with |, e.g. "MT6833V/ZA|MT6833|MT6833V|PHJ110" */
    val socKey: String,
    /** Build.MODEL, e.g. "PHJ110" */
    val deviceModel: String,
    /** GL_RENDERER, e.g. "Mali-G57 MC2" */
    val glRenderer: String,
    /** GL_VERSION, e.g. "OpenGL ES 3.2 v1.r32p1" */
    val glVersion: String,
    val ramMb: Long,
    val abi: String = "arm64-v8a",
) {
    val ramGb: Int get() = (ramMb / 1024L).toInt()
}

/**
 * Render-distance tiers. Values come from real-device runs (see the measured field in
 * device_profiles.json): RD2 is the sweet spot, RD8+ becomes unplayable, RD12+ is "not allowed".
 */
data class RenderDistanceRule(
    val min: Int,
    val sweet: Int,
    val max: Int,
    val warnAt: Int,
    val ban: Int,
)

enum class Verdict { OK, AVOID, UNTESTED, UNKNOWN }

/** Measured result for one (device × version) combo. null means that item was not tested. */
data class Measured(
    val version: String,
    val rd2Max: Int? = null,
    val rd17Max: Int? = null,
    val tntPeak: Int? = null,
    val tntTrough: Int? = null,
    val genSeconds: Int? = null,
    val verdict: Verdict = Verdict.UNTESTED,
    val note: String = "",
    /** Whether a re-test reproduced it. A single unreproduced run cannot be a conclusion. */
    val reproducible: Boolean = false,
) {
    /**
     * Composite score: average FPS + a stability weight.
     *
     * Average FPS alone ranks 1.12.2 (200fps) first, yet its TNT trough is only 18fps —
     * "great normally, stutters the moment something explodes". Weighting the trough 2x
     * matches how it feels: 1.16.5(144+174=318) > 1.20.1(133+156=289) > 1.12.2(200+36=236).
     */
    val score: Int get() = (rd2Max ?: 0) + (tntTrough ?: 0) * 2
}
