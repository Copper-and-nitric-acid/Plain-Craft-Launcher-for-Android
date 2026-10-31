package io.pcla.core.download

import java.io.File

/** One file to download. mirrors are fallbacks, tried in the order the player gave. */
data class DownloadTask(
    val url: String,
    val dest: File,
    val sha1: String? = null,
    val sizeHint: Long? = null,
    val mirrors: List<String> = emptyList(),
) {
    /** Deduplicated candidate URLs; the primary URL always comes first. */
    val allUrls: List<String> get() = (listOf(url) + mirrors).filter { it.isNotBlank() }.distinct()

    val label: String get() = dest.name
}

sealed interface TaskResult {
    data class Ok(val task: DownloadTask, val bytes: Long, val sha1: String?, val url: String) : TaskResult
    data class Bad(val task: DownloadTask, val issue: DownloadOutcome.HashMismatch) : TaskResult
    data class Err(val task: DownloadTask, val reason: String) : TaskResult
    data class Skipped(val task: DownloadTask, val reason: String) : TaskResult
}

/** Aggregate progress: task-level and byte-level, both supplied so the UI can pick either. */
data class OverallProgress(
    val doneTasks: Int,
    val totalTasks: Int,
    val doneBytes: Long,
    val totalBytes: Long,
) {
    val percent: Int
        get() = when {
            totalTasks == 0 -> 100
            totalBytes > 0 -> ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            else -> ((doneTasks * 100) / totalTasks).coerceIn(0, 100)
        }
}
