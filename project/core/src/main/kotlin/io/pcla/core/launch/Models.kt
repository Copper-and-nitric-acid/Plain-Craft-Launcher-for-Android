package io.pcla.core.launch

enum class Loader { VANILLA, FORGE, FABRIC, NEOFORGE, QUILT }

/**
 * Input for one "prepare to launch".
 *
 * renderDistance is the user's/instance's current render distance, not the recommendation — that is computed by
 * [JvmTuner] and put in the output; the two must stay separate, or we cannot say "you want 12, I suggest 4".
 */
data class LaunchTarget(
    val mcVersion: String,
    val loader: Loader = Loader.VANILLA,
    val modCount: Int = 0,
    val renderDistance: Int = 4,
    /** Heap size set by hand; null means let the algorithm decide. */
    val heapOverrideMb: Int? = null,
)

enum class Severity { INFO, WARN, BLOCK }

/**
 * One diagnostic for the user. code is a stable id (for tests and telemetry), message is plain-language Chinese.
 * BLOCK does not mean "launch forbidden" but "must confirm in a dialog first" — different from
 * silently degrading when information is thin. PCLA's rule: want to override my settings, know the cost.
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
