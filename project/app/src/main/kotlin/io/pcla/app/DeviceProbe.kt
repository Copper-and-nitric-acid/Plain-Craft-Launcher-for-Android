package io.pcla.app

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import io.pcla.core.device.DeviceSpec

/**
 * The only job on the Android side: read device facts out and hand them to core to decide.
 *
 * No decision logic here — that lives in core/JvmTuner and VersionPolicy, which need to be
 * testable on a machine without a phone.
 */
object DeviceProbe {

    fun probe(context: Context): DeviceSpec {
        val (renderer, glVersion) = queryGl()
        return DeviceSpec(
            socKey = socKey(),
            deviceModel = Build.MODEL.orEmpty(),
            glRenderer = renderer,
            glVersion = glVersion,
            ramMb = totalRamMb(context),
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        )
    }

    /**
     * Multi-source concat: not every device fills in the SoC model (always empty below API 31),
     * and vendors often append variants in HARDWARE (MT6833V/ZA), so match with contains, not equals.
     */
    fun socKey(): String {
        val model = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""
        return listOf(model, manufacturer, Build.HARDWARE, Build.BOARD, Build.DEVICE, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString("|")
            .uppercase()
    }

    private fun totalRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.totalMem / (1024L * 1024L)
    }

    /**
     * Offscreen pbuffer + GLES2 context, only to read GL_RENDERER / GL_VERSION.
     * No SurfaceView, no UI impact; any failure silently degrades to "" (less info beats a crash).
     *
     * Must be called on a background thread: context creation blocks for tens of ms on some drivers.
     */
    private fun queryGl(): Pair<String, String> {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return "" to ""
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return "" to ""

            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) || num[0] == 0) return "" to ""
            val config = configs[0] ?: return "" to ""

            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return "" to ""

            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            if (surface == EGL14.EGL_NO_SURFACE) return "" to ""
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return "" to ""

            return GLES20.glGetString(GLES20.GL_RENDERER).orEmpty() to
                GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        } catch (_: Throwable) {
            return "" to ""
        } finally {
            runCatching {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
            }
        }
    }
}
