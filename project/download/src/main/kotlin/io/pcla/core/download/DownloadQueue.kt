package io.pcla.core.download

import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-file download queue.
 *
 * Why concurrency is "many files" rather than "one file, many threads": measured here
 * (Dimensity 700 / 8GB): 1 connection 1.30 MB/s, 4 segments 1.24 MB/s, 8 segments 1.06 MB/s
 * and it starts eating 429s — the link is the bottleneck, and cutting one link into 8 just makes
 * the pieces fight over bandwidth. What really raises throughput is downloading several files at
 * once, because one 1.20.1 instance pulls hundreds of small files (libraries, assets), so HTTP
 * handshakes and queuing dominate.
 *
 * The cap is not constant: on 429/503 halve it per [AdaptiveLimiter], then reopen it one step at
 * a time after consecutive successes.
 */
class DownloadQueue(
    config: DownloadConfig = DownloadConfig(),
    maxConcurrent: Int = 2,
    private val log: (String) -> Unit = {},
) {
    private val cfg = config.sanitized()
    private val limiter = AdaptiveLimiter(maxPermits = maxConcurrent.coerceIn(1, MAX_CONCURRENT))

    /** Gate: how many workers may actually run at once; it moves with the limiter. */
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
                // this thread already holds one permit; taking cut more means cut fewer tasks run until released
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

    /** Try one task through the mirror chain in order; only declare failure once all are exhausted. */
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
                // before switching mirrors drop the previous source's parts, so mismatched ranges can't be stitched into a bad file
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
                    // this source served wrong bytes: try the next one, hand the bad file to the player only if none work
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

    /** Reopen concurrency gradually after consecutive successes, never past the initial cap. */
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
