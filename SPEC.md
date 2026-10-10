# FilmSheet 渲染规格（跨端唯一真相源）

本文件定义 FilmSheet 排版的**全部算术与常量**。iOS（Swift/CoreGraphics）与
Android（Kotlin/Compose）各自实现，但必须从这里取值，不得各自硬编码。

**为什么需要这份文件**：两个端的渲染层不共享代码（Swift 与 Kotlin 无法互相编译），
共享的只有算法。如果各自维护一份常量，同一个改动会在两端漂移——而排版漂移
是肉眼可见的产出差异。规格是共享的，代码是各自实现的。

改动本文件时，请同步更新两端的实现并各跑一遍对照测试。

---

## 1. 配置默认值

| 字段 | 默认值 | 范围 | 备注 |
|---|---|---|---|
| `thumbWidth` | 400 | 300–1200 | 上限受内存约束，见 §6 |
| `spacing` | 20 | — | 实际间距 = `spacing × thumbWidth / 400` |
| `columns` | 6 | 1–99 | 1 触发单张模式 |
| `forceLandscape` | true | — | |
| `outputFormat` | JPG | PNG / JPG | |
| `quality` | 95 | 1–100 | |
| `filmFormat` | 135 | 135 / 120 | |
| `subFormat` | 135→`标准 36×24`，120→`66` | 见 §3 | 非法值回落到该格式默认 |
| `infoLang` | en | zh / en | |
| `packPosition` | left | left / right | |
| `packSize` | 80 | 10–100 | 百分比，非绝对值 |
| `packBorderStroke` | true | — | |
| `processingMode` | positive | positive / negative | |
| `perfMode` | auto | auto / KS / BH | auto 见 §2 |

`sanitized()`（清洗）顺序不可调换：

1. `subFormat` 为空 → 置为该 `filmFormat` 的默认值
2. `subFormat` 不在合法集合 → 置为默认值
3. `thumbWidth` clamp 到 [300, 1200]
4. `columns` clamp 到 [1, 99]
5. `quality` clamp 到 [1, 100]
6. `packSize` clamp 到 [10, 100]

> 第1、2 步必须早于通用 clamp：缺失的 `subFormat` 要依据 `filmFormat` 决定，
> 而不是反过来。

---

## 2. 135 物理常量（SMPTE，单位 mm）

来自 `FilmEngine135.swift`。这些数字决定「像胶片」而不是「像柱状图」，不得简化。

| 常量 | 值 |
|---|---|
| 片宽 `filmWidthMM` | 35.0 |
| 画幅宽 `frameWidthMM` | 36.0 |
| 画幅高 `frameHeightMM` | 24.0 |
| 片边到齿孔 `edgeToPerfMM` | 2.01 |
| 齿孔高 `perfDimYMM` | 2.794 |
| 齿孔宽（KS）`perfDimXKSMM` | 1.981 |
| 齿孔宽（BH）`perfDimXBHMM` | 1.854 |
| 齿孔半径 `perfRadiusMM` | 0.508 |
| BH 弧深 `bhCurveDepthMM` | 0.35 |
| 齿孔距（KS）`pitchKSMM` | 4.750 |
| 齿孔距（BH）`pitchBHMM` | 4.740 |

**齿孔类型判定** `determinePerfType(film, choice)`：

- `choice` 显式指定 KS/BH 时直接采用
- `auto` 时，把 `film` 转小写，若包含下列任一子串 → BH，否则 KS：
  `5207` `5294` `5219` `5203` `vision3` `ektachrome` `motion` `cinema`
- `pitch(type)` = KS ? 4.750 : 4.740

### 135 子格式表

`(画幅宽mm, 画幅高mm, 每画幅齿孔数)`

| 子格式 | 值 |
|---|---|
| 标准 36×24 | (36, 24, 8) |
| 半格 18×24 | (18, 24, 4) |
| 方形 24×24 | (24, 24, 5) |
| XPan 65×24 | (65, 24, 14) |

