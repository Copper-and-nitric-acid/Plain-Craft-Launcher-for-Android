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
        // the top bar is a blue gradient, so status bar icons must be white; edge-to-edge lets the gradient run under the status bar.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            PclaTheme {
                PclaApp()
            }
        }
    }

    /**
     * Dump one copy of a Java/Kotlin crash to the app's external dir (Android/data/io.pcla.app/files/)
     * so the user can drag it out. Native crashes (SIGSEGV) never reach here, but InProcessLauncher
     * writes a start marker before calling the JVM, telling "died in native" from "never got there".
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
