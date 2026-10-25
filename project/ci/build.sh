#!/usr/bin/env bash
# 一条命令跑完「自检 + 打 APK」。
# 用途：Gitee 不支持 GitHub Actions，所以在 Termux 里或任何有 JDK 的机器上直接跑它。
# 依赖：java 17+、kotlinc（自检用）、Android SDK + gradle（打 APK 用）。
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> 1/3 core 自检"
mkdir -p build/selfcheck
kotlinc core/src/main/kotlin -include-runtime -d build/selfcheck/core.jar
java -Dfile.encoding=UTF-8 -jar build/selfcheck/core.jar device_profiles.json

echo "==> 2/3 同步档位表"
mkdir -p app/src/main/assets
cp device_profiles.json app/src/main/assets/device_profiles.json

echo "==> 3/3 构建 debug APK"
if command -v gradle >/dev/null 2>&1; then
  gradle :app:assembleDebug --no-daemon
  echo "产物：app/build/outputs/apk/debug/"
else
  echo "没有 gradle，跳过 APK（只跑了自检）。"
fi
