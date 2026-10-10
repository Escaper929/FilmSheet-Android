#!/usr/bin/env bash
#  纯 JVM 单测链路 — 不需要 Android SDK。
#
#  为什么不用 ./gradlew test：Gradle 的 Android 插件要求 compileSdk 与
#  build-tools 都在位才能配置，而 core/ 与 config/ 这两个源集**根本不碰
#  任何 android.* API**（这正是把它们拆干净的理由）。所以这里直接用
#  kotlinc 编译 + JUnitCore 跑，几十秒就能验证，不必先装几个 GB 的 SDK。
#
#  代价是这条链路只能覆盖纯 JVM 的那部分。绘制层、ImageOps、UI 一旦落地，
#  仍需回到 Gradle（Android Studio 或装了 SDK 的命令行）。
#
#  用法：  ./scripts/jvm-test.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/.toolchain"

JAVA_HOME_DIR="$(ls -d "$TC"/jdk-* 2>/dev/null | head -1)"
KOTLINC="$TC/kotlinc/bin/kotlinc"
if [ ! -x "$KOTLINC" ]; then
  echo "找不到 kotlinc。先跑 scripts/bootstrap-toolchain.sh" >&2
  exit 1
fi

CP="$TC/lib/junit-4.13.2.jar:$TC/lib/hamcrest-core-1.3.jar"
# kotlin-stdlib 必须进**运行时** classpath —— kotlinc 编译时会自动带上，
# 但产物跑起来仍要它，漏了会得到一堆莫名其妙的 NoClassDefFoundError。
STDLIB="$TC/kotlinc/lib/kotlin-stdlib.jar"
OUT="$ROOT/build/jvm-test"

# 源码集划分是硬约束：一旦 core/ 或 config/ 里 import 了 android.*，
# 这个脚本就会编不过 —— 那正是它该报警的时刻。
PURE_MAIN="$ROOT/app/src/main/java/com/filmsheet/android/core
           $ROOT/app/src/main/java/com/filmsheet/android/config"
PURE_TEST="$ROOT/app/src/test/java/com/filmsheet/android/core
           $ROOT/app/src/test/java/com/filmsheet/android/config"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "▸ 编译主源集…"
JAVA_HOME="$JAVA_HOME_DIR" "$KOTLINC" \
  -nowarn -d "$OUT/main" $PURE_MAIN 2>&1 | grep -v '^warning:' || true

echo "▸ 编译测试源集…"
JAVA_HOME="$JAVA_HOME_DIR" "$KOTLINC" \
  -nowarn -cp "$OUT/main:$CP" -d "$OUT/test" $PURE_TEST 2>&1 | grep -v '^warning:' || true

# 找出所有以 Tests / Test 结尾的类，含 Kotlin 内部类（Foo$Bar）。
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' -o -name '*Tests.class' \
  | sed 's|^\./||; s|\.class$||; s|/|.|g' | grep -v '\$' | sort)

if [ -z "$CLASSES" ]; then
  echo "没有找到测试类" >&2
  exit 1
fi

echo "▸ 运行 $(echo "$CLASSES" | wc -l) 个测试类…"
echo
# 测试名是中文，Windows 控制台默认 GBK 会把它们显示成乱码。
JAVA_HOME="$JAVA_HOME_DIR" "$JAVA_HOME_DIR/bin/java" \
  -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -cp "$OUT/main:$OUT/test:$CP:$STDLIB" org.junit.runner.JUnitCore $CLASSES