---

## 3. 120 物理常量（ISO 732，单位 mm）

| 常量 | 值 |
|---|---|
| 衬纸宽 `backingWidthMM` | 61.0 |
| 画幅高 `imageHeightMM` | 56.0 |
| 上下边框 `imageBorderMMTopBottom` | (61 − 56) / 2 = 2.5 |

120 **没有齿孔**，画幅编号印在衬纸上（`Renderer120` 在 `drawStripDecoration` 里画）。
`thumbWidth` 映射到的是**画幅宽**，不是片宽——这是 120 与 135 最容易搞错的地方。

### 120 子格式表（画幅宽 mm）

| 子格式 | 宽 |
|---|---|
| 645 | 41.5 |
| 66 | 56.0 |
| 67 | 70.0 |
| 68 | 76.5 |
| 69 | 84.0 |
| 612 | 112.0 |
| 617 | 168.0 |
| 624 | 224.0 |

### 画幅宽高比表（`filmFormatRatios`，W/H）

`135`=1.5、`645`=56/41.5、`66`=1.0、`67`=70/56、`68`=76.5/56、`69`=84/56、
`612`=112/56、`617`=168/56、`624`=224/56。

> ⚠️ **645 是唯一的例外，不能套用通用公式。** 其余 120 子格式都是
> `画幅宽mm / 56`，而 645 是竖幅格式，比值是 `56 / 41.5mm`。
> 若用一条 `widthMM / 56` 盖过整张表，645 会算成 0.741（横竖颠倒），
> 导致照片被按错误方向裁切。实现时要么单列 645，要么照抄这张表。

---

## 4. 行推进规则（两端必须一致）

这是最容易出错的一处，README 记录的 120 多行丢图就出在这里。

### 4.1 行首列

```
135: firstColumn(row) = (row == 0 && cols > 1) ? 2 : 0   // 两个片头引带位
120: firstColumn(row) = 0                                   // 无引带
```

`cols > 1` 的守卫不可省：单列时 `2..<1` 为空，会导致**什么都不渲染**。

### 4.2 行数

```
135: rows = (cols == 1) ? 1 : ceil((photoCount + 2) / cols)
120: rows = max(1, ceil(photoCount / max(1, cols)))
```

135 的 `+2` 是把两个引带位算进去，这样一整卷 72 张不会丢掉最后两张。

### 4.3 行计划（Row Plan）

每行放几张 = `min(cols - firstColumn(row), photoCount - rowStartIndex(row))`，
`rowStartIndex(0) = 0`，`rowStartIndex(row+1) = rowStartIndex(row) + 该行张数`。

> **实现注记**：这必须用**一次性前缀和**算出整张表的每行起始索引。
> 早期实现用 `firstImageIndex(row) = Σ countImages(r for r < row)`，
> 而 `countImages(row)` 内部又要调 `firstImageIndex(row)`——互相递归且无记忆化，
> 复杂度是斐波那契型。一卷 200 张的片子需要约 10^11 次调用，渲染永远跑不完。
> 正确做法是一趟前向扫描，之后两个查询都是 O(1) 查表。
> 等价性验证见 `Verification/check_row_plan.py`。

**帧必须连续无重复**：`placeImages` 按 `imgIdx` 单调递增遍历，永不回退。
任何实现都要满足「所有行拼起来的帧序列恰好是 `0..<photoCount`」。

---

## 5. 画布布局算术

### 5.1 公共布局 `calcCommonLayout`

```
scale        = thumbWidth / 400
sideMargin   = 50 × scale
topMargin    = 25 × scale
contentW     = cols × thumbW + (cols + 1) × spacing
totalW       = contentW + 2 × sideMargin + 100 × scale
bagGap       = 见 §5.3
```

信息块与边距：

