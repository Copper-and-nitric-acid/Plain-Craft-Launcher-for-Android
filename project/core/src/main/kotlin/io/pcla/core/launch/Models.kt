package io.pcla.core.launch

enum class Loader { VANILLA, FORGE, FABRIC, NEOFORGE, QUILT }

/**
 * 一次「准备启动」的输入。
 *
 * renderDistance 是用户/实例当前的渲染距离，不是推荐值——推荐值由
 * [JvmTuner] 算出来放在输出里，两者要分开，否则没法告诉用户「你想开 12，我建议 4」。
 */
data class LaunchTarget(
    val mcVersion: String,
    val loader: Loader = Loader.VANILLA,
    val modCount: Int = 0,
    val renderDistance: Int = 4,
    /** 用户手动指定的堆大小；null 表示让算法决定。 */
    val heapOverrideMb: Int? = null,
)

enum class Severity { INFO, WARN, BLOCK }

/**
 * 一条给用户看的诊断。code 是稳定标识（给测试和埋点用），message 是中文人话。
 * BLOCK 不代表「禁止启动」，而是「必须先弹窗确认」——这和信息不足时静默降级
 * 是两回事，PCLA 的原则是：想改我给的设置，你得知道代价。
 */
data class Diagnostic(val code: String, val severity: Severity, val message: String)

data class JvmConfig(
    val heapMb: Int,
    val args: List<String>,
    val diagnostics: List<Diagnostic>,
    val recommendedRenderDistance: Int,
    val recommendedVersion: String?,
) {
    val blocked: Boolean get() = diagnostics.any { it.severity == Severity.BLOCK }
}
