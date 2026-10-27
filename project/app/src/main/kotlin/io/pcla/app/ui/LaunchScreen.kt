package io.pcla.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pcla.app.GameInstaller
import io.pcla.app.InProcessLauncher
import io.pcla.app.RuntimePack
import io.pcla.core.launch.AndroidRuntime
import io.pcla.core.launch.JavaPolicy
import io.pcla.core.launch.JavaProbe
import io.pcla.core.launch.Preflight
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 启动页：装（下载+解压）→ 启动（exec java）→ 日志。
 * 只做一件事：把「点了启动到底发生了什么」原样打出来。
 */
@Composable
internal fun LaunchScreen(heapMb: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf("1.16.5") }
    var stage by remember { mutableStateOf("未开始") }
    var log by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf<GameInstaller.Installed?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun append(line: String) {
        log = if (log.length > 20000) log.takeLast(12000) + "\n" + line else log + "\n" + line
    }

    PclCard("启动") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PclField("版本", version)
            PclField("堆", "${heapMb}MB（来自档位推荐）")
            PclField("状态", stage)
            PclButton(
                text = if (busy) "进行中…" else "① 下载并解压",
                enabled = !busy,
                highlight = true,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                GameInstaller.install(context, version, heapMb) { p ->
                                    stage = buildString {
                                        append(p.stage)
                                        if (p.total > 0) append(" ${p.done}/${p.total}")
                                        if (p.bytes > 0) append(" ${p.bytes / 1048576}MB")
                                        if (p.message.isNotEmpty()) append(" · ${p.message}")
                                    }
                                }
                            }
                        }.onSuccess { installed = it; stage = "就绪，可以启动" }
                            .onFailure { append("安装失败：" + (it.message ?: it.javaClass.simpleName)) }
                        busy = false
                    }
                },
            )
            PclButton(
                text = "② 启动",
                enabled = !busy && installed != null,
                onClick = {
                    val ready = installed ?: return@PclButton
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                GameInstaller.launch(context, ready, heapMb) { line -> append(line) }
                            }
                        }.onFailure { append("启动失败：" + (it.message ?: it.javaClass.simpleName)) }
                        busy = false
                    }
                },
            )
            // 不需要 366MB 的游戏文件，也能先确认「包里的 JRE 到底能不能跑」
            PclButton(
                text = "JRE 自检",
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val major = JavaPolicy.requiredMajor(version)
                                append("--- JRE 自检（$version 需要 Java $major）---")
                                val runtime = RuntimePack.ensure(context, major) { mb ->
                                    stage = "解包 JRE $major · ${mb / 1048576}MB"
                                }
                                append("java: " + runtime.javaBin.absolutePath)
                                append("存在=" + runtime.javaBin.exists() +
                                    " 可执行=" + runtime.javaBin.canExecute() +
                                    " 大小=" + runtime.javaBin.length())
                                val argv = listOf(runtime.javaBin.absolutePath) + JavaProbe.ARGS
                                val proc = ProcessBuilder(argv)
                                    .redirectErrorStream(true)
                                    .apply {
                                        environment()["LD_LIBRARY_PATH"] = RuntimePack.libraryPath(runtime)
                                        environment()["HOME"] = runtime.home.absolutePath
                                    }
                                    .start()
                                val text = proc.inputStream.bufferedReader().readText()
                                val done = proc.waitFor()
                                append("退出码 $done")
                                append(text.trim().ifEmpty { "（没有任何输出）" })
                                val out = JavaProbe.parse(text)
                                append(JavaProbe.render(out, major))
                                JavaProbe.problems(major, out).forEach { append("✗ " + it) }
                            }
                        }.onFailure { append("自检失败：" + (it.message ?: it.javaClass.simpleName)) }
                        busy = false
                    }
                },
            )
            // 实验路径：同进程起 JVM。崩会带走整个 App，所以单独一个按钮，不混进正常流程
            PclButton(
                text = "实验·同进程启动",
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                append("--- 同进程启动（实验）---")
                                append(InProcessLauncher.loadNative(context).getOrElse {
                                    "载入失败：" + (it.message ?: it.javaClass.simpleName)
                                })
                                val ready = installed
                                if (ready == null) {
                                    append("先点①装完版本再来")
                                } else {
                                    val code = InProcessLauncher.launch(context, ready, heapMb) { line -> append(line) }
                                    append("launchJVM 返回 $code")
                                }
                            }
                        }.onFailure { append("同进程启动失败：" + (it.message ?: it.javaClass.simpleName)) }
                        busy = false
                    }
                },
            )
            PclButton(
                text = "检查",
                enabled = !busy && installed != null,
                onClick = {
                    val ready = installed ?: return@PclButton
                    val root = ready.root
                    val nativeDir = GameInstaller.nativesPath(context, root)
                    val nativeFiles = nativeDir.split(":").flatMap { d ->
                        File(d).listFiles()?.map { it.name } ?: emptyList()
                    }
                    val checks = Preflight.run(
                        plan = ready.plan,
                        jarNames = AndroidRuntime.JARS,
                        nativePaths = nativeDir.split(":"),
                        nativeFiles = nativeFiles,
                        expectedMajor = JavaPolicy.requiredMajor(version),
                        libraryPath = nativeDir,
                    )
                    append("--- 启动前检查 ---")
                    append(Preflight.render(checks))
                    append("nativeLibraryDir 里 ${File(context.applicationInfo.nativeLibraryDir).listFiles()?.size ?: 0} 个文件")
                    append("assets 里 merged-modules：" + runCatching {
                        context.assets.open("lwjgl/3.3.3/lwjgl-3.3.3-merged-modules.jar").use { "在，${it.available()} 字节" }
                    }.getOrElse { "打不开：" + (it.message ?: "") })
                },
            )
            Text(
                text = log.ifEmpty { "日志会出现在这里" },
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}
