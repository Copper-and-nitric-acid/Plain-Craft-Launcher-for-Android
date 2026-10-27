package io.pcla.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pcla.app.ui.theme.Pcl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PclaApp() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<Report?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        report = null
        error = null
        try {
            report = withContext(Dispatchers.IO) { buildReport(context) }
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PclTopBar(tab) { tab = it }
        val msg = error
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            val r = report
            when {
                msg != null -> CenteredMessage("设备探测失败：$msg") { reload++ }
                r == null -> CenteredMessage("正在读取 GPU 与内存…", null)
                tab == 0 -> HomeScreen(r) { reload++ }
                tab == 1 -> DegradeScreen(r.spec, r.profile)
                else -> DownloadScreen()
            }
        }
    }
}

/** 48px 蓝渐变标题栏，导航是 PCL 那排 27px 高的白字胶囊，不是侧栏也不是底栏。 */
@Composable
private fun PclTopBar(tab: Int, onTab: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(Pcl.Blue4, Pcl.Blue3)))
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // PCL 的 logo 是白色描边字母，这里用同字重的白字顶替，不做假 Path
            Text("PCL", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.width(13.dp))
            PclTab("体检", selected = tab == 0) { onTab(0) }
            Spacer(Modifier.width(5.dp))
            PclTab("降级", selected = tab == 1) { onTab(1) }
            Spacer(Modifier.width(5.dp))
            PclTab("下载", selected = tab == 2) { onTab(2) }
            Spacer(Modifier.weight(1f))
            Text("0.3.0", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun CenteredMessage(text: String, onRetry: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            PclButton("重试", onClick = onRetry, modifier = Modifier.width(160.dp), height = 40)
        }
    }
}
