package io.pcla.core.net

/**
 * 一个地址的体检结果。
 *
 * 这里刻意只放「纯数据 + 纯判断」，真正的 HEAD 请求在 app 层用
 * HttpURLConnection 发（core 模块零依赖，不能引 OkHttp）。
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
 * 网址体检：把「能不能下」翻译成人话。
 *
 * 起因是用户那句「加上网址可用」——镜像站挂掉时，界面不能只显示一个
 * 转圈的进度条，得明确告诉你哪个地址死了、死法是什么。
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

    /** 按「可用 / 全部阵亡」给一句总结，界面直接显示这行。 */
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
