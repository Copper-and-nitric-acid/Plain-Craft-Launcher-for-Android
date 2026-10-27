package io.pcla.core.device

/**
 * 一台手机在「启动决策」这个上下文里需要的全部事实。
 *
 * 这是纯数据：core 模块不知道 Android 的存在，探测由 app 层完成
 * （见 DeviceProbe.kt 的 EGL14 实现），从而让决策逻辑可以在桌面上单测。
 */
data class DeviceSpec(
    /** SoC 识别串，多个来源用 | 拼接，例如 "MT6833V/ZA|MT6833|MT6833V|PHJ110" */
    val socKey: String,
    /** Build.MODEL，例如 "PHJ110" */
    val deviceModel: String,
    /** GL_RENDERER，例如 "Mali-G57 MC2" */
    val glRenderer: String,
    /** GL_VERSION，例如 "OpenGL ES 3.2 v1.r32p1" */
    val glVersion: String,
    val ramMb: Long,
    val abi: String = "arm64-v8a",
) {
    val ramGb: Int get() = (ramMb / 1024L).toInt()
}

/**
 * 渲染距离档位。数值来自真机实测（见 device_profiles.json 的 measured 字段）：
 * RD2 是甜点，RD8 以上开始掉到不可玩，RD12 以上属于「不该允许的配置」。
 */
data class RenderDistanceRule(
    val min: Int,
    val sweet: Int,
    val max: Int,
    val warnAt: Int,
    val ban: Int,
)

enum class Verdict { OK, AVOID, UNTESTED, UNKNOWN }

/** 某个 (机型 × 版本) 组合的实测结果。null 表示该项没测。 */
data class Measured(
    val version: String,
    val rd2Max: Int? = null,
    val rd17Max: Int? = null,
    val tntPeak: Int? = null,
    val tntTrough: Int? = null,
    val genSeconds: Int? = null,
    val verdict: Verdict = Verdict.UNTESTED,
    val note: String = "",
    /** 是否复测复现过。没复现的单次数据不能当结论用。 */
    val reproducible: Boolean = false,
) {
    /**
     * 综合分：平均帧 + 稳定性权重。
     *
     * 只用平均帧会把 1.12.2（200fps）排第一，但它的 TNT 谷底只有 18fps，
     * 也就是「平时很爽、一炸就卡」。谷底给 2 倍权重，结论才和体感一致：
     * 1.16.5(144+174=318) > 1.20.1(133+156=289) > 1.12.2(200+36=236)。
     */
    val score: Int get() = (rd2Max ?: 0) + (tntTrough ?: 0) * 2
}
