package io.pcla.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pcla.app.ui.theme.Pcl
import io.pcla.app.ui.theme.PclDark
import io.pcla.core.device.Verdict

/** 圆形胶囊标签，尺寸照 PCL 的 MyRadioButton：高 27、圆角 13.5。 */
@Composable
fun PclTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val alpha = if (selected) 0.26f else 0.10f
    Box(
        Modifier
            .height(27.dp)
            .clip(RoundedCornerShape(13.5.dp))
            .background(Color.White.copy(alpha = alpha))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * PCL 的 MyCard：白底、圆角 6、标题在左上角、无边距内缩。
 * 标题不画分割线——PCL 原版就是靠留白分层，加线反而更"Material"。
 */
@Composable
fun PclCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.surface)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(6.dp)),
    ) {
        if (title != null) {
            Text(
                title,
                Modifier.padding(start = 15.dp, top = 12.dp),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
        }
        Column(
            Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * PCL 的 MyButton：圆角 3、1px 描边、文字颜色跟描边一致。
 * highlight 是启动键那种蓝渐变（渐变起点用 ColorObject4）。
 */
@Composable
fun PclButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    enabled: Boolean = true,
    height: Int = 44,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(3.dp)
    val border = if (highlight) Color.Transparent else scheme.onSurface.copy(alpha = 0.55f)
    val textColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.35f)
        highlight -> Color.White
        else -> scheme.onSurface
    }
    val bg: Brush = if (highlight) {
        Brush.linearGradient(listOf(Pcl.Blue4, Pcl.Blue3))
    } else {
        Brush.linearGradient(listOf(scheme.surface, scheme.surface))
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (enabled) border else scheme.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

enum class TagTone { BLUE, GRAY, RED }

/** 小标签：PCL 的 TypeOne 胶囊（高 27、圆角 13）缩小一号。 */
@Composable
fun PclTag(text: String, tone: TagTone, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val (container, content) = when (tone) {
        TagTone.BLUE -> if (dark) PclDark.Blue8 to Color(0xFFAED3FF) else Pcl.Blue7 to Pcl.Blue2
        TagTone.GRAY -> if (dark) PclDark.SurfaceHigh to PclDark.TextMuted else Pcl.Gray7 to Pcl.Gray2
        TagTone.RED -> if (dark) Color(0xFF4A1F1C) to Color(0xFFFFB4AB) else Pcl.RedBack to Pcl.RedDark
    }
    Box(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = content, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun verdictTag(verdict: Verdict): Pair<String, TagTone> = when (verdict) {
    Verdict.OK -> "可跑" to TagTone.BLUE
    Verdict.AVOID -> "避开" to TagTone.RED
    Verdict.UNTESTED -> "未测" to TagTone.GRAY
    Verdict.UNKNOWN -> "无数据" to TagTone.GRAY
}

/** 键值对。PCL 的设置项就是「灰色小标题 + 黑色值」的上下两行，不是左右两列。 */
@Composable
fun PclField(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 11.sp, color = scheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, color = scheme.onSurface)
    }
}

/** 日志/参数这类等宽内容块。 */
@Composable
fun PclCode(text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .background(if (isSystemInDarkTheme()) PclDark.Gray8 else Pcl.Gray8)
            .padding(10.dp),
    ) {
        Text(text, fontSize = 11.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
    }
}

/** 列表行的基线高度，照 MyListItem 的 42。 */
@Composable
fun PclRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(42.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

@Composable
fun PclDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
