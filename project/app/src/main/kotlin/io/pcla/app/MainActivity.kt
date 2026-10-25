package io.pcla.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.pcla.app.ui.PclaApp
import io.pcla.app.ui.theme.PclaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 顶栏是蓝渐变，状态栏必须是白图标；边到边让渐变直接铺到状态栏下面。
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            PclaTheme {
                PclaApp()
            }
        }
    }
}
