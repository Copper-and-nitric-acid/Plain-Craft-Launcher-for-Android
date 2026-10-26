package io.pcla.core.download

import java.io.File

/** 一个待下载文件。mirrors 是备用源，按玩家给的顺序试。 */
data class DownloadTask(
    val url: String,
    val dest: File,
    val sha1: String? = null,
    val sizeHint: Long? = null,
    val mirrors: List<String> = emptyList(),
) {
    /** 去重后的候选地址，主地址永远排第一。 */
    val allUrls: List<String> get() = (listOf(url) + mirrors).filter { it.isNotBlank() }.distinct()

    val label: String get() = dest.name
}

sealed interface TaskResult {
    data class Ok(val task: DownloadTask, val bytes: Long, val sha1: String?, val url: String) : TaskResult
    data class Bad(val task: DownloadTask, val issue: DownloadOutcome.HashMismatch) : TaskResult
    data class Err(val task: DownloadTask, val reason: String) : TaskResult
    data class Skipped(val task: DownloadTask, val reason: String) : TaskResult
}

/** 汇总进度：任务级 + 字节级，两套都给，UI 想显示哪个都行。 */
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
