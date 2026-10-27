package io.pcla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pcla.app.GameInstaller
import io.pcla.app.RuntimePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 启动页：装（下载+解压）→ 启动（exec java）→ 日志。
 * 只做一件事：把「点了启动到底发生了什么」原样打出来。
 */
@Composable
internal fun LaunchScreen(heapMb: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf("1.16.5") }
    var stage by remember { mutableStateOf("未开始") }
    var log by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf<GameInstaller.Installed?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun append(line: String) {
        log = if (log.length > 20000) log.takeLast(12000) + "\n" + line else log + "\n" + line
    }

    PclCard("启动") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PclField("版本", version)
            PclField("堆", "${heapMb}MB（来自档位推荐）")
            PclField("状态", stage)
            PclButton(
                text = if (busy) "进行中…" else "① 下载并解压",
                enabled = !busy,
                highlight = true,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                GameInstaller.install(context, version, heapMb) { p ->
                                    stage = buildString {
                                        append(p.stage)
                                        if (p.total > 0) append(" ${p.done}/${p.total}")
                                        if (p.bytes > 0) append(" ${p.bytes / 1048576}MB")
                                        if (p.message.isNotEmpty()) append(" · ${p.message}")
                                    }
                                }
                            }
                        }.onSuccess { installed = it; stage = "就绪，可以启动" }
                            .onFailure { append("安装失败：" + (it.message ?: it.javaClass.simpleName)) }
                        busy = false
                    }
                },
            )
            PclButton(
                text = "② 启动",
                enabled = !busy && installed != null,
                onClick = {
                    val ready = installed ?: return@PclButton
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                GameInstaller.launch(context, ready, heapMb) { line -> append(line) }
                            }
                        }.onFailure { append("启动失败：" + (it.message ?: it.javaClass.simpleName)) }
                        busy = false
                    }
                },
            )
            Text(
                text = log.ifEmpty { "日志会出现在这里" },
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}
