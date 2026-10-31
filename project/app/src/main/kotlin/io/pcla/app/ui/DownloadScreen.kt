package io.pcla.app.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pcla.app.ui.theme.Pcl
import io.pcla.core.download.DownloadConfig
import io.pcla.core.download.DownloadOutcome
import io.pcla.core.download.DownloadQueue
import io.pcla.core.download.DownloadTask
import io.pcla.core.download.OverallProgress
import io.pcla.core.download.TaskResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Download page: multi-file queue.
 *
 * One URL per line, with an optional SHA-1 (40 hex) after a space. Concurrency is across files, not
 * threads within one file — measured here: one connection 1.30 MB/s, four shards 1.24 MB/s, so splitting
 * one link only makes the shards fight for bandwidth; downloading several small files at once is what really helps.
 */
@Composable
fun DownloadScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    val meter = remember { SpeedMeter() }

    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var cancelFlag by remember { mutableStateOf(false) }
    var overall by remember { mutableStateOf(OverallProgress(0, 0, 0, 0)) }
    var speedText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val rows = remember { mutableStateListOf<Pair<String, String>>() }

    fun start() {
        val tasks = parseTasks(context, input)
        if (tasks.isEmpty()) {
            note = "没识别到 http/https 地址。一行一个，例如：\nhttps://bmclapi2.bangbang93.com/…/client.jar"
            return
        }
        rows.clear()
        tasks.forEach { rows.add(it.label to "排队中") }
        note = null
        running = true
        cancelFlag = false
        speedText = ""
        meter.reset()
        overall = OverallProgress(0, tasks.size, 0, tasks.sumOf { it.sizeHint ?: 0L })

        scope.launch {
            val queue = DownloadQueue(DownloadConfig(), maxConcurrent = 2) { msg ->
                // Queue logs are for debugging only; don't flood the UI
                android.util.Log.d("PCLA-DL", msg)
            }
            val results = withContext(Dispatchers.IO) {
                queue.run(
                    tasks = tasks,
                    onOverall = { o ->
                        val sp = meter.sample(o.doneBytes)
                        main.post {
                            overall = o
                            if (sp > 0) speedText = humanSpeed(sp)
                        }
                    },
                    onTaskDone = { r ->
                        val label = taskOf(r).label
                        main.post {
                            val i = rows.indexOfFirst { it.first == label }
                            if (i >= 0) rows[i] = label to statusOf(r)
                        }
                    },
                    isCancelled = { cancelFlag },
                )
            }
            val ok = results.count { it is TaskResult.Ok }
            val bad = results.count { it is TaskResult.Err || it is TaskResult.Bad }
            val skipped = results.count { it is TaskResult.Skipped }
            note = "结束：成功 $ok" +
                (if (bad > 0) "，失败 $bad" else "") +
                (if (skipped > 0) "，跳过 $skipped" else "") +
                "。文件在 ${downloadDir(context).absolutePath}"
            running = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PclCard("下载队列") {
            Text(
                "一行一个地址，地址后面空一格可以跟 SHA-1。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { if (!running) input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(3.dp),
                placeholder = { Text("https://…/client.jar", fontSize = 13.sp) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PclButton(
                    text = if (running) "下载中…" else "开始下载",
                    onClick = { start() },
                    modifier = Modifier.weight(1f),
                    highlight = true,
                    enabled = !running && input.isNotBlank(),
                )
                if (running) {
                    PclButton(
                        text = "取消",
                        onClick = { cancelFlag = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (overall.totalTasks > 0) {
                ProgressBar(overall.percent / 100f)
                Text(
                    "${overall.doneTasks}/${overall.totalTasks} 个文件 · ${humanBytes(overall.doneBytes)}" +
                        (if (speedText.isNotBlank()) " · $speedText" else ""),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            note?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (rows.isNotEmpty()) {
            PclCard("任务") {
                rows.forEach { (label, status) ->
                    PclRow {
                        Text(label, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1)
                        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        PclCard("说明") {
            Text(
                "· 小于 10MB 单连接，10–60MB 默认 4 分片，超过 60MB 动态加片。\n" +
                    "· 分片数在设置里改得动，但别调到 16 以上——这台机器实测 6 线程就开始吃 429。\n" +
                    "· 下载完成会校验 SHA-1；不匹配时坏文件不覆盖原文件，会先停下来问你。\n" +
                    "· 文件落在应用私有目录，暂时不影响你在文件管理器里看到的 Download 文件夹。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Pcl.Gray6),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(Pcl.Blue3),
        )
    }
}

private fun taskOf(r: TaskResult): DownloadTask = when (r) {
    is TaskResult.Ok -> r.task
    is TaskResult.Bad -> r.task
    is TaskResult.Err -> r.task
    is TaskResult.Skipped -> r.task
}

private fun statusOf(r: TaskResult): String = when (r) {
    is TaskResult.Ok -> "完成 · ${humanBytes(r.bytes)}" + (if (r.sha1 != null) " · 校验通过" else "")
    is TaskResult.Bad -> "SHA-1 不匹配"
    is TaskResult.Err -> "失败：${r.reason.take(40)}"
    is TaskResult.Skipped -> "跳过：${r.reason}"
}

/** One URL per line; `url sha1` separated by a space. */
private fun parseTasks(context: android.content.Context, raw: String): List<DownloadTask> =
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            val url = parts[0]
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@mapNotNull null
            val sha = parts.getOrNull(1)?.trim()?.takeIf { it.length in 40..45 }
            DownloadTask(url = url, dest = destFile(context, url), sha1 = sha)
        }
        .distinctBy { it.dest.absolutePath }
        .toList()

private class SpeedMeter {
    private var lastBytes = 0L
    private var lastAt = 0L

    fun reset() {
        lastBytes = 0L
        lastAt = 0L
    }

    /** Returns instantaneous speed (B/s); returns 0 on too-short sampling intervals so the number does not jitter. */
    fun sample(done: Long): Double {
        val now = System.currentTimeMillis()
        if (lastAt == 0L) {
            lastAt = now
            lastBytes = done
            return 0.0
        }
        val dt = (now - lastAt) / 1000.0
        if (dt < 0.3) return 0.0
        val speed = (done - lastBytes) / dt
        lastBytes = done
        lastAt = now
        return speed
    }
}

private fun humanSpeed(bytesPerSecond: Double): String = when {
    bytesPerSecond >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSecond / 1024 / 1024)
    bytesPerSecond >= 1024 -> String.format("%.0f KB/s", bytesPerSecond / 1024)
    else -> String.format("%.0f B/s", bytesPerSecond)
}

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun downloadDir(context: android.content.Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").apply { mkdirs() }

private fun destFile(context: android.content.Context, url: String): File {
    val raw = url.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }
    val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    return File(downloadDir(context), safe)
}
