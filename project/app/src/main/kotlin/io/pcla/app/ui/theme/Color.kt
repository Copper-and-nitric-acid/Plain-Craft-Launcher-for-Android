package io.pcla.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Taken from PCL's `Plain Craft Launcher 2/Application.xaml` lines 22-65,
 * i.e. the original values of `ColorObject1`-`ColorObject8` and `ColorObjectGray1`-`Gray8`.
 * No "close enough" invented colors: next to the originals, an invented blue turns grey.
 */
object Pcl {
    val Blue1 = Color(0xFF343D4A) // PCL calls this Color1: the body text color (dark slate), not a blue
    val Blue2 = Color(0xFF0B5BCB)
    val Blue3 = Color(0xFF1370F3) // primary color
    val Blue4 = Color(0xFF4890F5)
    val Blue5 = Color(0xFF96C0F9)
    val Blue6 = Color(0xFFD5E6FD)
    val Blue7 = Color(0xFFE0EAFD)
    val Blue8 = Color(0xFFEAF2FE)

    val Gray1 = Color(0xFF404040)
    val Gray2 = Color(0xFF737373)
    val Gray3 = Color(0xFF8C8C8C)
    val Gray4 = Color(0xFFA6A6A6)
    val Gray5 = Color(0xFFCCCCCC)
    val Gray6 = Color(0xFFEBEBEB)
    val Gray7 = Color(0xFFF0F0F0)
    val Gray8 = Color(0xFFF5F5F5)

    val RedLight = Color(0xFFFF4C4C)
    val RedDark = Color(0xFFCE2111)
    val RedBack = Color(0x80FBDDDD)
}

/** The dark scheme is derived by darkening PCL's hues (PCL's dark values live in code, not in the XAML). */
object PclDark {
    val Blue2 = Color(0xFF1370F3)
    val Blue3 = Color(0xFF4890F5)
    val Blue8 = Color(0xFF16304F)
    val Gray8 = Color(0xFF1B1F24)
    val Surface = Color(0xFF22272E)
    val SurfaceHigh = Color(0xFF2A3038)
    val Text = Color(0xFFE6EAF0)
    val TextMuted = Color(0xFF9BA6B4)
    val Outline = Color(0xFF39424E)
}
