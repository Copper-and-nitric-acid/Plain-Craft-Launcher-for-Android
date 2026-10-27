# garbage —— 暂时用不到，但**不能删**的东西

这里只放「现在构建用不上、以后可能要」的原始资源。**不要删除**，需要时 `git mv` 回原位即可。

| 目录 | 是什么 | 什么时候会用上 |
|---|---|---|
| `lwjgl/` | FCL 的 LWJGL 原始包：3.3.3 与 3.4.1 全套 jar + arm64 natives | 换 LWJGL 版本（1.21+ 可能要 3.4.1），或补 openal/vulkan 等模块 |
| `armeabi-v7a/` | FCL 的 32 位原生库 38 个（launcher 层，**不含 JVM**） | 做 32 位向下兼容时；还缺 32 位 JRE |
| `caciocavallo/` | AWT 的 1.10 变体（cacio-shared + cacio-androidnw + ResConfHack） | 现用版本已复制进 `project/app/src/main/assets/caciocavallo/`，这份是原件 |
| `caciocavallo17/` | AWT 的 1.17 变体（tta/agent） | 换 Java 17 的 AWT 实现或对比问题时 |
| `device_profiles.root-old.json` | 早期的档位表副本（旧版） | 权威副本是 `project/device_profiles.json`，这份只作历史对照 |

装进 APK 的永远只有 `project/app/src/main/assets/` 与 `project/app/src/main/jniLibs/` 里挑出来的子集；
根目录这些原件不参与构建。
