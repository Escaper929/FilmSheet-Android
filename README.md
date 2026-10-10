# FilmSheet for Android

胶片接触印相排版工具的 Android 原生实现，用 Kotlin + Jetpack Compose 编写。

桌面端是 Python/PIL，iOS 端是 Swift + CoreGraphics。三端共享**同一份渲染规格**，
各自实现渲染层。

---

## 与 iOS 端的关系

两端**不共享代码**，共享规格：

| | iOS | Android |
|---|---|---|
| 语言 | Swift | Kotlin |
| 绘制 | CoreGraphics | Compose Canvas（Skia） |
| 解码 | ImageIO | BitmapFactory + ExifInterface |
| 渲染规格 | [`SPEC.md`](https://github.com/Escaper929/FilmSheet-Swift/blob/main/SPEC.md) | 同一份 |

**权威源是 `FilmSheet-Swift` 仓库里的 `SPEC.md`。** 本仓库根目录也放了一份副本
（`SPEC.md`），方便离线查阅，但**不要在这里直接改** —— 改完记得同步回
FilmSheet-Swift，否则两端会漂移。

改任何常量、清洗规则或行推进逻辑的顺序：先改 FilmSheet-Swift 的 SPEC.md，
推到 GitHub，再把副本同步到本仓库，然后两端各自改实现。

## 为什么不用 Kivy / Flutter

- **Kivy**：能复用桌面端的 Python 引擎，但要交叉编译 Pillow（python-for-android
  生态最容易炸的一环），启动慢体积大，且此前已尝试过一次并放弃。
- **Flutter / React Native**：要再学第三种语言，渲染层照样重写，还损失像素级
  控制力。胶片排版依赖 `int()` 截断语义和齿孔弧度计算，抽象层反成障碍。

Android 端的价值在于：**开发环境全在 Windows**，且 APK 装一次一直用
（不受 iOS 那种 7 天重签限制）。

---

## 当前进度

### 纯 JVM 层（不碰 `android.*`，可用 kotlinc 直接跑）

| 模块 | 文件 | 说明 |
|---|---|---|
| 物理常量 | `core/FilmEngine.kt` | 135 SMPTE + 120 ISO 732 常量表 |
| 行计划 | `core/RowPlan.kt` | 行首列、行数、每行张数与起始帧号 |
| 配置模型 | `config/SheetConfig.kt` | 默认值、校验、清洗、信息字段 |
| 布局算术 | `core/SheetLayout.kt` | 布局结果的数据类与派生量 |
| 布局计算 | `core/LayoutCalculator.kt` | 画布尺寸、信息块高度、135/120 几何、包装图定位 |
| 图像几何 | `core/ImageGeometry.kt` | 居中裁切、盖满裁切、转向判定、EXIF 角度 |
| 片边文字 | `core/EdgeText.kt` | 品牌/型号映射、FNV-1a 批号 |
| 随机数 | `core/SeededRNG.kt` | SplitMix64，片边印字的散布 |
| 输出命名 | `core/OutputNaming.kt` | 文件名派生、批量策略、重处理判定 |
| 参照尺寸 | `core/ReferenceSizeTests.kt` | 7 个用例的画布尺寸与桌面端钉死 |

**几何为什么也放在 core**：裁切与转向算错了不会崩，只会让照片被裁歪一点 ——
肉眼看出「不对劲」但极难定位。抽到这里就能被 JVM 单测逐像素验证，
不必上 Robolectric。

**重处理判定为什么也在 core**：界面里存的是**已处理好的缩略图**，
而裁切/反相/转向/缩放都发生在预处理阶段，布局阶段改不了它们。
漏判的后果很隐蔽 —— 预览看着「排版变了」，照片却是旧画幅裁的，
要等导出才发现。见 `ReprocessRule`。

### 渲染与界面层（依赖 Skia / Compose，需要 Android SDK）

| 模块 | 文件 | 说明 |
|---|---|---|
| 调色板 | `render/Palette.kt` | 灯板正片 / 接触印相两套配色 |
| 画布 | `render/SheetCanvas.kt` | 矩形、圆角、椭圆、多边形、文本、贴图 |
| 渲染器基类 | `render/SheetRenderer.kt` | 渲染顺序、信息块、包装图、水印、帧号 |
| 135 | `render/Renderer135.kt` | 齿孔（KS/BH）、片边文字、照片放置 |
| 120 | `render/Renderer120.kt` | 衬纸帧号、照片放置 |
| 图像处理 | `render/ImageOps.kt` | 解码、EXIF 校正、反相、裁切（算术转发给 `ImageGeometry`） |
| 照片流水线 | `data/PhotoPipeline.kt` | URI → 缩略图，逐帧容错 |
| 编排 | `ui/SheetController.kt` | 预览/完整渲染、编码、保存、批量导出 |
| 主界面 | `ui/SheetScreen.kt` | 选图 / 预览 / 高频参数 / 保存 |
| 参数面板 | `ui/ParameterSheet.kt` | 7 组参数，对齐 iOS 端 ParameterSheet |

**主路已通**：选图 → 排版 → 保存到相册 → 系统分享。用系统 PhotoPicker，
**不声明任何存储权限**。

### 两条容易漏的交互规则

1. **预览用 80px**。`thumb_width=1200` 时画布约 8000px 宽，拖滑块
   每秒触发几十次变更，每次都完整渲染会把界面卡死。预览把 `thumbWidth`
   压到 80（与桌面端预览一致），导出才按设定宽度渲染。
2. **预览防抖 250ms**，新变更取消上一次未完成的渲染。与 iOS 端
   `SheetViewModel.refreshPreview` 同构。

> 教训：跨端移植时要去读对端的 ViewModel/Controller，不只是渲染层。
> 交互策略不在 SPEC 里，也不在渲染器里，而在编排层。

单测：纯 JVM **153 个全绿**；instrumented **12 个全过**（需模拟器）。

---

## 验证：与桌面端逐像素对照

移植渲染引擎最大的风险不是编译不过，而是**能跑但画错了**。所以有一套
固定的可重复对照流程，详见 [`Verification/README.md`](Verification/README.md)。

```bash
./Verification/regen-reference.sh                    # 用桌面端引擎重新生成参考图
./scripts/jvm-test.sh                               # 布局算术对照（30 秒，不需设备）
./scripts/gradlew-local.sh connectedDebugAndroidTest # 像素对照（需模拟器）
```

**最值得每天跑的是中间那条。** 画布尺寸由布局算术唯一决定，而布局算术
覆盖 SPEC §5 的全部内容（边距、行数、片高、齿孔间距、帧宽高、信息块高度、
画布高度公式）。任何一处漂移都会让尺寸变化，且它**不受重采样核与字体
栅格化差异的影响**——比像素比对干净得多。当前 7 个用例全部逐字节吻合。

像素比对的 meanD：135 是 2~8，120 是 2~16。两个 120 用例偏高是**因为
本端修掉了桌面端的 120 丢图 bug**，与桌面端「一致」反而意味着复刻 bug。
细节见 Verification/README.md。

### 构建命令

```bash
./scripts/bootstrap-toolchain.sh   # 首次：下载便携 JDK17 / kotlinc / Gradle / Android SDK
./scripts/jvm-test.sh              # 153 个纯 JVM 单测，约 30 秒，不需要 SDK
./scripts/gradlew-local.sh test    # 走 Gradle（需要 SDK）
./scripts/gradlew-local.sh assembleDebug   # 产物 app/build/outputs/apk/debug/
```

---

## 为什么有两条测试链路

`core/` 与 `config/` 根本不碰 `android.*` API（这正是把它们拆干净的理由），
所以不必等 SDK 就位就能验证最内层的算术——`jvm-test.sh` 直接用 kotlinc
编译加 JUnitCore 跑，30 秒出结果。

它同时是一道护栏：**这两个源集里一旦出现 `import android.*`，脚本立刻编不过。**

需要图形 API 的部分（渲染层、UI）走 Gradle 的 instrumented test。

日常开发直接用 Android Studio 打开本目录即可，不需要这些脚本。

---

## Android 端与另两端的三处有意分歧

除 `SPEC.md` §8 已记录的两条外，Android 端还有一条**只在渲染层**的分歧：

**不做 y 轴翻转。** iOS 端 `Canvas` 初始化时必须翻转 CTM，因为
CoreGraphics 原点在**左下角**、y 向上，而排版数字全部从桌面端 PIL 的
**左上原点**口径移植过来。翻转一次才让整份移植对得上。

Compose / Skia 本身就是左上原点、y 向下，**画布空间与布局空间天然一致**
—— 照抄 iOS 的翻转反而会把整张拼版上下颠倒。所以 `SheetCanvas` 不做任何
坐标变换，`SheetLayout` 的数字可以直接用。

另外两条是共有的：不做 4 倍超采样、修掉 120 多行丢图。

---

## 端间漂移：三处已修正

跨端移植时发现的实际缺陷，都已同步进 `SPEC.md` 并在两端修复：

1. **120 单列丢行**（iOS 与 Android 都有）。`Renderer120.computeLayout`
   对 `cols == 1` 无条件设 `totalH = stripH`，但 120 单列时**每张照片各占
   一行**，于是 6 张照片只留 1 行的高度，后 5 行被静默裁掉且不报错。
   修法见 SPEC.md §5.3.1：压缩的触发条件是 `rows == 1` 而非 `cols == 1`。
   135 不受影响 —— `cols == 1` 时行数恒为 1。

2. **645 宽高比横竖颠倒**（仅 Android）。645 是唯一的竖幅 120 格式，
   比值是 `56 / 41.5`；通用公式 `widthMM / 56` 会算出 0.741，
   照片被按错误方向裁切。见 SPEC.md §3。

3. **`calcCommonLayout` 吞掉调用方的零间距**（仅 Android 新实现）。
   它内部重算 `spacing`，把单张模式传入的 0 覆盖掉了。已加
   `spacingOverride` 参数。

另修掉 iOS 端一条自己通不过的测试断言：电影胶卷关键词是 `cinema`，
测试里却写成了 `cine`（不含 `cinema`）。

---

## 行计划为什么必须是一次性前缀和

每行放几张取决于还剩多少帧，剩余量又取决于前面所有行放了几张 —— 依赖是顺序的。
但**不能**写成互相递归：

```kotlin
// 错：不要这样写
fun firstImageIndex(row: Int): Int =
    (0 until row).sumOf { countInRow(it) }
fun countInRow(row: Int): Int {
    var idx = firstImageIndex(row)   // ← 又调回来了
    ...
}
```

展开后 `T(row) ≈ 2·T(row−1)`，是斐波那契型。iOS 端曾因此需要约 **8.6×10^10**
次调用才能排完 200 张照片，渲染永远跑不完 —— 而 iOS 的照片选择器上限正好是 200。

正确做法是一趟前向扫描，之后两个查询都是 O(1) 查表。见 `RowPlan.build`。

---

## 构建

需要 JDK 17+ 与 Android SDK（compileSdk 35）。Windows 上直接用 Android Studio
打开本目录即可，无需 Mac。命令行构建见上文「构建命令」。

---

## 换算语义

从 Python 逐行移植时，截断语义必须一致：

| 语言 | 向零截断 | 向下取整 |
|---|---|---|
| Python | `int(x)` | `math.floor(x)` |
| Swift | `Int(CGFloat)` | `floor(x)` |
| Kotlin | `.toInt()` | `Math.floor(x)` |

**全部换算用 `toInt()`，不要用 `floor()`** —— 负数时会差 1。

---

## 不做 4 倍超采样

桌面端 PIL 的 `ImageDraw` 没有抗锯齿，所以它在 4 倍画布上绘制再 LANCZOS 缩回来，
靠缩放这一步完成边缘混合。实测代价：`thumb_width=1200` 时 6 帧 135 拼版峰值约
**2.9 GB**。

Compose Canvas 与 CoreGraphics 本身对每条路径都做抗锯齿，**1 倍绘制即可**，
峰值内存降到约 1/16。