```
infoToFilmGap   = 65 × scale
topAreaHeight   = topMargin + infoHeight + infoToFilmGap
topRegionHeight = topMargin + infoHeight
bottomMargin    = infoHeight == 0 ? topRegionHeight × 2.0
                              : topRegionHeight × 1.6
totalH          = topAreaHeight + rows × stripH + (rows-1) × bagGap + bottomMargin
```

`infoHeight == 0` 时底边距取 2 倍，是为了平衡胶片条上方的空白。

### 5.2 信息块高度

按**实际填了的行数**算，不是固定三行——只填了卷号的拼版会明显更矮。

```
lineHeight  = thumbW > 200 ? 52 × thumbW / 400 : 52 × scale
topPad      = thumbW > 200 ? 20 × thumbW / 400 : 20 × scale
bottomPad   = thumbW > 200 ? 15 × thumbW / 400 : 15 × scale

infoHeight = (有任一信息字段非空 && 有效行数 > 0)
           ? topPad + activeInfoRows × lineHeight + bottomPad
           : (有包装图 ? (thumbW > 200 ? 140 × thumbW / 400 : 140 × scale) : 0)
```

### 5.3 胶片条高度

```
135: scaleFactor = thumbW / frameW_mm
     stripH      = 35.0 × scaleFactor     // 片宽固定 35mm
     bagGap      = 50 × thumbW / 400

120: scaleFactor = thumbW / imgW_mm       // 注意是画幅宽，不是衬纸宽
     stripH      = 61.0 × scaleFactor     // 衬纸宽
     imgH        = 56.0 × scaleFactor
     bagGap      = 50 × scaleFactor
```

### 5.3.1 「压成一条」的触发条件

单张模式会把画布压缩成只剩胶片条（无信息块、无边距、`totalH = stripH`）。
**压缩的前提是 `rows == 1`，不是「列数为 1」。**

```
canCollapseToStrip = (rows == 1)
```

135 不受影响：`cols == 1` 时 `rows` 恒为 1，条件天然成立。

120 会被坑到 —— 它的 `rows = max(1, ceil(photoCount / cols))`，
`cols == 1` 时**每张照片各占一行**。若照 135 的写法对 `cols == 1`
无条件设 `totalH = stripH`，6 张照片只留 1 行的高度，后 5 行被静默裁掉，
而且不报任何错。

同理，单张模式下把 `spacing` 归零的判断也要跟着 `rows == 1` 走：
多行单列若间距为 0，画面会紧贴画布左右边缘。

### 5.4 信息块布局

三行三列，第 2 行第 3 列是空槽但仍占横向空间：

```
[卷号, 相机, 胶卷]
[拍摄日期, 冲洗日期, (空)]
[冲洗方式, 冲洗地点, 扫描仪]
```

- 列宽 = 该列最宽的「标签+值」文本宽度 + colGap（`40 × thumbW/400`）
- 三列总宽超出可用宽度时**按比例**压缩（包装图占位时必然发生）
- `卷号` 用空格而非冒号：`Roll No. 12`，其余字段用冒号：`Film: Portra 400`
- 可用宽度被包装图夹住：包装图在左则 `textLeft = packRight`，在右则 `textRight = packLeft`

### 5.5 渲染顺序（不可调换）

```
包装图 → 信息块 → 胶片条（片基 → 装饰 → 画面）→ 水印
```

包装图先画，因为信息块要按它剩下的空间来排文字。

---

## 6. 内存约束

桌面端 PIL 的 `ImageDraw` **没有抗锯齿**，所以它靠 4 倍超采样 + LANCZOS 缩回来
完成边缘混合。实测代价：`thumb_width=1200` 时 6 帧 135 拼版峰值约 **2.9 GB**。

CoreGraphics / Compose Canvas 本身对每条路径都做抗锯齿，**1 倍绘制即可**，
峰值内存降到约 1/16。两端都不要引入超采样。

---

## 7. 片边文字映射（`EdgeText`）

