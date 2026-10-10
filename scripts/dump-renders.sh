#!/usr/bin/env bash
#  渲染全部对照用例，把图取到 build/renders/。
#
#  两个用途：
#   1. 肉眼比对 —— meanD 只说「差多少」，不说「差在哪」
#   2. 跨端交叉验证 —— 把图喂给 iOS 端的 compare.py，
#      就能在 PC 上分析两端差异，不必在 Mac 上跑模拟器
#
#  用法：  ./scripts/dump-renders.sh
#
#  为什么走 logcat 传输：instrumented 测试跑完**被测应用会被卸载**，
#  它的 externalFilesDir 随之消失，adb pull 会扑空（实测如此）。
#  logcat 不受那个生命周期影响。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="$ROOT/.android-sdk"
ADB="$SDK/platform-tools/adb.exe"
OUT="$ROOT/build/renders"
PY="${PY:-/c/Python314/python.exe}"

if ! "$ADB" devices 2>/dev/null | grep -q "emulator-.*[[:space:]]device$"; then
  echo "没有运行的模拟器。先跑 ./scripts/start-emulator.sh" >&2
  exit 1
fi

echo "▸ 渲染用例（结果走 logcat）…"
"$ADB" logcat -c 2>/dev/null || true

"$ROOT/scripts/gradlew-local.sh" connectedDebugAndroidTest --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.class=com.filmsheet.android.verify.DumpRenders \
  2>&1 | grep -E "Finished|BUILD|FAILED" || true

echo "▸ 从 logcat 重组 PNG…"
mkdir -p "$OUT"
"$ADB" logcat -d 2>/dev/null > "$ROOT/build/logcat.txt"

"$PY" - "$ROOT/build/logcat.txt" "$OUT" <<'PYEOF'
import base64, re, sys, os

log_path, out_dir = sys.argv[1], sys.argv[2]
with open(log_path, encoding="utf-8", errors="replace") as f:
    lines = f.read().splitlines()

count = 0
current, chunks = None, []

def flush():
    global count
    if current is None:
        return
    data = base64.b64decode("".join(chunks))
    with open(os.path.join(out_dir, current + ".png"), "wb") as out:
        out.write(data)
    print(f"  {current}.png  {len(data) // 1024} KB")
    count += 1

for line in lines:
    # 只取 System.out 里我们打的标记；logcat 每行前面有固定前缀
    m = re.search(r"@@B64START@@ (\S+) (\d+)x(\d+)", line)
    if m:
        flush()
        current, chunks = m.group(1), []
        continue
    if re.search(r"@@B64END@@", line):
        flush()
        current, chunks = None, []
        continue
    m = re.search(r"@@B64@@(\S+)", line)
    if m and current:
        chunks.append(m.group(1))

flush()
print(f"\n共 {count} 张")
sys.exit(0 if count else 1)
PYEOF

echo
echo "完成。目录：$OUT"
