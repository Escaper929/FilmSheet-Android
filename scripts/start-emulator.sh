#!/usr/bin/env bash
#  启动对照用的无窗口模拟器，并在就绪后返回。
#
#  像素对照的 instrumented test 需要一台设备。手工敲 emulator 命令容易漏
#  掉 AVD_HOME 之类的环境变量，漏了就会写到 C 盘 —— 而 C 盘只剩约 10 GB。
#
#  用法：  ./scripts/start-emulator.sh          # 后台启动并等待就绪
#          ./scripts/start-emulator.sh --stop   # 关掉
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="$ROOT/.android-sdk"
AVD_HOME_DIR="$ROOT/.avd"
AVD_NAME="fsverify"

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_AVD_HOME="$AVD_HOME_DIR"
export ANDROID_EMULATOR_HOME="$AVD_HOME_DIR"
ADB="$SDK/platform-tools/adb.exe"
EMU="$SDK/emulator/emulator.exe"

if [ ! -f "$AVD_HOME_DIR/$AVD_NAME.ini" ]; then
  echo "没有 AVD：$AVD_NAME。先跑 ./scripts/bootstrap-toolchain.sh" >&2
  exit 1
fi

if [ "${1:-}" = "--stop" ]; then
  "$ADB" emu kill 2>/dev/null || echo "没有在运行的模拟器"
  exit 0
fi

if "$ADB" devices | grep -q "emulator-.*	device"; then
  echo "模拟器已在运行"
  exit 0
fi

echo "▸ 启动 $AVD_NAME（无窗口）…"
# -gpu swiftshader_indirect 是软件渲染，这台机器没有可用的硬件加速，
# 而像素对照并不需要 GPU 正确性 —— Skia 走 CPU 路径结果是一样的。
"$EMU" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot > "$ROOT/build/emulator.log" 2>&1 &

echo "▸ 等待启动完成（首次约 60 秒）…"
for i in $(seq 1 40); do
  if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    echo "就绪。跑对照："
    echo "  ./scripts/gradlew-local.sh connectedDebugAndroidTest"
    exit 0
  fi
  sleep 10
done

echo "超时未就绪，日志见 build/emulator.log" >&2
exit 1
