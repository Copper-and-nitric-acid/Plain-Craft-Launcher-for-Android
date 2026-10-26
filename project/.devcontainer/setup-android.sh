#!/usr/bin/env bash
# Codespaces 里一次性安装 Android 命令行工具链。
# 只装编译 APK 必需的部分：platform-tools / platforms;android-34 / build-tools;34.0.0
set -euo pipefail

SDK=/usr/local/android-sdk
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

if [ -x "$SDK/platform-tools/adb" ] && [ -d "$SDK/platforms/android-34" ]; then
  echo "[setup] Android SDK 已就绪，跳过"
  exit 0
fi

echo "[setup] 安装 Android 命令行工具到 $SDK"
sudo mkdir -p "$SDK/cmdline-tools"
curl -fsSL "$CMDLINE_URL" -o /tmp/cmdline-tools.zip
sudo apt-get update -qq && sudo apt-get install -y -qq unzip >/dev/null
sudo unzip -q -o /tmp/cmdline-tools.zip -d /tmp/cmdline-tools
sudo mv /tmp/cmdline-tools/cmdline-tools "$SDK/cmdline-tools/latest"
sudo chown -R "$(id -u):$(id -g)" "$SDK"

export ANDROID_HOME="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null

# 编 FCL fork 时才需要 NDK（约 1GB）。PCLA 本体是纯 Kotlin，不用装。
if [ "${PCLA_INSTALL_NDK:-0}" = "1" ]; then
  echo "[setup] 安装 NDK 27.0.12077973（FCL 的 jni 需要这个精确版本）"
  sdkmanager "ndk;27.0.12077973" >/dev/null
fi

echo "[setup] 完成："
sdkmanager --list_installed | sed -n '1,10p'
