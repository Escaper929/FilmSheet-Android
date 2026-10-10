#!/usr/bin/env bash
#  用桌面端 Python 引擎重新生成参考图。
#
#  参考图是**布局算术的权威答案**：Android 端渲染的同一批用例必须与它们
#  尺寸一致（`ReferenceSizeTests`，纯 JVM 可跑）。改任何渲染参数后都要
#  重新生成并更新那张尺寸表。
#
#  用法：  ./Verification/regen-reference.sh
#
#  依赖：
#   - 桌面端 FilmSheet 仓库（含 processor/ 与 engine/）
#   - 系统 Python 3.14 + Pillow（本机是 C:/Python314）
#   - FilmSheet-Swift 仓库里的 Verification/make_inputs.py 与
#     render_reference.py —— 对照基准脚本共用同��份，避免两端口径不同
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/.toolchain"

# 桌面端仓库位置
FILMSHEET_SRC="${FILMSHEET_SRC:-/d/FilmSheet}"
# 对照脚本所在（iOS 端那套，两端共用保证输入与用例表一致）
SWIFT_V="${SWIFT_V:-/d/FilmSheet-Swift}"
PY="${PY:-/c/Python314/python.exe}"

if [ ! -d "$FILMSHEET_SRC/processor" ]; then
  echo "找不到桌面端仓库：$FILMSHEET_SRC（用 FILMSHEET_SRC 覆盖）" >&2
  exit 1
fi
if [ ! -f "$SWIFT_V/Verification/render_reference.py" ]; then
  echo "找不到对照脚本：$SWIFT_V/Verification（用 SWIFT_V 覆盖）" >&2
  exit 1
fi

OUT="$ROOT/build/verify"
rm -rf "$OUT"
mkdir -p "$OUT"/{inputs,reference}

export FILMSHEET_SRC

echo "▸ 生成测试底片…"
"$PY" "$SWIFT_V/Verification/make_inputs.py" "$OUT/inputs"

echo "▸ 渲染参考图…"
# 用例表在 render_reference.py 的 CASES 里，改那里
for c in 135_light_info 135_dark_plain 135_bh_perf 135_half \
         120_66 120_617_wide 135_single; do
  "$PY" "$SWIFT_V/Verification/render_reference.py" "$c" "$OUT/inputs" "$OUT/reference"
done

echo "▸ 同步到 androidTest assets…"
cp "$OUT"/inputs/*.png   "$ROOT/app/src/androidTest/assets/inputs/"
cp "$OUT"/reference/*.png "$ROOT/app/src/androidTest/assets/reference/"

echo
echo "完成。参考图已更新。"
echo "  接着跑 ./scripts/jvm-test.sh —— 若画布尺寸测试失败，"
echo "  说明布局算术与桌面端漂移了，需要同时更新 ReferenceSizeTests 的尺寸表。"
