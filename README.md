# PCLA — Plain Craft Launcher for Android

第三方 Android 修改版，与龙腾猫跃及 PCL 官方无关。

- [中文说明 / 中文介绍](README_CN.md)
- [English introduction](README_EN.md)

## 仓库结构

```
project/            可构建的 Android 工程（Gradle）
  app/              界面 + 下载/解压/启动的胶水层（Kotlin + 少量 C）
  core/             与 Android 无关的纯逻辑（版本 json、classpath、自检 115 项）
  download/         多线程下载（断点续传 + SHA1 校验）
  docs/             设计记录（ROADMAP、LEGACY、legacy-fork 里的早期原型）
  tools/            开发期用的脚本与实测数据（DeviceProbe、GC 日志分析、fps.csv）
.github/            GitHub Actions：跑自检 + 出 Debug APK
```

## 原始二进制（只增不删）

根目录这几组是 FCL 的原始资源，留着供以后换 LWJGL 版本 / 补其他 ABI / 试 AWT 变体，
**不要删**：

- `lwjgl/`：3.3.3 与 3.4.1 两套（1.21+ 可能需要 3.4.1）
- `armeabi-v7a/`：v7a 的原生库（现在只做 arm64，以后也许要）
- `caciocavallo/`、`caciocavallo17/`：AWT 的 1.10 与 1.17 变体
- `device_profiles.json`：根目录这份是早期的旧副本，权威副本见 `project/`

装进 APK 的只有 `project/app/src/main/assets/` 与 `jniLibs/` 里挑出来的子集。
