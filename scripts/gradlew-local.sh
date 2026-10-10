#!/usr/bin/env bash
#  带本机工具链的 Gradle 入口。
#
#  用法：
#    ./scripts/gradlew-local.sh test        # 跑 JVM 单测
#    ./scripts/gradlew-local.sh assembleDebug
#    ./scripts/gradlew-local.sh :app:compileDebugKotlin
#
#  为什么需要它：JDK、Android SDK、Gradle 缓存都不在标准位置，
#  而且 C 盘只剩约 10 GB —— 依赖缓存（约 1–2 GB）必须放 D 盘。
#  这些都是**本机专属**的，所以走环境变量而不是写进 gradle.properties：
#  写进去会让别人和 Android Studio 的构建直接失败。
#
#  日常开发直接用 Android Studio 打开本目录即可，不需要这个脚本。
#  它是给「命令行验证」和 CI 用的。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

JAVA_DIR="$(ls -d "$ROOT"/.toolchain/jdk-* 2>/dev/null | head -1)"
if [ ! -d "$JAVA_DIR" ]; then
  echo "找不到便携 JDK。先跑 ./scripts/bootstrap-toolchain.sh" >&2
  exit 1
fi

export JAVA_HOME="$JAVA_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$ROOT/.android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# 关键：C 盘空间不够，缓存必须落 D 盘
export GRADLE_USER_HOME="$ROOT/.gradle-home"

GRADLE_BIN="$(ls -d "$ROOT"/.toolchain/gradle-*/bin/gradle 2>/dev/null | head -1)"
if [ -z "$GRADLE_BIN" ]; then
  echo "找不到便携 Gradle。先跑 ./scripts/bootstrap-toolchain.sh" >&2
  exit 1
fi

exec "$GRADLE_BIN" "$@"
