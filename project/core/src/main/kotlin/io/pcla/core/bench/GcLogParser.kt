package io.pcla.core.bench

enum class GcKind { YOUNG, MIXED, REMARK, CLEANUP, FULL, CONCURRENT, OTHER }

/**
 * One GC log event.
 *
 * Key distinction: [GcKind.CONCURRENT] is a concurrent phase (no stop-the-world, no dropped
 * frames); everything else is STW. Early analysis treated Concurrent Mark Cycle as a pause and
 * produced the absurd "longest pause 8.2s" — the real longest STW was only 298ms.
 */
data class GcEvent(
    /** Milliseconds since JVM start */
    val atMillis: Long,
    val durationMs: Double,
    val kind: GcKind,
    val cause: String? = null,
    val heapBeforeMb: Int? = null,
    val heapAfterMb: Int? = null,
) {
    val stopTheWorld: Boolean get() = kind != GcKind.CONCURRENT
}

data class Cluster(val startSeconds: Int, val endSeconds: Int, val totalMs: Double, val maxMs: Double)

data class GcSummary(
    val wallSeconds: Double,
    val stopTheWorldCount: Int,
    val meanMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
    val totalStwMs: Double,
    val pctOfWall: Double,
    val over50Ms: Int,
    val over100Ms: Int,
    val fullGcCount: Int,
    val concurrentCycles: Int,
    val maxConcurrentMs: Double,
    val clusters: List<Cluster>,
) {
    /** Over 3% of wall-clock time spent in pauses is definitely perceivable. */
    val isProblematic: Boolean get() = pctOfWall > 3.0 || maxMs > 100
}

/**
 * Parses `-Xlog:gc` output.
 *
 * Supports the JDK 9+ unified log format (what FCL's bundled jre17 emits):
 *   [2.572s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 113M->20M(2048M) 18.425ms
 * and the JDK 8 -Xloggc format (best effort; missing fields become null).
 */
object GcLogParser {

    private val JDK9 = Regex("""^\[(\d+(?:\.\d+)?)s]\[(?:info|debug)]\[gc(?:,[a-z]+)?] GC\((\d+)\) (.+?)\s+([\d.]+)(ms|s)\s*$""")
    private val JDK8 = Regex("""^\S+: ([\d.]+): \[(?:GC|Full GC) ?(?:pause)? ?\(?([^\])\d]*)\)?[^]]*?([\d.]+) secs]""")
    private val HEAP = Regex("""(\d+)M->(\d+)M""")

    /** Known GC causes; order matters — more specific ones match first. */
    private val CAUSES = listOf(
        "GCLocker Initiated GC", "Metadata GC Threshold", "G1 Evacuation Pause",
        "Prepare Mixed", "Concurrent Start", "Humongous Allocation",
        "System.gc()", "Allocation Failure", "To-space Exhausted",
    )

    fun parse(text: String): List<GcEvent> {
        val out = ArrayList<GcEvent>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.contains("[gc")) {
                if (!trimmed.contains("[GC")) continue
            }
            JDK9.find(trimmed)?.let { m ->
                val seconds = m.groupValues[1].toDoubleOrNull() ?: return@let
                val body = m.groupValues[3]
                val value = m.groupValues[4].toDoubleOrNull() ?: return@let
                val ms = if (m.groupValues[5] == "s") value * 1000 else value
                val heap = HEAP.find(body)
                out.add(
                    GcEvent(
                        atMillis = (seconds * 1000).toLong(),
                        durationMs = ms,
                        kind = kindOf(body),
                        cause = CAUSES.firstOrNull { body.contains(it) },
                        heapBeforeMb = heap?.groupValues?.get(1)?.toIntOrNull(),
                        heapAfterMb = heap?.groupValues?.get(2)?.toIntOrNull(),
                    ),
                )
                return@let
            }
            JDK8.find(trimmed)?.let { m ->
                val seconds = m.groupValues[1].toDoubleOrNull() ?: return@let
                val value = m.groupValues[3].toDoubleOrNull() ?: return@let
                val body = trimmed
                out.add(
                    GcEvent(
                        atMillis = (seconds * 1000).toLong(),
                        durationMs = value * 1000,
                        kind = if (body.contains("Full GC")) GcKind.FULL else kindOf(body),
                        cause = CAUSES.firstOrNull { body.contains(it) },
                    ),
                )
            }
        }
        return out
    }

    private fun kindOf(body: String): GcKind = when {
        body.startsWith("Concurrent") -> GcKind.CONCURRENT
        body.contains("Pause Full") -> GcKind.FULL
        body.contains("Pause Mixed") -> GcKind.MIXED
        body.contains("Pause Remark") -> GcKind.REMARK
        body.contains("Pause Cleanup") -> GcKind.CLEANUP
        body.contains("Pause Young") -> GcKind.YOUNG
        else -> GcKind.OTHER
    }

    fun summarize(events: List<GcEvent>, clusterBucketSeconds: Int = 30): GcSummary {
        val stw = events.filter { it.stopTheWorld }
        val durations = stw.map { it.durationMs }.sorted()
        val stwTotal = durations.sum()
        val wallSeconds = (events.maxOfOrNull { it.atMillis } ?: 0L) / 1000.0
        val concurrent = events.filter { it.kind == GcKind.CONCURRENT }

        val buckets = stw.groupBy { (it.atMillis / 1000).toInt() / clusterBucketSeconds }
        val clusters = buckets.entries
            .map { (k, v) -> Cluster(k * clusterBucketSeconds, (k + 1) * clusterBucketSeconds, v.sumOf { it.durationMs }, v.maxOf { it.durationMs }) }
            .filter { it.totalMs >= 1000 } // only >= 1000ms of pauses in one bucket is a "cluster"; else it is normal jitter
            .sortedByDescending { it.totalMs }

        return GcSummary(
            wallSeconds = wallSeconds,
            stopTheWorldCount = stw.size,
            meanMs = if (durations.isEmpty()) 0.0 else stwTotal / durations.size,
            p50Ms = percentile(durations, 0.50),
            p95Ms = percentile(durations, 0.95),
            p99Ms = percentile(durations, 0.99),
            maxMs = durations.lastOrNull() ?: 0.0,
            totalStwMs = stwTotal,
            pctOfWall = if (wallSeconds <= 0) 0.0 else stwTotal / 1000 / wallSeconds * 100,
            over50Ms = durations.count { it > 50 },
            over100Ms = durations.count { it > 100 },
            fullGcCount = events.count { it.kind == GcKind.FULL },
            concurrentCycles = concurrent.size,
            maxConcurrentMs = concurrent.maxOfOrNull { it.durationMs } ?: 0.0,
            clusters = clusters,
        )
    }

    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
