package io.pcla.core.net

/**
 * Health-check result for one address.
 *
 * Deliberately only "pure data + pure judgment"; the real HEAD request is sent in the app layer with
 * HttpURLConnection (the core module has zero dependencies, so it cannot pull in OkHttp).
 */
data class Probe(
    val label: String,
    val url: String,
    val code: Int = 0,
    val ms: Long = 0,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && code in 200..399
}

/**
 * URL health check: translate "can it download" into plain words.
 *
 * It started from the user's line "make URL availability show": when a mirror dies, the UI must not show just a
 * spinning progress bar — it has to say which address is dead and how it died.
 */
object UrlHealth {

    fun classify(p: Probe): String = when {
        p.error != null -> "不可用：${p.error}"
        p.code == 0 -> "不可用：没连上"
        p.code in 200..399 -> "可用 ${p.ms}ms"
        p.code == 404 -> "地址在，但文件没了（404）"
        p.code == 403 -> "被拒绝（403）"
        p.code in 400..499 -> "请求有问题（${p.code}）"
        p.code in 500..599 -> "服务端挂了（${p.code}）"
        else -> "莫名其妙（${p.code}）"
    }

    /** One-line summary as "usable / all dead"; the UI shows this line directly. */
    fun summary(list: List<Probe>): String {
        if (list.isEmpty()) return "一个地址都没测"
        val ok = list.count { it.ok }
        val fastest = list.filter { it.ok }.minByOrNull { it.ms }
        return when {
            ok == 0 -> "全部 ${list.size} 个地址都不可用，先检查网络或挂梯子"
            ok == list.size ->
                "全部 ${list.size} 个地址可用" + (fastest?.let { "，最快是 ${it.label}（${it.ms}ms）" } ?: "")
            else -> "$ok/${list.size} 可用" + (fastest?.let { "，最快是 ${it.label}（${it.ms}ms）" } ?: "")
        }
    }

    fun render(list: List<Probe>): String = buildString {
        list.forEach { append(if (it.ok) "✓ " else "✗ ").append(it.label).append("  ").append(classify(it)).append('\n') }
        append(summary(list))
    }
}
