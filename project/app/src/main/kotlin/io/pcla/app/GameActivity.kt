package io.pcla.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The game window: one full-screen SurfaceView plus the in-process JVM.
 *
 * Why a separate Activity is mandatory: `glfwCreateWindow` wants a Surface that is **currently
 * displayed**. The 660x192 preview on the launcher page is for "inspection" only, wrong size and
 * wrong lifetime; here it gets full screen, landscape, keep-awake, and the Surface is handed to
 * libpojavexec.so only after `surfaceCreated` — reversed, native stores a dangling pointer.
 *
 * The few lines of text on screen are just a temporary status bar (the JVM covers it once up);
 * when something breaks and logcat isn't handy, it's the only way to see how far we got.
 */
class GameActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        status = TextView(this).apply {
            setTextColor(0xFFE8E8E8.toInt())
            textSize = 11f
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0x99000000.toInt())
            text = "PCLA 游戏窗口\n正在等 Surface…"
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )
        setContentView(root)
        surfaceView.holder.addCallback(this)
    }

    private fun log(line: String) {
        status.text = "PCLA 游戏窗口\n" + line
        android.util.Log.i("PCLA-Game", line)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (started) return
        started = true
        val versionId = intent.getStringExtra(EXTRA_VERSION) ?: "1.16.5"
        val heapMb = intent.getIntExtra(EXTRA_HEAP, 2048)
        val f = holder.surfaceFrame
        log("Surface 已创建 ${f.width()}x${f.height()}")
        // the JVM runs until the game exits, so this whole block goes on a worker thread;
        // this Activity's UI thread only displays status.
        Thread({
            try {
                val err = SurfaceBridge.attach(holder.surface)
                if (err != null) {
                    runOnUiThread { log("✗ Surface 交接失败：$err") }
                    return@Thread
                }
                runOnUiThread { log("✓ Surface 已交给 libpojavexec.so，准备 JVM…") }
                val installed = GameInstaller.assemble(this, versionId)
                runOnUiThread {
                    log("Java ${installed.javaMajor}，主类 ${installed.plan.mainClass}\n开始启动 JVM…")
                }
                val code = InProcessLauncher.launch(this, installed, heapMb) { line ->
                    runOnUiThread { status.append("\n" + line.take(90)) }
                }
                runOnUiThread { log("launchJVM 返回 $code") }
            } catch (t: Throwable) {
                runOnUiThread { log("✗ 失败：" + (t.message ?: t.javaClass.simpleName)) }
            }
        }, "pcla-jvm").start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        android.util.Log.i("PCLA-Game", "surfaceChanged ${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        android.util.Log.i("PCLA-Game", "surfaceDestroyed")
    }

    companion object {
        const val EXTRA_VERSION = "versionId"
        const val EXTRA_HEAP = "heapMb"
    }
}
