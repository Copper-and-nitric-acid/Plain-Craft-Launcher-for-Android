package io.pcla.core.download

/**
 * 下载层的纯逻辑自检：阈值划分、线程数夹取、分片覆盖、IP 拦截。
 * 不碰网络——这部分能在任何没有网的环境里跑。
 */
object DownloadSelfTest {

    private var pass = 0
    private var fail = 0

    private fun eq(name: String, expected: Any?, actual: Any?) {
        if (expected == actual) { pass++; println("  ✓ $name") }
        else { fail++; println("  ✗ $name → 期望 $expected，实际 $actual") }
    }

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) { pass++; println("  ✓ $name") }
        else { fail++; println("  ✗ $name $detail") }
    }

    fun run(): Int {
        val cfg = DownloadConfig()
        println("=== 1. 分档阈值 ===")
        val mb = 1024L * 1024
        eq("9MB → 单线程", DownloadTier.SINGLE, SegmentPlanner.tierOf(9 * mb, cfg))
        eq("10MB → 并发", DownloadTier.PARALLEL, SegmentPlanner.tierOf(10 * mb, cfg))
        eq("60MB → 并发", DownloadTier.PARALLEL, SegmentPlanner.tierOf(60 * mb, cfg))
        eq("61MB → 火力全开", DownloadTier.TURBO, SegmentPlanner.tierOf(61 * mb, cfg))
        eq("9MB 线程数", 1, SegmentPlanner.threadsFor(9 * mb, cfg))
        eq("30MB 线程数", 4, SegmentPlanner.threadsFor(30 * mb, cfg))
        eq("100MB 线程数", 8, SegmentPlanner.threadsFor(100 * mb, cfg))

        println("\n=== 2. 玩家自定义的防呆 ===")
        eq("9999 被夹到上限", 16, DownloadConfig.validateThreads(9999))
        eq("0 被夹到下限", 1, DownloadConfig.validateThreads(0))
        eq("负数被夹到下限", 1, DownloadConfig.validateThreads(-3))
        eq("6 合法", 6, DownloadConfig.validateThreads(6))
        val custom = DownloadConfig.fromUserSettings(parallelThreads = 2, largeFileThreads = 9999)
        eq("自定义并发生效", 2, custom.parallelThreads)
        eq("大文件并发越界被夹", 16, custom.largeFileThreads)
        eq("阈值倒挂被纠正", true, DownloadConfig(singleThreadMaxBytes = 80 * mb, parallelMaxBytes = 10 * mb)
            .sanitized().let { it.parallelMaxBytes >= it.singleThreadMaxBytes })

        println("\n=== 3. 分片必须无缝无重叠 ===")
        for (size in listOf(10 * mb, 37 * mb, 60 * mb, 61 * mb, 500 * mb + 7)) {
            val ranges = SegmentPlanner.plan(size, cfg)
            val covered = ranges.sumOf { it.length }
            check("$size 字节覆盖完整", covered == size, "覆盖 $covered")
            check("$size 字节首片从 0 开始", ranges.first().start == 0L)
            check("$size 字节末片到末尾", ranges.last().end == size - 1)
            check("$size 字节无缝隙", ranges.zipWithNext().all { (a, b) -> a.end + 1 == b.start })
            check("$size 字节每片 ≥1MB", ranges.all { it.length >= cfg.minSegmentBytes })
        }

        println("\n=== 4. 地址拦截 ===")
        eq("10.x 拦", true, NetworkGuard.checkHost("10.0.0.5") is NetworkGuard.Verdict.PrivateNetwork)
        eq("192.168.x 拦", true, NetworkGuard.checkHost("192.168.1.1") is NetworkGuard.Verdict.PrivateNetwork)
        eq("172.16 拦", true, NetworkGuard.checkHost("172.16.0.1") is NetworkGuard.Verdict.PrivateNetwork)
        eq("172.31 拦", true, NetworkGuard.checkHost("172.31.255.254") is NetworkGuard.Verdict.PrivateNetwork)
        eq("172.32 放行", NetworkGuard.Verdict.Ok, NetworkGuard.checkHost("172.32.0.1"))
        eq("169.254 拦", true, NetworkGuard.checkHost("169.254.3.3") is NetworkGuard.Verdict.LinkLocal)
        eq("公网放行", NetworkGuard.Verdict.Ok, NetworkGuard.checkHost("bmclapi2.bangbang93.com"))
        eq("域名不误伤", NetworkGuard.Verdict.Ok, NetworkGuard.checkHost("192.168.example.com"))
        check("169.254 文案提 DHCP", NetworkGuard.messageOf(NetworkGuard.Verdict.LinkLocal("169.254.1.1"))
            ?.contains("DHCP") == true)

        println("\n=== 5. 哈希 ===")
        eq("前缀归一", "abc123", Checksum.normalize(" SHA1:ABC123 "))

        println("\n通过 $pass 项，失败 $fail 项")
        return if (fail == 0) 0 else 1
    }
}

fun main() {
    val code = DownloadSelfTest.run()
    if (code != 0) kotlin.system.exitProcess(code)
}
