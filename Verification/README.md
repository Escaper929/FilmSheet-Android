# FilmSheet Android — 验证方法

移植渲染引擎最大的风险不是编译不过，而是**能跑但画错了**：尺寸对、颜色对，
但帧的位置、旋转方向或行偏移错了，肉眼在缩略图上根本看不出来。
所以这里固定了一套可重复的对照流程。

## 基准是桌面端 Python 引擎

三端里桌面端是原始实现（iOS 与 Android 都是它的移植），所以拿它当参照。
参考图由 `Verification/regen-reference.sh` 用**桌面端自己的代码**渲染 ——
不自己写一份参考渲染器，避免把偏差带进基准。

| 端 | 角色 |
|---|---|
| 桌面端 Python/PIL | 基准 |
| FilmSheet-Swift | 移植一（已有自己的对照流程） |
| 本仓库 Kotlin/Compose | 移植二（本文件） |

## 两层验证，价值差别很大

### 一、画布尺寸 —— 纯 JVM，30 秒，不需设备

`core/ReferenceSizeTests` 把桌面端参考图的**尺寸**钉成断言：

```bash
./scripts/jvm-test.sh     # 其中包含 ReferenceSizeTests
```

这一条比任何像素比对都强。画布尺寸由布局算术唯一决定，而布局算术覆盖
（SPEC §5）：

- 边距 `sideMargin` / `topMargin` / `bottomMargin`
- 行数（135 的 `+2` 引带、120 的 `max(1, ceil)`）
- 胶片条高度 `stripH`（135 的 35mm、120 的 61mm 衬纸）
- 齿孔间距 `pitch`（KS 4.750 / BH 4.740）
- 画幅宽高（135 的子格式 mm、120 的画幅宽映射）
- 信息块高度（按实际填了的行数）
- 画布高度公式

**任何一处漂移都会让尺寸变化。** 改动渲染参数后先跑这条 ——
它比「盯着预览图看」可靠得多。

当前 7 个用例全部吻合。

### 二、像素差异 —— 需设备或模拟器

`verify/PixelParityTests`（instrumented）渲染同一批用例并逐像素比对：

```bash
./scripts/gradlew-local.sh connectedAndroidTest
```

**meanD 的合理范围：135 是 2~8，120 是 2~16。** 剩下的是两类无法消除的差异：

1. **重采样核不同** —— 桌面端 PIL LANCZOS，这里 `createScaledBitmap`（双线性）。
2. **字体栅格化不同** —— FreeType vs Android 字体栈，字形提示与抗锯齿策略都不一样。

### 实测结果（2026-10-10）

```
135_light_info   meanD=2.71  最差行带(顶部)=8.8   ← 信息块与片边文字的字体差异
135_dark_plain   meanD=0.92  最差行带     =1.9
135_bh_perf      meanD=2.06  最差行带(顶部)=4.2   ← 同上
135_half         meanD=1.56  最差行带(顶部)=4.5   ← 同上
120_66           meanD=11.67 最差行带(中部)=43.6  ← 桌面端 bug，见下
120_617_wide     meanD=8.44  最差行带     =18.7   ← 同上
135_single       meanD=2.81  —                   ← 单张模式
```

**看分布比看均值有用得多。** 135 的差异全部集中在**顶部行带** ——
那里是信息块与片边文字，即字体差异；而底部空白边距带 ≤ 0.6，几何是对的。

`空白边距区域几乎无差异` 这条断言就是靠这个特征：底部边距是纯色背景，
不受重采样与字体影响，两端必须完全一致。它比整体 meanD 强得多。

### 两个 120 用例分数高：这是**修好了**，不是移植错误

差异**集中在中部几行**，正是桌面端 120 丢图 bug 的位置。
`BaseRenderer._count_images_in_row` 按 135 的两格引带算行首推进量，
而 `Renderer120._place_images_in_row` 从第 0 列放图。6 张图 3 列时：

```
行首推进量算成   row0: 放 1 张（起点 0）
                row1: 放 3 张（起点 1）   ← 应为 3
                row2: 放 2 张（起点 4）
实际放置却是     row0: 帧 [0,1,2] 从列 0
                row1: 帧 [1,2,3] 从列 0   ← 第 1、2 张重复
```

本端与 iOS 端都覆写了 `firstColumnFor(row)` 恒返回 0 修掉了它
（SPEC.md §8 第 2 条）。**所以这里分数高才对** ——
与桌面端「一致」意味着复刻一个 bug。

## 模拟器

```bash
# 建 AVD（AVD_HOME 也要放 D 盘，C 盘不够）
export ANDROID_AVD_HOME="$PWD/.avd"
echo no | .android-sdk/cmdline-tools/latest/bin/avdmanager create avd \
  -n fsverify -k "system-images;android-30;aosp_atd;x86_64" -d pixel_2

# 无窗口启动（自动化用）
export ANDROID_EMULATOR_HOME="$PWD/.avd"
.android-sdk/emulator/emulator.exe -avd fsverify \
  -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
```

用 `aosp_atd` 镜像：它专为自动化测试优化，没有 Play 商店和多余的系统应用，
启动快、镜像小。

## 7 个用例覆盖什么

| 用例 | 覆盖 |
|---|---|
| `135_light_info` | 信息块三行、片头引带、KS 齿孔、签名水印 |
| `135_dark_plain` | 接触印相配色、无信息块时的底边距 |
| `135_bh_perf` | BH 齿孔（束腰外形）、非默认缩略图宽度、5 列 |
| `135_half` | 半格 18×24（竖幅，转向规则与横幅相反） |
| `120_66` | 120 衬纸、无齿孔 |
| `120_617_wide` | 120 宽幅 + 接触印相组合 |
| `135_single` | 单张模式（只画一条片） |

用例表在两处各有一份，**改一处必须同步另一处**：
- `Verification/regen-reference.sh` 复用 iOS 端 `render_reference.py` 的 `CASES`
- `verify/VerifyCases.kt`（instrumented）与 `core/ReferenceSizeTests.kt`（JVM）

三处不一致的话，对照结果就没有意义 —— 因为已经不知道在比什么。

## 输入底片的用意

`make_inputs.py` 生成的 6 张图刻意覆盖每一条图像管线的分支：
横构图、竖构图、方形、超宽幅，每张带一个**居中的圆**。

圆是关键：裁切和旋转之后它**仍然必须是圆**。
变成椭圆 → 宽高比错了；圆不见了 → 旋转错了。
另外那排竖条的间距能暴露横向裁切或缩放的误差。

## 无设备时的降级路径

模拟器需要下载系统镜像（约 1 GB）。没有设备也**不影响验证布局** ——
`ReferenceSizeTests` 在纯 JVM 上跑，30 秒出结果，而它覆盖的正是最容易出错的部分。

## 参考图失效时

改动渲染参数后：

```bash
./Verification/regen-reference.sh     # 重新生成参考图
./scripts/jvm-test.sh                 # 若尺寸测试失败 → 布局与基准漂移了
```

若尺寸确实应该变，同时更新 `ReferenceSizeTests` 里那张尺寸表 ——
它记录的是「桌面端当前是这样」，不是一个应该被自动接受的值。
