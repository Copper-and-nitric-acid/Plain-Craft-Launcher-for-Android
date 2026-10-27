# 验证台账（只写真跑过的命令与结果）

> 规则：本文档只记录「本机真跑过」的东西。
> 没有 Android SDK，所以 **APK 从未编译过**；任何「能装、能用」的说法都不成立。
> 每条都要标：已验证 / 未验证。

## 工具链（本机临时装到 /tmp，不进仓库）

| 组件 | 版本 | 来源 |
|---|---|---|
| OpenJDK | 17.0.2+8-86 | download.java.net（adoptium 只有 30KB/s，不可用） |
| kotlinc | 2.0.21 (JRE 17) | gh-proxy 代理 GitHub Release |
| okhttp / okio jar | 4.12.0 / 3.6.0 | Maven Central，仅用于本机编译校验 |

## 已验证

### 1. core 纯逻辑（33 项自检）
```bash
export JAVA_HOME=/tmp/jdk-17.0.2 PATH=/tmp/jdk-17.0.2/bin:$PATH
/tmp/kc/kotlinc/bin/kotlinc core/src/main/kotlin -include-runtime -d /tmp/build/core.jar
java -Dfile.encoding=UTF-8 -jar /tmp/build/core.jar device_profiles.json
# → 通过 33 项，失败 0 项
```

### 2. download 模块（49 项自检 + 真实下载）
```bash
/tmp/kc/kotlinc/bin/kotlinc download/src/main/kotlin \
  -cp "/tmp/okhttp/okhttp-4.12.0.jar:/tmp/okhttp/okio-jvm-3.6.0.jar" \
  -d /tmp/build/dl.jar
# dl.jar 64,667 字节，sha256 dd4226d0…

java -cp "/tmp/build/dl.jar:/tmp/okhttp/okhttp-4.12.0.jar:/tmp/okhttp/okio-jvm-3.6.0.jar:/tmp/kc/kotlinc/lib/kotlin-stdlib.jar" \
  io.pcla.core.download.DownloadSelfTestKt
# → 通过 49 项，失败 0 项
```
真实下载（1.20.1 client.jar，23,028,853 字节，SHA-1 三次全对）：
| 并发 | 耗时 | 吞吐 |
|---|---|---|
| 1 | 27.14s | 0.81 MB/s |
| 4 | 17.65s | 1.24 MB/s |
| 6 | 20.70s | 1.06 MB/s（触发 429，退避 32s） |

### 3. app 模块的语法级检查（不是编译）
```bash
/tmp/kc/kotlinc/bin/kotlinc core/src/main/kotlin app/src/main/kotlin -d /tmp/build/check.jar
# 语法错误 0；自定义符号 unresolved 0；剩余 717 条全部是缺 Android SDK 的符号
```

## 未验证（不要对用户说「已通过」）

- [ ] **APK 构建**：没有 Android SDK / gradle，`assembleDebug` 一次都没跑过
- [ ] **Compose UI 真机渲染**：只做过符号解析，没渲染过一帧
- [ ] **git wrapper 实跑**：`gradlew` 在 /sdcard 是 noexec，只校验过字节与 zip 结构
- [ ] **接入 FCL 后能否启动游戏**：连 FCL 原版都没编出来
- [ ] **okhttp 在 Android 上的行为**：本机是 JVM 17 跑通，Android 还有 R8/desugaring/证书差异

## JRE 运行时（2026-10-27 复查）

- 仓库里每套 Java 是**两层**：`universal.tar.xz`（rt.jar.pack、lib/security、字体等）
  + `bin-arm64.tar.xz`（java 可执行与 libjli/libjvm）。之前只留了 bin 那层，
  解出来缺 `lib/` 里的大半内容和 `lib/aarch64/server/libjvm.so`，**必然起不来**。
- 已从 git 历史恢复四套 `universal.tar.xz`（jre8 9.5MB / jre17 16.3MB / jre21 18.1MB / jre25 31.3MB）
  与 `version` 文件；`RuntimePack` 改为按 universal → bin 顺序叠解。
- 已验证：jre8 的 universal 85 条目 + bin 49 条目，`bin/java`、`lib/aarch64/server/libjvm.so`、`release` 都在，
  xz 魔数正确。
- **未验证**：在 Android 应用进程里真跑起来。本容器是 Ubuntu 用户态，缺 `/linkerconfig/ld.config.txt`，
  执行 Android 版 `bin/java` 会报 `CANNOT LINK EXECUTABLE: library "libjli.so" not found`
  ——这是环境差异，不是包的问题。真机能否起来只能靠 APK 实测。
- Java 8 是 `rt.jar.pack` 打包格式，运行时要 `unpack200`（`libunpack200.so` 已在 jniLibs 里）。
