#!/usr/bin/env bash
# 在 Termux（手机，无电脑）上装齐 core 自检需要的工具链。
# 用完直接跑 ci/build.sh 即可。
set -euo pipefail

echo "==> 更新索引"
pkg update -y

echo "==> 安装 JDK 17 + Kotlin + git"
# Termux 官方仓库有 openjdk-17 和 kotlin；没有的话下面的报错会告诉你
pkg install -y openjdk-17 kotlin git curl unzip

echo "==> 版本"
java -version
kotlinc -version

echo
echo "OK。接着跑： ./ci/build.sh"
echo "注意：Termux 里没有 Android SDK，打包 APK 仍要走 GitHub Actions。"
