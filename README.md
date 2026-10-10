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

已完成的部分都不依赖任何图形 API，可以直接跑 JVM 单测：

| 模块 | 文件 | 说明 |
|---|---|---|
| 物理常量 | `core/FilmEngine.kt` | 135 SMPTE + 120 ISO 732 常量表 |
| 行计划 | `core/RowPlan.kt` | 行首列、行数、每行张数与起始帧号 |
| 配置模型 | `config/SheetConfig.kt` | 默认值、校验、清洗、信息字段 |
| 单测 | `test/.../RowPlanTests.kt` | 与 iOS 端 `RowPlanTests.swift` 一一对应 |

尚未实现：

- 图像解码与 EXIF 校正（`ImageOps` 等价物）
- 绘制层（`Canvas` / `Renderer135` / `Renderer120` 等价物）
- 照片选择、相册写入、分享
- 像素对照基准（Skia 与 CoreGraphics 抗锯齿、字体栅格化都不同，
  iOS 侧的 meanD 成绩不能套用，需要另建一套）

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
打开本目录即可，无需 Mac。

```bash
# 命令行构建与测试
./gradlew test              # JVM 单元测试，不需要模拟器
./gradlew assembleDebug     # 产物 app/build/outputs/apk/debug/
```

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