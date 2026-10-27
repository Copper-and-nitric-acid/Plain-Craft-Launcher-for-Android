package io.pcla.core

import io.pcla.core.bench.GcLogParser
import io.pcla.core.bench.GcReport
import io.pcla.core.device.DeviceSpec
import io.pcla.core.device.ProfileTable
import io.pcla.core.launch.ClasspathBuilder
import io.pcla.core.launch.DegradeLadder
import io.pcla.core.launch.JavaPolicy
import io.pcla.core.launch.JvmTuner
import io.pcla.core.launch.Platform
import io.pcla.core.launch.VersionMeta
import io.pcla.core.launch.Lever
import io.pcla.core.launch.RunObservation
import io.pcla.core.launch.LaunchTarget
import io.pcla.core.launch.Loader
import io.pcla.core.launch.Severity
import io.pcla.core.launch.VersionPolicy
import java.io.File

/**
 * 不依赖 JUnit 的自检程序。
 *
 * 为什么手写：core 模块要能在 Termux（无 Android SDK、无 Gradle）里
 * 用一条命令跑完，这就是「手机上也能验证」的底线。
 *
 *   kotlinc core/src/main/kotlin -include-runtime -d core.jar
 *   java -jar core.jar device_profiles.json
 */
private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
        println("  ✓ $name")
    } else {
        failed++
        println("  ✗ $name ${if (detail.isEmpty()) "" else "→ $detail"}")
    }
}

private fun eq(name: String, expected: Any?, actual: Any?) =
    check(name, expected == actual, "期望 $expected，实际 $actual")

