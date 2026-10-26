package io.pcla.core.download

import java.io.File

/**
 * 命令行入口，用来在真机上验证引擎：
 *   java -cp ... DownloadCliKt <url> <目标路径> [sha1]
 * 打印分档、线程数、进度百分比与最终结果。
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        println("用法: DownloadCliKt <url> <目标文件> [sha1] [并发线程数]")
        return
    }
    val url = args[0]
    val dest = File(args[1])
    val sha1 = args.getOrNull(2)?.takeIf { it.isNotBlank() && it != "-" }
    val threads = args.getOrNull(3)?.toIntOrNull()

    val base = DownloadConfig()
    val cfg = if (threads != null) DownloadConfig.fromUserSettings(threads, threads) else base
    val lastPct = intArrayOf(-1)

    val outcome = HttpDownloader(cfg, log = { println("  · $it") }).download(
        url = url,
        dest = dest,
        expectedSha1 = sha1,
        onProgress = { done, total ->
            if (total > 0) {
                val pct = (done * 100 / total).toInt()
                if (pct != lastPct[0]) {
                    lastPct[0] = pct
                    print("\r  $pct%  ${done / 1024}KB / ${total / 1024}KB   ")
                }
            }
        },
        onIssue = {
            println("\n下载不对劲：期望 ${it.expectedSha1}，实际 ${it.actualSha1}")
            UserChoice.SHOW_URL
        },
    )
    println()
    when (outcome) {
        is DownloadOutcome.Success -> println(
            "完成：${outcome.bytes} 字节，sha1=${outcome.sha1 ?: "未提供"}${if (outcome.forced) "（强制放行）" else ""}"
        )
        is DownloadOutcome.HashMismatch -> println("哈希不匹配，文件留在 ${outcome.tempFile}")
        is DownloadOutcome.Blocked -> println("已拦截：${outcome.message}")
        is DownloadOutcome.Failed -> println("失败：${outcome.reason}")
        DownloadOutcome.Cancelled -> println("已取消")
    }
}
