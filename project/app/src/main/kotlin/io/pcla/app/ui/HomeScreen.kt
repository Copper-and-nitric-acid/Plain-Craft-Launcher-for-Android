package io.pcla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pcla.core.launch.JvmConfig
import io.pcla.core.launch.Severity

@Composable
internal fun HomeScreen(report: Report, onReload: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VersionHero(report, onReload)
        PclCard("设备") {
            FieldGrid(
                listOf(
                    "SoC" to report.spec.socKey.ifBlank { "未知" },
                    "机型" to report.spec.deviceModel.ifBlank { "未知" },
                    "GPU" to report.spec.glRenderer.ifBlank { "未知" },
                    "GL 版本" to report.spec.glVersion.ifBlank { "未知" },
                    "内存" to "${report.spec.ramMb / 1024} GB",
                    "ABI" to report.spec.abi.ifBlank { "未知" },
                ),
            )
        }
        NativeCard()
        RankedVersions(report)
        report.configs[report.recommendation.version]?.let { DiagCard(it) }
        Spacer(Modifier.width(1.dp))
    }
}

/**
 * 对标 PCL 启动页左栏的纵向节奏：标签 → 大字版本号 → 主按钮（54 高、蓝渐变）→ 灰色小字说明。
 * 主按钮在 PCL 里是「启动」，这里换成「重新探测」——本版本还不启动游戏，不做假按钮。
 */
@Composable
private fun VersionHero(report: Report, onReload: () -> Unit) {
    val rec = report.recommendation
    val cfg = report.configs[rec.version]
    val scheme = MaterialTheme.colorScheme
    PclCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PclTag("已实测 ${report.ranking.size} 个版本", TagTone.BLUE)
            PclTag(report.profile.id.uppercase(), TagTone.GRAY)
        }
        Text(
            rec.version ?: "无推荐",
            fontSize = 27.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
        )
        Text(rec.reason.ifBlank { "档位表里没有这台机器的实测数据" }, fontSize = 11.sp, color = scheme.onSurfaceVariant)
        PclButton("重新探测", onClick = onReload, highlight = true, height = 54)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            PclField("建议渲染距离", "${report.profile.rd.sweet}")
            PclField("堆上限", "${cfg?.heapMb ?: report.profile.heapSweetMb} MB")
        }
    }
}

@Composable
private fun RankedVersions(report: Report) {
    PclCard("版本实测（分数 = 平均帧 + TNT 谷底×2）") {
        if (report.ranking.isEmpty()) {
            Text("档位表里没有这条 SoC 的实测数据，先用通用默认值。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@PclCard
        }
        report.ranking.forEachIndexed { index, pair ->
            val (version, m) = pair
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(version, Modifier.weight(1f), fontSize = 13.sp)
                Text(
                    "平均 ${m.rd2Max ?: "—"} · 谷底 ${m.tntTrough ?: "—"}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                val tag = verdictTag(m.verdict)
                PclTag(tag.first, tag.second)
            }
            if (index != report.ranking.lastIndex) PclDivider()
        }
    }
}

@Composable
private fun DiagCard(cfg: JvmConfig) {
    PclCard("诊断与启动参数") {
        if (cfg.diagnostics.isEmpty()) {
            Text("没有需要注意的项。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        cfg.diagnostics.forEach { d ->
            val tone = when (d.severity) {
                Severity.BLOCK -> TagTone.RED
                Severity.WARN -> TagTone.RED
                Severity.INFO -> TagTone.GRAY
            }
            val label = when (d.severity) {
                Severity.BLOCK -> "拦截"
                Severity.WARN -> "注意"
                Severity.INFO -> "信息"
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                PclTag(label, tone)
                Spacer(Modifier.width(8.dp))
                Text(d.message, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        PclCode(cfg.args.joinToString(" "))
    }
}

/** PCL 的设置页就是「灰色小标签 + 值」上下两行，两列排布。 */
@Composable
private fun FieldGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { i, pair ->
                    if (i > 0) Spacer(Modifier.width(12.dp))
                    PclField(pair.first, pair.second, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
