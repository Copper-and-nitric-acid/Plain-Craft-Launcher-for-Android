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

原生库与 JRE 都放在 `project/app/src/main/assets/` 与 `project/app/src/main/jniLibs/`。
FCL 的原始二进制包（`lwjgl/`、`armeabi-v7a/` 等）已经从工作区移除以免仓库过重，
需要时从 git 历史 `fe04459` 取回。
