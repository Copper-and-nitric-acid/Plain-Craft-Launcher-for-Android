# 把 :pcla-core 挂进 FCL fork

目标：FCL 仍然是 FCL，只是在启动前多问一句「这台手机该怎么配」。所有决策逻辑在
`pcla-core` 里，FCL 的代码只改 3 处、共十几行。

---

## 0. 先证明原版能编译（不要跳过）

在**没有做任何修改**的 FCL 上先跑一次，确认工具链没问题：

```bash
git clone --depth 1 https://github.com/<你的账号>/FoldCraftLauncher.git
cd FoldCraftLauncher
chmod +x gradlew
./gradlew assemblefordebug -Darch=arm64
```

要求：

| 项 | 值 | 说明 |
|---|---|---|
| JDK | 17（temurin） | FCL 官方 CI 就用这个 |
| Gradle | 8.14.4 | 仓库自带 wrapper，不用装 |
| NDK | **27.0.12077973** | `FCL/build.gradle.kts` 里写死，编 `FCL/src/main/jni` 要用 |
| 签名 | 不需要配置 | debug 走仓库自带的 `debug-key.jks`，口令硬编码 `FCL-Debug` |

产物：`FCL/build/outputs/apk/debug/*.apk`。**这一步不过，后面全部免谈。**

---

## 1. 放入模块

```bash
cp -r pcla-core ~/FoldCraftLauncher/
```

目录长这样：

```
FoldCraftLauncher/
├── FCL/                        ← 原样
├── LWJGL/  Terracotta/  ZipFileSystem/
├── pcla-core/                  ← 新增
│   ├── build.gradle.kts
│   └── src/main/
│       ├── kotlin/io/pcla/core/...   （含 fcl/PclaBridge.kt）
│       └── resources/device_profiles.json
└── settings.gradle.kts
```

## 2. 注册模块

`settings.gradle.kts`，在已有 `include(...)` 后面加一行：

```kotlin
include(":pcla-core")
```

## 3. 让 FCL 依赖它

`FCL/build.gradle.kts` 的 `dependencies { }` 块里加：

```kotlin
implementation(project(":pcla-core"))
```

## 4. 唯一的接入点

```kotlin
// 位置随你，建议放在真正构建 JVM 命令行之前（FCLGameLauncher 被 new 出来的附近）
val profilesJson = PclaBridge::class.java
    .getResourceAsStream("/device_profiles.json")!!
    .bufferedReader().readText()

val decision = PclaBridge.evaluate(profilesJson, PclaBridge.Input(
    mcVersion     = version.id,                 // 例如 "1.18.2-forge-40.2.1"
    socKey        = "MT6833V|MT6833|PHJ110",    // 有 Build.SOC_MODEL 就拼进去
    deviceModel   = Build.MODEL,
    glRenderer    = glRenderer,                 // EGL14 读 GL_RENDERER
    glVersion     = glVersion,
    ramMb         = totalRamMb,
    renderDistance = 4,                         // 实例当前值
    modCount      = mods.size,
    loader        = "FORGE",
))

// a) JVM 参数：写进实例设置（FCL 的 VersionSetting.javaArgs，见 VersionSetting.kt:98）
versionSetting.javaArgs = decision.jvmArgsLine()

// b) 渲染距离：写进实例 run 目录的 options.txt（`renderDistance:<n>` 那一行）
//    注意 FCL 通过 GameOption 读写这个文件，直接改文本后需要重新进入实例才生效。

// c) 需要用户确认的项（BLOCK 级）：用 FCL 现成的 Dialog 弹一下再继续
if (decision.blocked) { /* showDialog(decision.diagnostics) */ }
```

`PclaBridge.Result` 里还有 `profileId`、`recommendedVersion`、`heapMb`，够在「关于」页
显示「本机按 MT6833V 档位调优」。

---

## 5. 许可与署名（必须做，否则等于违约）

FCL 是 **GPL-3.0**，fork 后整个 APK 就是 GPL-3.0 的衍生物：

- 保留所有文件的 GPL 版权头，新增文件（`pcla-core`）也加 GPL-3.0 头
- README 写明：Android 启动内核来自 FCL / PojavLauncher / HMCL，并给出仓库地址
- 关于页：按 PCL 的《分发有限许可》要求，**第一位**给龙腾猫跃署名 + 其赞助链接
- 名称需以 `Plain Craft Launcher (PCL)` 开头并带第三方后缀（例如 `PCLA — PCL 第三方 Android 修改版`）

## 6. 常见坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `Permission denied ./gradlew` | 可执行位没传上去 | `chmod +x gradlew && git update-index --chmod=+x gradlew` |
| CMake 找不到 NDK | 缺 NDK 27.0.12077973 | SDK Manager 里装这个精确版本 |
| 编译 20 分钟还没完 | 编了全部 5 个架构 | `-Darch=arm64` |
| APK 装不上 | 与已装的 FCL 签名冲突 | 先卸载原版 FCL |
| `javaArgs` 改了没生效 | 实例用的是「全局设置」 | 实例设置里关掉「使用全局设置」 |