fun main(args: Array<String>) {
    // 子命令：java -jar core.jar gc <gc.log>
    if (args.firstOrNull() == "gc") {
        val path = args.getOrNull(1)
        if (path == null) {
            println("用法: java -jar core.jar gc <gc.log>")
            kotlin.system.exitProcess(2)
        }
        println(GcReport.render(GcLogParser.parse(File(path).readText())))
        return
    }

    // 子命令：java -jar core.jar degrade <json> <版本> <当前RD> <实测fps> [采样秒] [堆MB] [GC占比%] [回收率%] [可用内存MB]
    if (args.firstOrNull() == "degrade") {
        val json = File(args.getOrNull(1) ?: "device_profiles.json").readText()
        val table = ProfileTable.parse(json)
        val version = args.getOrNull(2) ?: "1.16.5"
        val rd = args.getOrNull(3)?.toIntOrNull() ?: 17
        val fps = args.getOrNull(4)?.toDoubleOrNull() ?: 18.0
        val obs = RunObservation(
            avgFps = fps,
            sampleSeconds = args.getOrNull(5)?.toIntOrNull() ?: 60,
            renderDistance = rd,
            heapMb = args.getOrNull(6)?.toIntOrNull() ?: 2048,
            gcPausePct = args.getOrNull(7)?.toDoubleOrNull(),
            gcReclaimPct = args.getOrNull(8)?.toDoubleOrNull(),
            memAvailableMb = args.getOrNull(9)?.toIntOrNull(),
        )
        val spec = DeviceSpec("MT6833V|PHJ110", "PHJ110", "Mali-G57 MC2", "OpenGL ES 3.2", 7460L)
        val profile = table.match(spec)
        val tuned = JvmTuner.tune(spec, profile, LaunchTarget(version, Loader.FORGE, 0, rd))
        val plan = DegradeLadder.plan(obs, spec, profile, version, tuned)
        println("=== 降级建议：$version / RD$rd / ${obs.avgFps}fps ===")
        if (plan.actions.isEmpty()) println("  无需动作") else plan.actions.forEachIndexed { i, a ->
            println("  ${i + 1}. [${a.lever}] ${a.from} → ${a.to}")
            println("     理由：${a.reason}")
            println("     预期：${a.expected}")
        }
        println("  堆：${plan.heapVerdict}")
        plan.notes.forEach { println("  注：$it") }
        return
    }

    val jsonPath = args.firstOrNull() ?: "device_profiles.json"
    val json = File(jsonPath).readText()

    println("=== 1. 档位表解析 ===")
    val table = ProfileTable.parse(json)
    println("  解析到 ${table.profiles.size} 个档位：${table.profiles.joinToString { it.id }}")
    check("包含 generic 兜底", table.byId("generic") != null)

    val spec = DeviceSpec(
        socKey = "MT6833V/ZA|MT6833|MT6833V|PHJ110",
        deviceModel = "OPPO PHJ110",
        glRenderer = "Mali-G57 MC2",
        glVersion = "OpenGL ES 3.2 v1.r32p1",
        ramMb = 7634,
    )

    println("\n=== 2. 机型匹配 ===")
    val profile = table.match(spec)
    eq("匹配到 mt6833v", "mt6833v", profile.id)
    eq("未知机型回落 generic", "generic",
        table.match(spec.copy(socKey = "XYZ|FOO", deviceModel = "Nope", glRenderer = "Adreno 610")).id)
    check("仅 GPU 命中不足以认定 SoC",
        table.match(spec.copy(socKey = "UNKNOWN", deviceModel = "UNKNOWN")).id == "generic")

    println("\n=== 3. 版本推荐（按实测分数排序） ===")
    val rec = VersionPolicy.pick(profile)
    eq("推荐版本", "1.16.5", rec.version)
    println("  理由：${rec.reason}")
    println("  备选：${rec.alternatives}")
    val ranked = VersionPolicy.ranking(profile)
    println("  排序：" + ranked.joinToString(" > ") { "${it.first}(${it.second.score})" })
    eq("排序顺序", listOf("1.16.5", "1.20.1", "1.12.2", "1.18"), ranked.map { it.first })

    println("\n=== 4. JVM 参数生成（RD=4，无 mod） ===")
    val cases = listOf("1.12.2", "1.16.5", "1.18", "1.19.2", "1.20.1")
    // 默认堆 = 这台机型实测跑过的基线（2048）。1.18 曾按「版本系数×甜点」算到 3072，
    // 但那是 8GB 机器上不该默认给出的数字：加堆要等运行时 GC 证据（见 DegradeLadder）。
    val expectedHeap = mapOf("1.12.2" to 2048, "1.16.5" to 2048, "1.18" to 2048, "1.19.2" to 2048, "1.20.1" to 2048)
    for (v in cases) {
        val cfg = JvmTuner.tune(spec, profile, LaunchTarget(v, Loader.FORGE, 0, 4))
        val block = cfg.diagnostics.filter { it.severity == Severity.BLOCK }
        println("  %-7s heap=%4dMB  blocked=%-5s  %s".format(v, cfg.heapMb, block.isNotEmpty(), block.firstOrNull()?.code ?: ""))
        eq("$v 堆大小", expectedHeap[v], cfg.heapMb)
    }

    println("\n=== 5. 风险拦截 ===")
    val rd17 = JvmTuner.tune(spec, profile, LaunchTarget("1.16.5", Loader.FORGE, 0, 17))
    check("RD17 被标记为 BLOCK", rd17.blocked)
    eq("RD17 诊断码", "RD_BAN", rd17.diagnostics.first { it.severity == Severity.BLOCK }.code)
    // 渲染距离吃的是显存和 CPU，不是堆。实测 1.18 在 RD17 下用 2048MB 也没有 OOM。
    check("RD17 不改变堆", rd17.heapMb == 2048, "实际 ${rd17.heapMb}")

    val avoid = JvmTuner.tune(spec, profile, LaunchTarget("1.19.2", Loader.FORGE, 0, 4))
    check("1.19.2 被标记为 BLOCK", avoid.blocked)

    val greedy = JvmTuner.tune(spec, profile, LaunchTarget("1.18", Loader.FORGE, 0, 4, heapOverrideMb = 8192))
    check("超安全线被压回", greedy.heapMb <= 3072, "实际 ${greedy.heapMb}")
    eq("安全上限受机型实测兜住", 3072, JvmTuner.safeCeilingMb(spec, profile))
    check("超安全线有告警", greedy.diagnostics.any { it.code == "HEAP_CLAMPED" })

    println("\n=== 6. GC 日志解析（真实日志片段） ===")
    val sample = """
        [309.500s][info][gc] GC(226) Pause Young (Normal) (G1 Evacuation Pause) 980M->941M(2048M) 180.000ms
        [310.012s][info][gc] GC(227) Pause Young (Normal) (G1 Evacuation Pause) 1004M->948M(2048M) 135.380ms
        [310.669s][info][gc] GC(228) Pause Young (Concurrent Start) (G1 Evacuation Pause) 1037M->973M(2048M) 106.334ms
        [310.670s][info][gc] GC(229) Concurrent Mark Cycle
        [316.412s][info][gc] GC(235) Pause Young (Normal) (G1 Evacuation Pause) 1197M->1144M(2048M) 297.910ms
        [317.407s][info][gc] GC(236) Pause Young (Normal) (G1 Evacuation Pause) 1233M->1176M(2048M) 290.534ms
        [318.859s][info][gc] GC(229) Concurrent Mark Cycle 8189.169ms
        [674.638s][info][gc] GC(311) Pause Young (Mixed) (G1 Evacuation Pause) 632M->528M(2048M) 48.514ms
    """.trimIndent()
    val events = GcLogParser.parse(sample)
    eq("事件数", 7, events.size)
    eq("其中并发事件", 1, events.count { !it.stopTheWorld })
    // GC(229) 的起始行没有时长，属于「同一周期的开始」，重复计入会把并发周期数翻倍
    check("无时长的并发起始行被丢弃", events.none { it.atMillis == 310670L })
    val sum = GcLogParser.summarize(events)
    eq("最长 STW 停顿", 297.91, sum.maxMs)
    eq("最长并发周期", 8189.169, sum.maxConcurrentMs)
    eq("堆回收生效(1197→1144)", true, events.any { it.heapBeforeMb == 1197 && it.heapAfterMb == 1144 })
    check("簇检测命中 300s 窗口", sum.clusters.any { it.startSeconds == 300 })

    println("\n=== 7. 降级阶梯（帧率低于 20 时先动什么） ===")
    // 场景 A：1.16.5 拉 RD17，GC 很干净 → 必须先去动渲染距离，不许碰堆
    val planA = DegradeLadder.plan(
        RunObservation(avgFps = 18.0, sampleSeconds = 60, renderDistance = 17, heapMb = 2048,
            gcPausePct = 0.4, liveSetMb = 1100),
        spec, profile, "1.16.5",
    )
    eq("第一步是渲染距离", Lever.RENDER_DISTANCE, planA.actions.first().lever)
    eq("RD 17 降到 8", "8", planA.actions.first().to)
    check("GC 干净时不缩堆", planA.heapVerdict.contains("缩堆无效"), planA.heapVerdict)
    check("不掉进缩堆陷阱", planA.actions.none { it.lever == Lever.HEAP })

    // 场景 B：1.18 活对象顶满 + GC 吃掉 9% → 方向是加堆，不是缩
    val tuned118 = JvmTuner.tune(spec, profile, LaunchTarget("1.18", Loader.FORGE, 0, 4))
    val planB = DegradeLadder.plan(
        RunObservation(avgFps = 15.0, sampleSeconds = 60, renderDistance = 4, heapMb = 2048,
            gcPausePct = 9.0, gcReclaimPct = 5.0, liveSetMb = 950),
        spec, profile, "1.18", tuned118,
    )
    val heapB = planB.actions.firstOrNull { it.lever == Lever.HEAP }
    check("GC 吃帧时建议加堆", heapB != null && heapB.to == "3072", heapB?.to ?: "无动作")

    // 场景 C：低内存设备 + 可用内存吃紧 → 这时才该缩堆（用户提的 1024 在这里成立）
    val spec3g = spec.copy(ramMb = 3072L)
    val planC = DegradeLadder.plan(
        RunObservation(avgFps = 15.0, sampleSeconds = 60, renderDistance = 4, heapMb = 2048,
            gcPausePct = 1.0, liveSetMb = 400, memAvailableMb = 700),
        spec3g, profile, "1.16.5",
    )
    val heapC = planC.actions.firstOrNull { it.lever == Lever.HEAP }
    check("内存吃紧才缩堆", heapC != null && heapC.to == "1075", heapC?.to ?: "无动作")
    check("缩堆理由写的是内存不是帧率", planC.heapVerdict.contains("内存"), planC.heapVerdict)

    // 场景 D：采样不足 → 不下结论
    val planD = DegradeLadder.plan(
        RunObservation(avgFps = 12.0, sampleSeconds = 12, renderDistance = 17, heapMb = 2048),
        spec, profile, "1.16.5",
    )
    check("采样不足不下结论", planD.isEmpty, planD.heapVerdict)

    // Java 版本策略：错了会 Unsupported class file major version
    eq("1.12.2 → Java 8", 8, JavaPolicy.requiredMajor("1.12.2"))
    eq("1.16.5 → Java 8", 8, JavaPolicy.requiredMajor("1.16.5"))
    eq("1.18 → Java 17", 17, JavaPolicy.requiredMajor("1.18"))
    eq("1.19.2 → Java 17", 17, JavaPolicy.requiredMajor("1.19.2"))
    eq("1.20.1 → Java 17", 17, JavaPolicy.requiredMajor("1.20.1"))
    eq("1.20.5 → Java 21", 21, JavaPolicy.requiredMajor("1.20.5"))
    eq("1.21.4 → Java 21", 21, JavaPolicy.requiredMajor("1.21.4"))
    eq("快照版本回落 Java 8", 8, JavaPolicy.requiredMajor("23w45a"))

    // 版本 json → 启动命令行
    val metaJson = """
        {"id":"1.16.5","mainClass":"net.minecraft.client.main.Main","assets":"1.16",
         "javaVersion":{"majorVersion":8},
         "arguments":{"jvm":["-Djava.library.path=${'$'}{natives_directory}","-cp","${'$'}{classpath}"],
                      "game":["--username","${'$'}{auth_player_name}"]},
         "libraries":[
          {"name":"org.lwjgl:lwjgl:3.2.2","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2.jar","sha1":"x"}},
           "rules":[{"action":"allow","os":{"name":"linux"}},{"action":"disallow","os":{"name":"windows"}}]},
          {"name":"org.lwjgl:lwjgl:3.2.2:natives-linux","downloads":{"classifiers":{"natives-linux":{"path":"org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2-natives-linux.jar","sha1":"y"}}},"natives":{"linux":"natives-linux"}},
          {"name":"com.sun:winonly:1.0","downloads":{"artifact":{"path":"com/sun/winonly/1.0/winonly-1.0.jar"}},"rules":[{"action":"allow","os":{"name":"windows"}}]}
         ]}
    """
    val meta = VersionMeta.parse(metaJson)
    eq("解析出 mainClass", "net.minecraft.client.main.Main", meta.mainClass)
    eq("解析出 java 大版本", 8, meta.javaMajor)
    eq("库声明数量", 3, meta.libraries.size)
    check("windows 专属库被 rules 排除", !Platform.allows(meta.libraries[2].rules))
    val ph = ClasspathBuilder.placeholders("CP", "/n", "/g", "/a", "1.16", "1.16.5")
    val plan = ClasspathBuilder.build(meta, "/libs", "/g/client.jar", ph)
    check("classpath 含 lwjgl", plan.classpath.contains("lwjgl-3.2.2.jar"))
    check("classpath 含 client.jar", plan.classpath.endsWith("client.jar"))
    check("windows 库不进 classpath", !plan.classpath.contains("winonly"))
    eq("natives 只收 1 个", 1, plan.nativeJars.size)
    check("natives 路径正确", plan.nativeJars[0].endsWith("lwjgl-3.2.2-natives-linux.jar"))
    eq("无未替换占位符", 0, plan.unresolved.size)
    check("natives_directory 已替换", plan.jvmArgs.any { it == "-Djava.library.path=/n" })
    check("玩家名已替换", plan.gameArgs.contains("PCLA"))
    check("启动前检查无问题", ClasspathBuilder.problems(plan).isEmpty(), ClasspathBuilder.problems(plan).joinToString())

    println("\n========================================")
    println("通过 $passed 项，失败 $failed 项")
    if (failed > 0) kotlin.system.exitProcess(1)
}
