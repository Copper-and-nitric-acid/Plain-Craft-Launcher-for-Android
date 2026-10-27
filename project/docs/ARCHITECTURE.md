# 启动链与唯一的缺口

这份文档只写一件长久的事：**从点「启动」到画面出现，中间有哪些环节，现在缺哪一个。**
别的文档写功能（README）和验证边界（VERIFY.md），这里写结构。

## 一、现在的链路（已打通）

```
选版本 → 版本清单(v2) → 版本 json
  → GamePlan(NativeExtractor) 拆出 libraries / assetIndex / client.jar
  → 并发下载 + sha1 校验（BMCLAPI 镜像）          2664 文件 / 366 MB（1.16.5 实测）
  → RuntimePack：universal.tar.xz + bin-arm64.tar.xz 两层解包
  → ClasspathBuilder：清单库 + 随包 LWJGL 3.3.3 + Cacio
  → LaunchCommand：bin/java -cp ... <mainClass> --username ...
  → ProcessBuilder.exec（LD_LIBRARY_PATH / LIBGL_* / HOME / TMPDIR 都设了）
```

关键替换（不是可选项，是 arm64 手机能不能跑的前提）：

| 项 | 事实 | 处理 |
|---|---|---|
| 1.16.5 的原生库 | `natives-linux` 是 x86_64 | 换随包 `lwjgl/3.3.3` 的 arm64 原生库 |
| `org.lwjgl.glfw` | 官方 3.2.2 jar 里没有这个包 | 用 `lwjgl-3.3.3-merged-modules.jar`，且排在 classpath 最前 |
| 官方 3.3.3 arm64 原生 | Maven 上加不进来（3.2.2 直接 404） | 随包放在 `assets/lwjgl/3.3.3/`，解到外部目录再拼进 `java.library.path` |
| 开窗 | Android 没有 X11 | Cacio 的 `CTCToolkit` + `-Djava.awt.headless=false` |

## 二、缺口：Surface 从 App 进程交给游戏进程

`glfwCreateWindow` 在 arm64 上不是原生 GLFW 实现的，是 LWJGLX 转给 AWT，
AWT 用 Cacio 落到 `libawt_xawt.so`，最后由 `libpojavexec.so` 把 Android 的
`ANativeWindow` 绑上去。这一段需要 **Java 侧先拿到一个 Surface**。

`libpojavexec.so` 的导出符号（本仓库里 `app/src/main/jniLibs/arm64-v8a/libpojavexec.so`
的 `.dynstr`，共 278 个名字）证实它一半是 Pojav、一半是 FCL：

```
Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow        ← Pojav 的入口
Java_net_kdt_pojavlaunch_utils_JREUtils_releaseBridgeWindow ← Pojav 的入口
Java_com_oracle_dalvik_VMLauncher_launchJVM                 ← Pojav 的同进程启动器
Java_com_tungsten_fclauncher_bridge_FCLBridge_setupBridgeSurfaceAWT  ← FCL 的入口
Java_android_view_Surface_nativeGetBridgeSurfaceAWT         ← FCL 的入口

Java_com_tungsten_fclauncher_bridge_FCLBridge_executeBinary / dlopen / chdir
Java_com_tungsten_fclauncher_bridge_FCLBridge_initializeHooks / setLdLibraryPath
```

对照随包 jar：

- `CallbackBridge.class` 在 `lwjgl-3.3.3-merged-modules.jar` 里，**没有** `setupBridgeWindow`
  （逐字节查过 class 常量池），所以 FCL 走的是 `FCLBridge_setupBridgeSurfaceAWT`；
- 但 `FCLBridge` 与 `Surface.nativeGetBridgeSurfaceAWT` 的 Java 类只在 FCL 的 App 代码里，
  而 FCL 的公开仓库从某个版本起不再放 Java 源码（`1.3.4.2` 标签下 0 个 .java，只有 libs）。

于是产生一个硬约束：**Surface 属于 App 进程，而 `bin/java` 是另一个进程。**
Android 的 `Surface` 不能跨进程传对象，只能传 `ANativeWindow*`（同进程内的裸指针语义）。

## 三、两条可走的路

### B（同进程起 JVM，已有第一版）

Pojav 的 `jre_launcher.c`（GPLv2+Classpath，公开仓库可读）证实：
`Java_com_oracle_dalvik_VMLauncher_launchJVM` 只是把字符串数组转成 `argc/argv`，
然后 `dlopen("libjli.so")` → `dlsym("JLI_Launch")` → 当命令行用。
推论有两条，都已写进 `InProcessLauncher`：
1. `argv[0]` 必须是 `bin/java` 的绝对路径（JLI 靠它推 `java.home`），不能丢；
2. App 进程的 `LD_LIBRARY_PATH` 改不了，所以先用绝对路径 `System.load` 预载
   `libjli/libjvm/libjava/libjimage/libverify/libzip`，之后按 soname 的 `dlopen` 才命中。

### B'（要写原生代码的版本）

`Java_com_oracle_dalvik_VMLauncher_launchJVM` 这个符号在包里，正是 Pojav 的做法：
不 exec 外部 `java`，而是在 App 进程内 `dlopen libjvm.so` 起 JVM。
同进程 → Surface 拿得到 → `CallbackBridge.setupBridgeWindow(surface)` 直接可用。

代价：
- 需要 JNI 胶水（NDK / CMake，GitHub Actions 的 Android 环境自带 NDK，可在 CI 编）；
- JVM 在 UI 线程外跑，崩溃会带走整个 App；
- `CallbackBridge` 需要自备一份（Pojav 源码是 GPL，可参考实现），因为随包 jar 里那份缺方法。

### A（快，但有代价）：从 FCL 官方 APK 抠类

把 FCL APK 里的 `com.tungsten.fclauncher.bridge.*` 与 GLSurface 相关类解出来，
放进 `classes.dex` 之外再 `dx` 进我们的包。技术上一天能通，
但那是别人的闭源产物，**必须得到用户明确同意**，且分发有风险。

## 四、现在的判断

今晚（2026-10-27 20:00）之前做到的是第一节整条链路；第二节的缺口在 B 被实现之前不会消失。
`LaunchScreen` 的「检查」十项与「JRE 自检」就是为了在真机上把问题切到最小范围：
- 自检 ✗ → 第一节的问题（JRE 解包、可执行位、架构），能当场修；
- 自检 ✓ 而启动报窗口错 → 第二节，只能走 A 或 B。
