package io.pcla.app

import io.pcla.core.net.Probe
import io.pcla.core.net.UrlHealth
import java.net.HttpURLConnection
import java.net.URL

/**
 * URL health check, concretely: knock with a HEAD request, record status code and elapsed ms.
 *
 * HttpURLConnection rather than OkHttp because okhttp is an implementation dependency of the
 * download module and not exposed on app's compile classpath. Adding a dependency for four HEAD
 * requests is not worth it.
 */
object NetProbe {

    /** Addresses a real download hits; any one of them being down stalls "① Download and unpack". */
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
