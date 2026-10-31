package io.pcla.app

import io.pcla.core.net.Probe
import io.pcla.core.net.UrlHealth
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网址体检的实做部分：拿 HEAD 敲一下，记下状态码和耗时。
 *
 * 用 HttpURLConnection 而不是 OkHttp，是因为 okhttp 是 download 模块的
 * implementation 依赖，没有暴露到 app 的编译期 classpath。为了四个 HEAD
 * 请求去加一条依赖不值。
 */
object NetProbe {

    /** 真要下载时会碰到的几个地址，挂了哪个都会卡住「① 下载并解压」。 */
    val targets: List<Pair<String, String>> = listOf(
        "BMCLAPI 版本清单" to "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json",
        "Mojang 版本清单" to "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
        "BMCLAPI 资源站" to "https://bmclapi2.bangbang93.com/assets/",
        "Gitee 仓库" to "https://gitee.com/copper-and-nitric-acid/plain-craft-launcher-for-android/raw/master/README.md",
    )

    fun probeAll(timeoutMs: Int = 6000): List<Probe> = targets.map { (label, url) -> probe(label, url, timeoutMs) }

    fun probe(label: String, url: String, timeoutMs: Int = 6000): Probe {
        val started = System.nanoTime()
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PCLA-probe")
            }
            try {
                val code = conn.responseCode
                Probe(label, url, code, (System.nanoTime() - started) / 1_000_000)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Probe(label, url, 0, (System.nanoTime() - started) / 1_000_000, t.message ?: t.javaClass.simpleName)
        }
    }

    fun render(list: List<Probe>): String = UrlHealth.render(list)
}
