# :pcla-core

PCLA 的决策层，以**纯 Kotlin JVM 库**的形式挂进 FCL fork。

- 零 Android 依赖、零第三方依赖（JSON 是手写的 `MiniJson`）
- 可在 Termux 用裸 `kotlinc` 编译，也可在 CI 上跑 `SelfTest`（32 项断言）
- 对外只暴露一个入口：`io.pcla.core.fcl.PclaBridge`

## 内容

| 包 | 职责 |
|---|---|
| `json` | 极简 JSON 解析（不引第三方库，为了能在没有依赖管理的地方编译） |
| `device` | 设备规格、档位表匹配、实测数据打分 |
| `launch` | JVM 参数决策（`JvmTuner`）、版本推荐（`VersionPolicy`）、过载阶梯（`DegradeLadder`） |
| `bench` | JVM GC 日志解析与报告（`GcLogParser` / `GcReport`） |
| `fcl` | **接入点** `PclaBridge`：FCL 只需要调这一个对象 |

## 数据来源

`resources/device_profiles.json` 里的每个数字都来自真机实测（天玑 700 / OPPO A58x，
纯 Forge 无 OptiFine，JVM 2048MB）：

| 版本 | RD2 | RD17 | TNT 峰/谷 | 世界生成 |
|---|---|---|---|---|
| 1.12.2 | 200 | 60 | 150 / 18 | 20s |
| 1.16.5 | 144 | 22 | 111 / 87 | ~50s |
| 1.18 | 136 | 20 | 110 / 46 | 50s |
| 1.19.2 | 52 | 16 | 60 / 48 | 125s |
| 1.20.1 | 133 | 23 | 113 / 78 | 50s |

## 许可

GPL-3.0（与 FCL 一致——挂在一起分发就必然如此）。
