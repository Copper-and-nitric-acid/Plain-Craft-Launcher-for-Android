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

## LWJGL：清单里那套 x86_64 原生库不能用

1.16.5 的 libraries 里，原生库是 `org.lwjgl:lwjgl:3.2.2:natives-linux`，解出来是 x86_64 的
`.so`，arm64 手机加载必然失败。查了 Maven：`lwjgl-3.2.2-natives-linux-arm64.jar` 是 **404**，
3.3.3 才有这个 classifier，而且还是 glibc 版本，不是 Android 的。

所以 AndroidRuntime.kt 干三件事：

1. `strip(meta)` 把清单里所有 `org.lwjgl` 的库摘掉（1.16.5 实测少下 6 条、约 3 MB）；
2. `LwjglAssets.install()` 把 assets 里随包的 3.3.3 jar（8 个）与 arm64 的 .so（7 个）拷到
   `<root>/lwjgl/3.3.3/`，带大小缓存，重复进游戏不重拷；
3. classpath 里这 8 个 jar 排最前，`-Djava.library.path` 同时含解压出的 natives 与随包 natives。

没验证的：GLFW 那一层。清单被摘掉后 `org.lwjgl.glfw` 由随包 3.3.3 提供，但 3.3.3 的
`liblwjgl.so` 是否覆盖 1.16.5 用到的 GLFW 符号，只能在真机上跑才知道；如果启动时抛
`UnsatisfiedLinkError: org.lwjgl.glfw.GLFW.*`，就是这一层缺东西。

## 2026-10-27 晚：到哪一步了（截止 20:00）

能跑通的：

1. 拉 Mojang/BMCLAPI 清单，选 Java 大版本（1.16.5 → 8）；
2. 下载 1.16.5 的 2664 个文件（366 MB，4 路并发、sha1 跳过已存在）；
3. JRE 两层解包（universal + bin），`bin/java` 就位；
4. classpath 拼装：清单库 + 随包 LWJGL 3.3.3（merged-modules 在最前）+ Cacio 两个 jar；
5. `-Djava.library.path` 三段、`-Djava.home`、`LD_LIBRARY_PATH`、gl4es 三个环境变量；
6. 启动 `bin/java` 并把 stdout/stderr 逐行回传。

没跑通的，也是唯一的大缺口：**窗口**。

证据链：
- 1.16.5 的原生库是 `natives-linux`（x86_64），arm64 手机加载必失败 → 已换成随包 3.3.3；
- `org.lwjgl.glfw` 在官方 jar 里不存在，在 `lwjgl-3.3.3-merged-modules.jar` 里存在 → 已排最前；
- 该 GLFW 实现依赖 `libpojavexec.so` 的 `CallbackBridge` 四个 native 方法（已验证符号）；
- **但** `CallbackBridge.setupBridgeWindow` 在随包 jar 里不存在；`libpojavexec.so` 里导出的是
  `Java_org_lwjgl_glfw_CallbackBridge_setupBridgeWindow` 与
  `Java_com_tungsten_fclauncher_bridge_FCLBridge_setupBridgeSurfaceAWT`、
  `Java_android_view_Surface_nativeGetBridgeSurfaceAWT`；
- 也就是说 Surface 由 FCL 的 App 侧 Java 交给 native，而 FCL 公开仓库已不含 Java 源码
  （1.3.4.2 标签下 0 个 .java），这条桥今晚拿不到。

结论：起 JVM 之前的每一步都齐了；`glfwCreateWindow` 之后缺 Surface 交接。
