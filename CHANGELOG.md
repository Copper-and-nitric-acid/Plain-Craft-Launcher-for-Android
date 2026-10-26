# 更新日志 / Changelog

本文件双语并列：每条先中文，再英文。
All entries are bilingual: Chinese first, then English.

版本号对应 `project/app/build.gradle.kts` 的 `versionName`。

---

## [0.2.0-alpha] — 2026-10-26

### 新增 / Added

- **多文件下载队列**：一次贴多行地址，并发默认 2、上限 8；单条链路切碎不会更快（实测单连接 1.30 MB/s、4 分片 1.24 MB/s、8 分片 1.06 MB/s 且开始吃 429），所以并发对象是「多文件」。
  **Multi-file download queue**: paste several URLs at once, 2 concurrent by default (max 8). Splitting one stream is *not* faster here (measured: 1.30 MB/s single, 1.24 MB/s at 4 segments, 1.06 MB/s at 8 and hitting 429), so concurrency is applied across files.

- **换源链**：主源失败或 SHA-1 对不上时自动换下一个源，失败次数多的源排到后面。
  **Mirror chain**: falls back to the next mirror on failure or hash mismatch; flaky mirrors sink to the bottom.

- **429 自适应并发**：撞到限流就把并发减半，连续 8 个文件成功再 +1，避免在阈值附近抖动。
  **Adaptive concurrency**: halved on 429/503, +1 after 8 consecutive successes, so it does not oscillate around the limit.

- **下载页支持队列**：多行输入（`URL` 或 `URL SHA-1`）、总进度、瞬时速度、逐任务状态。
  **Queue UI**: multi-line input (`URL` or `URL SHA-1`), overall progress, live speed, per-task status.

### 修复 / Fixed

- `:app` 拿不到 `okhttp` 类型（`implementation` → `api`，并补 `HttpDownloader.create` 工厂），CI 的 `compileDebugKotlin` 因此失败过。
  `:app` could not see `okhttp` types (`implementation` → `api`, plus a `HttpDownloader.create` factory); this is what broke CI's `compileDebugKotlin`.

- 默认堆会开到 3686 MB：渲染距离不再参与堆计算，默认堆固定为机型实测基线 2048 MB，只在有运行时证据（young GC 回收率 < 10%）时才由降级阶梯抬一档。
  Default heap used to reach 3686 MB. Render distance no longer affects heap; the default is now the measured 2048 MB baseline, and only runtime evidence (young-GC reclaim < 10%) may raise it, one step at a time.

### 工程 / Tooling

- GitHub Actions 两个 job：`纯逻辑自检` 与 `打 Debug APK`，产物 `PCLA-debug`。
  Two CI jobs: `纯逻辑自检` (pure-logic self-test) and `打 Debug APK`; artifact `PCLA-debug`.

---

## [0.1.0-alpha] — 2026-10-25

### 新增 / Added

- **设备探测**：EGL14 离屏上下文读 `GL_RENDERER` / `GL_VERSION`，不打扰界面。
  **Device probe**: reads `GL_RENDERER` / `GL_VERSION` via an off-screen EGL14 context, without touching the UI.
- **档位决策**：按 SoC / GPU / 内存给出默认堆、渲染距离与版本推荐（8 GB 天玑 700：堆 2048 MB、RD 4、首选 1.16.5）。
  **Tuning**: derives heap, render distance and version ranking from SoC / GPU / RAM (8 GB Dimensity 700: heap 2048 MB, RD 4, 1.16.5 first).
- **GC 日志分析**：解析 `-Xlog:gc`，输出 STW 次数、p50/p95/p99、停顿占比与簇。
  **GC log analysis**: parses `-Xlog:gc` into STW count, p50/p95/p99, pause share and clusters.
- **降级阶梯**：掉帧时先降渲染距离，缩堆只在内存吃紧时发生。
  **Degrade ladder**: lowers render distance first; heap is shrunk only under memory pressure.
- **PCL 风格 UI**：蓝色主题、标题栏胶囊页签、圆角 3/6，照桌面版 XAML 的尺寸还原。
  **PCL-style UI**: blue theme, capsule tabs, 3/6 dp radii, sizes taken from the desktop XAML.
- **下载模块 v0.1**：< 10 MB 单连接、10–60 MB 4 分片、> 60 MB 动态加片，线程数可配置并夹取 1–16。
  **Download module v0.1**: single connection below 10 MB, 4 segments for 10–60 MB, dynamic above 60 MB; thread count configurable and clamped to 1–16.
- **网络诊断**：拦内网 IP（10/172.16–31/192.168）与 169.254 链路本地地址，并给出人话解释。
  **Network guard**: flags private ranges (10/172.16–31/192.168) and link-local 169.254 with a human explanation.
- **SHA-1 校验**：不匹配时保留坏文件、暂停，并提供删除 / 查看真实 URL / 强行继续三个选项。
  **SHA-1 verification**: on mismatch the bad file is kept and the transfer stops, offering delete / show real URL / force continue.

### 修复 / Fixed

- 自检从 24 项扩到 33 项：并发排序、`p95` 计算、并发标记周期不得计入 STW 等。
  Self-test grew from 24 to 33 checks: ranking, `p95`, and the rule that concurrent-mark cycles must not count as STW.

---

## [0.0.1] — 2026-10-25

- 仓库建立，AGPL-3.0 协议，GitHub Actions 首次构建。
  Repository created under AGPL-3.0; first GitHub Actions build.