真实 135 胶片沿片边印片名：上行品牌+型号，下行帧计数器。

### 7.1 品牌映射

中文键与其音译/大写形式映射到同一值，使 `柯达` 与 `Kodak` 行为一致：

| 键（含变体） | 值 |
|---|---|
| 柯达 / Kodak / KODAK | KODAK |
| 富士 / Fujifilm / FUJIFILM | FUJIFILM |
| 柯尼卡 / Konica / KONICA | KONICA |
| 伊尔福 / Ilford / ILFORD | ILFORD |
| 阿克发 / Agfa / AGFA | AGFA |
| 乐凯 / Lucky / LUCKY | LUCKY |
| 波尔 / Porst / PORST | PORST |
| 斯达法 / Stadia / STADIA | STADIA |
| 哈苏 / Hasselblad / HASSELBLAD | HASSELBLAD |

### 7.2 型号映射（节选，完整表见 `EdgeText.swift` / 端内实现）

`Portra 160`→`Portra 160`、`Portra 160nc`→`Portra 160NC`、
`Portra 400`→`Portra 400`、`Tri-X 400`→`Tri-X 400`、`Vision3 500T`→`Vision3 500T`、
电影胶片号 `5207/5219/5294/5203/5222` 原样、`C200`/`c200`→`C200` 等。

匹配顺序：**精确 → 忽略大小写 → 子串（长键优先）**。

### 7.3 判定流程

1. 用户填了自定义片边文字 → 直接用，跳过所有映射
2. 胶卷名大写后若含电影关键词 → 品牌强制 `EASTMAN`，剥离原品牌前缀
   关键词：`VISION3` `CINEVISION` `EKTACHROME` `MOTION PICTURE`
   `5207` `5219` `5294` `5203` `5222`
3. 否则按空格拆分品牌与型号；若无空格命中品牌表，尝试中文品牌前缀匹配
   （`乐凯C200` → 品牌`乐凯`，型号`C200`）
4. 品牌未命中表 → 取大写；型号按 §7.2 匹配
5. 上行文本 = `型号非空 ? "品牌  型号" : "品牌"`（**两个空格**）

### 7.4 批号

用 **FNV-1a 24 位**哈希 `品牌+型号`，格式 `E{d0} {dddd}`。
不要用语言内置哈希（Python `hash()` 每进程加盐，批号会每次运行都变）。

---

## 8. 三处「有意分歧」

与桌面端不同，这三处是**故意的**，不要「修正」：

1. **不做 4 倍超采样** —— 见 §6，CoreGraphics/Canvas 自带抗锯齿
2. **修掉 120 多行丢图** —— 桌面端 `BaseRenderer._count_images_in_row`
   按 135 的引带算行首推进量，但 `Renderer120._place_images_in_row`
   从第 0 列放图，导致第 2 行重复第 1 行、最后几张永不出现。
   iOS/Android 两端都覆写 `firstColumn(forRow:)` 返回 0 修掉了。
3. **坐标原点** —— §5 的全部数字都是**左上原点、y 向下**（桌面端 PIL 的口径）。
   iOS/CoreGraphics 原点在左下、y 向上，所以 iOS 的 `Canvas` 初始化时翻转一次
   CTM 来对齐。**Android/Compose/Skia 本身就是左上原点、y 向下，与布局空间
   天然一致，所以不做任何翻转** —— 照抄 iOS 的翻转会把拼版上下颠倒。

---

## 9. 换算语义

从 Python 逐行移植时，**截断语义必须一致**：

- Python 的 `int(x)` 是**向零截断**，不是向下取整
- Swift 的 `Int(CGFloat)` 同样是向零截断 —— 一致
- Kotlin 的 `.toInt()` 也是向零截断 —— 一致
- 但 `floor()` / `Math.floor()` 是向下取整，**负数时会差 1**

全部换算用 `toInt()`，不要用 `floor()`。