# 怎么验证这份代码是真的能跑

这里的命令都是实际执行过的，不是"应该可以"。

## PC 上装工具链

```bash
mkdir -p /tmp/jdk-17.0.2 /tmp/kc && cd /tmp
# JDK 17（arm64；x64 换 ..._x64_bin.tar.gz）
curl -L -o jdk.tar.gz https://download.java.net/java/GA/jdk17.0.2/dfd4a8d0985749f896bed50d7138ee7f/8/GPL/openjdk-17.0.2_linux-aarch64_bin.tar.gz
tar xzf jdk.tar.gz -C /tmp/jdk-17.0.2 --strip-components=1

# Kotlin 2.0.21
curl -L -o kotlin.zip https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip
python3 -c "import zipfile;zipfile.ZipFile('kotlin.zip').extractall('/tmp/kc')"
chmod +x /tmp/kc/kotlinc/bin/*
```

## 编译 core 并跑自检

```bash
cd <repo>/core
export JAVA_HOME=/tmp/jdk-17.0.2 PATH=/tmp/jdk-17.0.2/bin:$PATH
/tmp/kc/kotlinc/bin/kotlinc src/main/kotlin -include-runtime -d /tmp/build/core.jar
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar /tmp/build/core.jar device_profiles.json
```

期望结尾：`通过 32 项，失败 0 项`。

`-Dfile.encoding` 一定要加，否则中文输出在 Android/Termux 上会乱码。

## 分析 GC 日志

```bash
java -Dfile.encoding=UTF-8 -jar core.jar gc /path/to/gc.log
```

## 用实测数据问"下一步该动什么"

```bash
# 版本 RD 帧率 采样秒 堆MB GC占用% 回收率% 可用内存MB
java -Dfile.encoding=UTF-8 -jar core.jar degrade device_profiles.json 1.16.5 17 18 60 2048 0.4
```

## 编译 APK

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle :app:assembleDebug
```

仓库里**没有 gradle wrapper**（手机上手写二进制 jar 不现实），所以用系统装的 gradle，
或 CI 里的 `gradle/actions/setup-gradle`。别跑 `./gradlew`，会提示文件不存在。

## 云端构建（GitHub Actions）

仓库里源码在 `project/` 子目录，workflow 用 `defaults.run.working-directory: project`。
仓库里带标准 gradle wrapper（`gradlew` / `gradlew.bat` / `gradle/wrapper/*`），
CI 直接 `./gradlew :app:assembleDebug`，与本地构建完全一致；wrapper 首次运行会自动下载 gradle 8.9。

- 触发：push 到 master/main，或 Actions 页面手动 Run workflow。
- 第一个 job `core-check` 不碰 Android SDK，几十秒内告诉你自检有没有过。
- 第二个 job `apk` 出产物，到那次运行的页面底部 Artifacts 下载 `pcla-debug-apk`，
  解压得到 `app-debug.apk`，手机上直接安装（debug 包会自动签名）。
