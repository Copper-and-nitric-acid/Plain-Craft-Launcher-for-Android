package io.pcla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pcla.app.NativeLibInfo
import io.pcla.app.NativeLibs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 启动游戏的底座：Pojav 系的 EGL / Mesa / gl4es / SDL 全在这里。 */
@Composable
internal fun NativeCard() {
    val context = LocalContext.current
    var libs by remember { mutableStateOf<List<NativeLibInfo>>(emptyList()) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        libs = withContext(Dispatchers.IO) { NativeLibs.list(context) }
    }
    PclCard("原生库") {
        if (libs.isEmpty()) {
            Text(
                "APK 里没解出 lib*.so，检查 jniLibs 是否真的打进去了。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@PclCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PclField("已随包安装", "${libs.size} 个 · ${sizeText(libs.sumOf { it.sizeBytes })}")
            PclField("路径", NativeLibs.dir(context).absolutePath)
            PclButton("试加载 gl4es（验证 dlopen 链路）") {
                result = NativeLibs.probeLoad(context, "libgl4es_114.so") ?: "加载成功，dlopen 链路通"
            }
            result?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

/** 只用于显示体积，别当成通用格式化工具。 */
internal fun sizeText(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
