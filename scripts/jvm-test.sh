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
# 编译失败必须立刻退出并保留错误信息。之前这里挂着 `|| true`，
# 编译器报再多的错也照样往下跑测试，最后只看到一堆莫名其妙的失败 ——
# 那正是"光写不编译"能蒙混过关的原因。
#
# 两个坑：
#  1. 不能写 `kotlinc ... 2>&1 | grep -v warning` —— 全部行都被滤掉时
#     grep 返回 1，配合 set -o pipefail 会把**成功**的编译判成失败。
#     所以先把输出落盘，再单独筛。
#  2. set -e 下失败的命令会直接终止，`|| { ... }` 根本没机会执行。
#     所以显式 set +e 捕获退出码，再自己处理。
set +e

compile() { # 日志文件 输出目录 其后是 kotlinc 的其余参数
  local log="$1" outdir="$2"; shift 2
  JAVA_HOME="$JAVA_HOME_DIR" "$KOTLINC" -nowarn -d "$outdir" "$@" > "$log" 2>&1
}

show_errors() { grep -v '^warning:' "$1" || true; }

BUILD_MAIN="$OUT/main.compile.log"
compile "$BUILD_MAIN" "$OUT/main" $PURE_MAIN
rc=$?
if [ $rc -ne 0 ]; then show_errors "$BUILD_MAIN"; exit $rc; fi

echo "▸ 编译测试源集…"
BUILD_TEST="$OUT/test.compile.log"
compile "$BUILD_TEST" "$OUT/test" -cp "$OUT/main:$CP" $PURE_TEST
rc=$?
if [ $rc -ne 0 ]; then show_errors "$BUILD_TEST"; exit $rc; fi

set -e
# 只剩 warning 时也让人看见，但不因此判定失败
show_errors "$BUILD_MAIN" | grep -v '^$' || true
show_errors "$BUILD_TEST" | grep -v '^$' || true

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
