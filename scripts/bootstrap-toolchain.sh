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

echo
echo "完成。跑 ./scripts/jvm-test.sh"
