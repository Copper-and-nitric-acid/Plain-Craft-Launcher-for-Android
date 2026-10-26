package io.pcla.core.download

import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 多文件下载队列。
 *
 * 为什么并发对象是「多文件」而不是「单文件多线程」：本机实测（天玑 700 / 8GB）
 * 单连接 1.30 MB/s、4 分片 1.24 MB/s、8 分片 1.06 MB/s 且开始吃 429 —— 链路才是瓶颈，
 * 把一条链路切成 8 份只会互相抢带宽。真正能提吞吐的是同时下多个文件，
 * 因为下载 1.20.1 一个实例要拿几百个小文件（library、asset），HTTP 握手和排队才是大头。
 *
 * 并发上限不是常数：撞到 429/503 就按 [AdaptiveLimiter] 减半，连续成功再一格一格放开。
 */
class DownloadQueue(
    config: DownloadConfig = DownloadConfig(),
    maxConcurrent: Int = 2,
    private val log: (String) -> Unit = {},
) {
    private val cfg = config.sanitized()
    private val limiter = AdaptiveLimiter(maxPermits = maxConcurrent.coerceIn(1, MAX_CONCURRENT))

    /** 闸门：实际能同时跑的 worker 数，随限流动。 */
    private val gate = Semaphore(limiter.permits, true)
    private val gateLock = Any()

    private val downloader = HttpDownloader(
        config = cfg,
        client = HttpDownloader.defaultClient(cfg),
        log = log,
        onThrottled = {
            val before = limiter.permits
            val after = limiter.onThrottled()
            val cut = before - after
            if (cut > 0) {
                // 当前线程已经持着一个许可，再借 cut 个；返回许可前物理上就少跑 cut 个任务
                gate.acquire(cut)
                log("撞到限流，并发 $before → $after（本线程暂时多占 $cut 个许可）")
            }
        },
    )

    fun run(
        tasks: List<DownloadTask>,
        onOverall: (OverallProgress) -> Unit = {},
        onTaskDone: (TaskResult) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<TaskResult> {
        if (tasks.isEmpty()) return emptyList()

        val doneTasks = AtomicInteger(0)
        val doneBytes = AtomicLong(0)
        val totalBytes = AtomicLong(tasks.sumOf { it.sizeHint ?: 0L })
        val results = Collections.synchronizedList(ArrayList<TaskResult>(tasks.size))

        fun publish() = onOverall(
            OverallProgress(doneTasks.get(), tasks.size, doneBytes.get(), totalBytes.get())
        )
        publish()

        val workers = minOf(tasks.size, limiter.maxPermits).coerceAtLeast(1)
        val pool = Executors.newFixedThreadPool(workers) { r ->
            Thread(r, "pcla-queue-${r.hashCode()}").apply { isDaemon = true }
        }

        val futures = tasks.map { task ->
            pool.submit {
                if (isCancelled()) {
                    results.add(TaskResult.Skipped(task, "已取消"))
                    doneTasks.incrementAndGet(); publish()
                    return@submit
                }
                gate.acquire()
                try {
                    val result = runOne(task, { n -> doneBytes.addAndGet(n); publish() }, isCancelled)
                    results.add(result)
                    onTaskDone(result)
                } catch (t: Throwable) {
                    val r = TaskResult.Err(task, t.message ?: t::class.java.simpleName)
                    results.add(r)
                    onTaskDone(r)
                } finally {
                    releaseRecovered()
                    gate.release()
                    doneTasks.incrementAndGet(); publish()
                }
            }
        }

        futures.forEach { it.get() }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        return results.toList()
    }

    /** 一个任务按换源链依次试，全部失败才判死。 */
    private fun runOne(
        task: DownloadTask,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ): TaskResult {
        task.dest.parentFile?.mkdirs()
        val chain = MirrorChain(task.allUrls)
        var lastError = "没有可用地址"
        var first = true

        for (url in chain.candidates()) {
            if (isCancelled()) return TaskResult.Skipped(task, "已取消")
            if (!first) {
                // 换源前清掉上一个源留下的分片，避免字节范围对不上拼出坏文件
                File(task.dest.parentFile, "${task.dest.name}.parts").deleteRecursively()
            }
            first = false

            when (val outcome = downloader.download(url, task.dest, task.sha1, onProgress = { n, _ -> onBytes(n) })) {
                is DownloadOutcome.Success -> {
                    chain.reportSuccess(url)
                    absorbSuccess()
                    return TaskResult.Ok(task, outcome.bytes, outcome.sha1, url)
                }
                is DownloadOutcome.HashMismatch -> {
                    // 这份源给的字节是错的，换源再试；都不行才把坏文件交给玩家处置
                    chain.reportFailure(url)
                    lastError = "SHA-1 不匹配（$url）"
                    File(task.dest.parentFile, "${task.dest.name}.parts").deleteRecursively()
                }
                is DownloadOutcome.Blocked -> return TaskResult.Err(task, outcome.message)
                DownloadOutcome.Cancelled -> return TaskResult.Skipped(task, "已取消")
                is DownloadOutcome.Failed -> {
                    chain.reportFailure(url)
                    lastError = outcome.reason
                }
            }
        }
        return TaskResult.Err(task, lastError)
    }

    /** 连续成功后慢慢放开并发，但不超过初始上限。 */
    private fun absorbSuccess() {
        synchronized(gateLock) {
            val before = limiter.permits
            val after = limiter.onSuccess()
            if (after > before) {
                repeat(after - before) { gate.release() }
                log("连续成功，并发 $before → $after")
            }
        }
    }

    private fun releaseRecovered() = Unit

    companion object {
        const val MAX_CONCURRENT = 8
    }
}
