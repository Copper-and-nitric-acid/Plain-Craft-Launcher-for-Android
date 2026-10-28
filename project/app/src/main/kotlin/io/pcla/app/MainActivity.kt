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
        installCrashHandler()
        // 顶栏是蓝渐变，状态栏必须是白图标；边到边让渐变直接铺到状态栏下面。
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            PclaTheme {
                PclaApp()
            }
        }
    }

    /**
     * Java/Kotlin 层的崩溃落一份到应用外部目录（Android/data/io.pcla.app/files/），
     * 用户能直接拖出来。native 崩溃（SIGSEGV）不会走到这里，但 InProcessLauncher
     * 会在调 JVM 前写一个 start 标记，用来区分「死在 native 里」和「根本没到那一步」。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val dir = getExternalFilesDir("crash") ?: filesDir
                dir.mkdirs()
                val f = java.io.File(dir, "crash-${System.currentTimeMillis()}.txt")
                f.writeText(
                    buildString {
                        appendLine("thread=${thread.name}")
                        appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine(error.toString())
                        appendLine(error.stackTraceToString())
                    }
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
