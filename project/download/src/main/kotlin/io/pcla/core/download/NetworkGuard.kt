package io.pcla.core.download

/**
 * 下载 URL 的预检。不是安全防护，是「省事」：
 * 大部分「下载失败」其实一眼就能看出来，没必要等 30 秒超时再报网络错误。
 */
object NetworkGuard {

    sealed interface Verdict {
        data object Ok : Verdict
        data class PrivateNetwork(val host: String) : Verdict
        data class LinkLocal(val host: String) : Verdict
        data class Malformed(val host: String) : Verdict
    }

    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun checkHost(host: String?): Verdict {
        val h = host?.trim()?.removeSurrounding("[", "]") ?: return Verdict.Malformed("")
        if (h.isEmpty()) return Verdict.Malformed(h)
        val m = ipv4.matchEntire(h) ?: return Verdict.Ok   // 域名放行，交给 DNS
        val octets = m.destructured.toList().map { it.toIntOrNull() ?: return Verdict.Malformed(h) }
        if (octets.any { it !in 0..255 }) return Verdict.Malformed(h)

        val (a, b) = octets[0] to octets[1]
        return when {
            // 169.254.0.0/16：拿不到 DHCP 时的自分配地址
            a == 169 && b == 254 -> Verdict.LinkLocal(h)
            // 10.0.0.0/8
            a == 10 -> Verdict.PrivateNetwork(h)
            // 172.16.0.0 – 172.31.255.255
            a == 172 && b in 16..31 -> Verdict.PrivateNetwork(h)
            // 192.168.0.0/16
            a == 192 && b == 168 -> Verdict.PrivateNetwork(h)
            // 127.0.0.0/8
            a == 127 -> Verdict.PrivateNetwork(h)
            else -> Verdict.Ok
        }
    }

    /** UI 文案。null 表示不用提示。 */
    fun messageOf(v: Verdict): String? = when (v) {
        Verdict.Ok -> null
        is Verdict.PrivateNetwork -> "Hey Bro，这是局域网 IP，外网连不上。" +
            "如果你懂行在测试穿透请忽略，不懂的话请点开高级选项。"
        is Verdict.LinkLocal -> "🚨 兄弟，获取不到 DHCP，你家路由器/光猫/交换机大概率出现环路" +
            "导致广播风暴了。如果是云服务器，立刻找服务商提工单！"
        is Verdict.Malformed -> "这个地址看起来不是有效的 IP 或域名，先检查一下有没有多打空格。"
    }

    fun checkUrl(url: String): Verdict {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        return checkHost(host)
    }
}
