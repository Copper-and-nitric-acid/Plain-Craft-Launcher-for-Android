package io.pcla.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Pcl.Blue3,
    onPrimary = Color.White,
    primaryContainer = Pcl.Blue7,
    onPrimaryContainer = Pcl.Blue2,
    secondary = Pcl.Blue4,
    onSecondary = Color.White,
    secondaryContainer = Pcl.Blue8,
    onSecondaryContainer = Pcl.Blue2,
    tertiary = Pcl.Blue5,
    background = Pcl.Gray8,
    onBackground = Pcl.Blue1,
    surface = Color.White,
    onSurface = Pcl.Blue1,
    surfaceVariant = Pcl.Blue8,
    onSurfaceVariant = Pcl.Gray2,
    outline = Pcl.Gray5,
    outlineVariant = Pcl.Gray6,
    error = Pcl.RedLight,
    onError = Color.White,
    errorContainer = Pcl.RedBack,
    onErrorContainer = Pcl.RedDark,
)

private val DarkColors = darkColorScheme(
    primary = PclDark.Blue3,
    onPrimary = Color(0xFF04203F),
    primaryContainer = PclDark.Blue8,
    onPrimaryContainer = Color(0xFFCFE3FF),
    secondary = PclDark.Blue2,
    onSecondary = Color.White,
    secondaryContainer = PclDark.Blue8,
    onSecondaryContainer = Color(0xFFCFE3FF),
    tertiary = PclDark.Blue3,
    background = PclDark.Gray8,
    onBackground = PclDark.Text,
    surface = PclDark.Surface,
    onSurface = PclDark.Text,
    surfaceVariant = PclDark.SurfaceHigh,
    onSurfaceVariant = PclDark.TextMuted,
    outline = PclDark.Outline,
    outlineVariant = PclDark.Outline,
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0908),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD5),
)

/** PCL 正文固定 13px、标题 15、说明 11。这里照搬，不用 Material 默认的 16/14。 */
private val PclTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp),
)

/** 圆角对齐 PCL：按钮 3、卡片 6、胶囊 13.5（取 14）。 */
private val PclShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

@Composable
fun PclaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PclTypography,
        shapes = PclShapes,
        content = content,
    )
}
