package io.pcla.core.device

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build

/**
 * PCLA 设备档位探测。
 *
 * 设计目标（PCLA 相对 FCL 的第一个差异点）：
 * 用户第一次打开启动器时，用一次离屏 GL 上下文探测 + SoC 匹配，
 * 直接给出「推荐版本 / 渲染距离 / 堆大小」，而不是把一堆参数丢给用户自己试。
 *
 * 数据来源：MT6833V 上实测的 8 组纯 Forge 基线（RD2 vs RD17），见 device_profiles.json。
 * 调用时机：Application.onCreate 的后台线程，结果缓存到 DataStore，不要在主线程调用。
 */
data class RenderDistance(
    val min: Int,
    val sweet: Int,
    val max: Int,
    val warnAt: Int,
    val ban: Int,
)

data class DeviceProfile(
    val id: String,
    val soc: String,
    val gpu: String,
    val glVersion: String,
    val ramMb: Long,
    val heapMb: Int,
    val renderDistance: RenderDistance,
    /** 版本 -> ok / untested / avoid / unsupported */
    val versionVerdict: Map<String, String>,
    val rules: List<String>,
) {
    fun recommendedRenderDistance(version: String): Int =
        if (versionVerdict[version] == "avoid") renderDistance.min else renderDistance.sweet

    fun shouldWarn(requiredRd: Int): Boolean = requiredRd >= renderDistance.warnAt
}

object DeviceProbe {

    /**
     * MT6833V 的实测档位。数值全部来自真机 CSV：
     * 1.12.2 RD2=200fps / RD17=60fps（TNT 谷底 18）；1.18 RD2=136 / RD17=20；
     * 1.19.2 RD2=52 且世界生成 125s；1.20.1 RD2=133。
     * 核心结论：渲染距离是唯一的一级杠杆，RD 2-4 是甜点，RD17 全版本跌到 5-23fps。
     */
    private val MT6833V = DeviceProfile(
        id = "mt6833v",
        soc = "MT6833V",
        gpu = "Mali-G57 MC2",
        glVersion = "OpenGL ES 3.2",
        ramMb = 8192L,
        heapMb = 2048,
        renderDistance = RenderDistance(min = 2, sweet = 4, max = 6, warnAt = 8, ban = 12),
        versionVerdict = mapOf(
            "1.12.2" to "ok",
            "1.16.5" to "ok",
            "1.18" to "ok",
            "1.19.2" to "avoid",
            "1.20.1" to "ok",
        ),
        rules = listOf(
            "RD > 8 -> 弹窗：实测 RD17 全版本跌至 5-23fps",
            "新实例默认 RD=4，不要沿用桌面版的 12",
            "1.19.2 世界生成耗时 125s（1.18/1.20.1 为 50s），默认不推荐",
            "实例标签由 mods/ 目录扫描生成，不信任手写实例名",
        ),
    )

    /** 未知机型的安全默认：宁可保守，让用户手动上调。 */
    private val GENERIC = DeviceProfile(
        id = "generic",
        soc = "unknown",
        gpu = "unknown",
        glVersion = "unknown",
        ramMb = 0L,
        heapMb = 2048,
        renderDistance = RenderDistance(min = 2, sweet = 4, max = 6, warnAt = 8, ban = 12),
        versionVerdict = mapOf("1.12.2" to "ok", "1.16.5" to "untested"),
        rules = listOf("未知机型，使用保守默认值"),
    )

    /** 阻塞调用，约 5-30ms（建一次离屏 EGL 上下文）。请放到后台线程。 */
    fun detect(context: Context): DeviceProfile {
        val (renderer, glVersion) = queryGl()
        val ramMb = totalRamMb(context)
        val socKey = socKey()

        val base = when {
            socKey.contains("MT6833") || renderer.contains("Mali-G57", ignoreCase = true) -> MT6833V
            else -> GENERIC
        }
        return base.copy(
            soc = socKey.ifBlank { base.soc },
            gpu = renderer.ifBlank { base.gpu },
            glVersion = glVersion.ifBlank { base.glVersion },
            ramMb = ramMb,
            // 堆上限跟着内存走，但已知机型用实测值，避免 12G 机器把堆开到爆
            heapMb = if (base.id == "generic") {
                (ramMb / 4).toInt().coerceIn(1024, 4096)
            } else {
                base.heapMb
            },
        )
    }

    fun socKey(): String {
        val model = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""
        return listOf(model, manufacturer, Build.HARDWARE, Build.BOARD, Build.DEVICE)
            .filter { it.isNotBlank() }
            .joinToString("|")
            .uppercase()
    }

    private fun totalRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.totalMem / (1024L * 1024L)
    }

    /**
     * 离屏 pbuffer + GLES2 上下文，只为读 GL_RENDERER / GL_VERSION。
     * 不创建 SurfaceView，不打扰 UI；失败就返回空串（不向上层抛异常）。
     */
    private fun queryGl(): Pair<String, String> {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return "" to ""
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return "" to ""

            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                return "" to ""
            }
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

            val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            return renderer to glVersion
        } catch (t: Throwable) {
            return "" to ""
        } finally {
            try {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
            } catch (_: Throwable) {
                // 清理失败不影响已探测到的结果
            }
        }
    }
}
