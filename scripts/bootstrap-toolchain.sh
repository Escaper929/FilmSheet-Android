#!/usr/bin/env bash
#  下载便携 JDK 17 + Kotlin 编译器 + JUnit，供 scripts/jvm-test.sh 使用。
#
#  为什么要自带工具链：本机的 gradle/Android Studio 环境未必就绪，而 core/
#  与 config/ 是纯 JVM 的，没有理由让最内层的算术验证被 SDK 安装阻塞。
#
#  已知的网络现实：这台机器上 GitHub 直连不通、代理又会 502，所以走
#  清华镜像（Adoptium）与 JetBrains 的 cache-redirector。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/.toolchain"
mkdir -p "$TC/lib"

NOPROXY=(--noproxy '*')   # 代理对这几个源都不可用，直连反而通

fetch() { # url dest label
  if [ -s "$2" ]; then echo "  已存在 $3"; return; fi
  echo "  下载 $3…"
  curl -sSL "${NOPROXY[@]}" -o "$2" "$1"
}

echo "▸ JDK 17"
fetch "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip" \
      "$TC/jdk17.zip" "Temurin 17"
[ -d "$TC"/jdk-* ] || unzip -q -o "$TC/jdk17.zip" -d "$TC"

echo "▸ Kotlin 编译器"
fetch "https://cache-redirector.jetbrains.com/github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip" \
      "$TC/kotlinc.zip" "kotlinc 2.0.21"
[ -d "$TC/kotlinc" ] || unzip -q -o "$TC/kotlinc.zip" -d "$TC"

echo "▸ JUnit"
fetch "https://maven.aliyun.com/repository/public/junit/junit/4.13.2/junit-4.13.2.jar" \
      "$TC/lib/junit-4.13.2.jar" "junit"
fetch "https://maven.aliyun.com/repository/public/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar" \
      "$TC/lib/hamcrest-core-1.3.jar" "hamcrest"

echo "▸ Gradle"
fetch "https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip" \
      "$TC/gradle.zip" "gradle 8.9"
[ -d "$TC/gradle-8.9" ] || unzip -q -o "$TC/gradle.zip" -d "$TC"

# ── Android SDK ────────────────────────────────────────────────────────────
# 装在项目内而不是 ~/Android/Sdk，因为本机 C 盘只剩约 10 GB，
# 而 SDK + Gradle 缓存加起来要 3–4 GB。
SDK="$ROOT/.android-sdk"

if [ ! -d "$SDK/platforms/android-35" ]; then
  echo "▸ Android SDK 命令行工具"
  fetch "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" \
        "$TC/cmdtools.zip" "cmdline-tools"
  [ -d "$SDK/cmdline-tools/latest" ] || {
    unzip -q -o "$TC/cmdtools.zip" -d "$SDK"
    mkdir -p "$SDK/cmdline-tools/latest"
    mv "$SDK/cmdline-tools"/{bin,lib,NOTICE.txt,source.properties} \
       "$SDK/cmdline-tools/latest/" 2>/dev/null || true
  }

  # 许可哈希：不写的话 sdkmanager 会停在交互式提问上，
  # 而 `yes |` 在 Git Bash 下喂不进去（读的是 /dev/tty）。
  mkdir -p "$SDK/licenses"
  printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee\nd56f5187479451eabf01fb78af6dfcb131a6481e\n8933bad161af4178b1185d1a37fbf41ea5269c55\n' \
    > "$SDK/licenses/android-sdk-license"
  printf '\n84831b9409646a918e30573bab4c9c91346d8abd\n504667f4c0de7af1a06de9f4b1727b84351f2910\n' \
    > "$SDK/licenses/android-sdk-preview-license"

  echo "▸ 安装 platform-35 与 build-tools（几分钟）…"
  JAVA_HOME="$(ls -d "$TC"/jdk-* | head -1)" \
  ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK" \
    "$SDK/cmdline-tools/latest/bin/sdkmanager" \
      "platform-tools" "platforms;android-35" "build-tools;34.0.0" < /dev/null
fi

printf 'sdk.dir=%s\n' "$(cygpath -m "$SDK" 2>/dev/null || echo "$SDK")" \
  > "$ROOT/local.properties"

echo
echo "完成。"
echo "  纯 JVM 单测（不需要 SDK）  ./scripts/jvm-test.sh"
echo "  完整构建（含渲染层）      ./scripts/gradlew-local.sh assembleDebug"
