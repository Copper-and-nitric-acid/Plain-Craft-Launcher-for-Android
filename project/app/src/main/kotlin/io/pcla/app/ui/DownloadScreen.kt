package io.pcla.app.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import io.pcla.core.download.HttpDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 只做「一个地址一个文件」，队列调度留给 v0.3；这样先让 APK 里真的有一条能跑的下载链路。 */
@Composable
internal fun DownloadScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    val meter = remember { SpeedMeter() }

    var url by remember { mutableStateOf("") }
    var shaText by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var speedText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PclCard("下载") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("文件地址", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = shaText,
                onValueChange = { shaText = it },
                label = { Text("SHA-1（可留空，填了就校验）", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            if (running) {
                ProgressBar(progress)
                Text(speedText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PclButton(
                text = if (running) "下载中…" else "开始下载",
                onClick = {
                    running = true
                    progress = 0f
                    result = null
                    speedText = "正在探测大小…"
                    meter.reset()
                    scope.launch {
                        val dest = destFile(context, url.trim())
                        val expected = shaText.trim().ifBlank { null }
                        val outcome = withContext(Dispatchers.IO) {
                            HttpDownloader.create(DownloadConfig()).download(
                                url = url.trim(),
                                dest = dest,
                                expectedSha1 = expected,
                                onProgress = { done, total ->
                                    val pct = if (total > 0) done.toFloat() / total else 0f
                                    val sp = meter.sample(done)
                                    main.post {
                                        progress = pct.coerceIn(0f, 1f)
                                        speedText = if (sp > 0) "${(pct * 100f).toInt()}% · ${humanSpeed(sp)}" else "下载中…"
                                    }
                                },
                            )
                        }
                        running = false
                        result = describe(outcome)
                    }
                },
                enabled = url.isNotBlank() && !running,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        result?.let { text ->
            PclCard("结果") {
                Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        PclCard("说明") {
            Text(
                "分片阈值：<10MB 单连接；10–60MB 默认 4 连接；>60MB 默认 8 连接。"
                    + "局域网地址与 169.254 地址会在发起请求前就被拦下。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "文件保存在应用私有目录，不需要存储权限。",
                fontSize = 11.sp,
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

private class SpeedMeter {
    private var lastBytes = 0L
    private var lastAt = 0L

    fun reset() {
        lastBytes = 0L
        lastAt = System.currentTimeMillis()
    }

    /** 返回瞬时速度（B/s），采样间隔太短时返回 0 以免数字乱跳。 */
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

private fun destFile(context: android.content.Context, url: String): File {
    val raw = url.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }
    val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    return File(dir, safe)
}

private fun describe(outcome: DownloadOutcome): String = when (outcome) {
    is DownloadOutcome.Success ->
        "完成：${outcome.file.name}，${outcome.bytes} 字节" + (outcome.sha1?.let { "，SHA-1 校验通过" } ?: "") +
            (if (outcome.forced) "（高风险，已强行继续）" else "")
    is DownloadOutcome.HashMismatch ->
        "SHA-1 不匹配，已暂停。\n期望 ${outcome.expectedSha1}\n实际 ${outcome.actualSha1}\n坏文件保留在 ${outcome.tempFile.name}"
    is DownloadOutcome.Blocked -> outcome.message
    is DownloadOutcome.Failed -> "失败：${outcome.reason}"
    DownloadOutcome.Cancelled -> "已取消"
}
