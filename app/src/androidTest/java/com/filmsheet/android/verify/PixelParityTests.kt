package com.filmsheet.android.verify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.render.ImageOps
import com.filmsheet.android.render.Renderer120
import com.filmsheet.android.render.Renderer135
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 像素对照 harness — 与**桌面端 Python 引擎**逐像素比对，不与 iOS 比。
 *
 * ## 为什么是桌面端作基准
 *
 * 三端里桌面端是原始实现（iOS 与 Android 都是它的移植），所以拿它当参照。
 * iOS 侧的对照是另一套，不在本文件范围内。
 *
 * ## 尺寸为什么比像素更重要
 *
 * `画布尺寸与桌面端逐像素一致` 这条比任何像素值都重要：画布尺寸一次性验证了
 * **全部**布局算术 —— 边距、行数、片高、齿孔间距、帧宽高、信息块高度、
 * 画布高度公式（SPEC §5）。像素值必然有差异（重采样核与字体栅格化），
 * 尺寸不会有。
 *
 * 顺带一提：同一套算术在纯 JVM 上也验证得了 —— 见 `core/ReferenceSizeTests`，
 * 它把桌面端参考图的尺寸钉成断言，不需要设备。
 *
 * ## 像素分数的合理范围
 *
 * 与 iOS 端同口径：**2 ~ 16**。剩下的是两类无法消除的差异：
 *
 * 1. **重采样核不同** —— 桌面端 PIL LANCZOS，Android 端
 *    `Bitmap.createScaledBitmap`（双线性）。
 * 2. **字体栅格化不同** —— FreeType vs Android 字体栈，字形提示与
 *    抗锯齿策略都不一样。
 *
 * 差异分布比平均值更有信息量：纯色片基与空白边距应当 meanD = 0；
 * 那里非零就说明几何画错了。
 *
 * ## 两个 120 用例分数偏高 —— 这是**修好了**，不是移植错误
 *
 * 实测（2026-10-10）：
 *
 * ```
 * 135_light_info   meanD=2.71  最差行带(顶部)=8.8   ← 字体
 * 135_dark_plain   meanD=0.92  最差行带     =1.9
 * 135_bh_perf      meanD=2.06  最差行带(顶部)=4.2   ← 字体
 * 135_half         meanD=1.56  最差行带(顶部)=4.5   ← 字体
 * 120_66           meanD=11.67 最差行带(中部)=43.6  ← 桌面端 bug
 * 120_617_wide     meanD=8.44  最差行带     =18.7   ← 桌面端 bug
 * ```
 *
 * 135 的差异集中在顶部（信息块与片边文字，那是字体差异，属正常），
 * 底部的空白边距带接近 0 —— 几何是对的。
 *
 * 120 的差异**集中在中部几行**，那正是桌面端 120 丢图 bug 的位置：
 * `BaseRenderer._count_images_in_row` 按 135 的两格引带算行首推进量，
 * 而 `Renderer120._place_images_in_row` 从第 0 列放图。于是 6 张图 3 列时：
 *
 * ```
 * 行首推进量算成  row0: 放 1 张（起点 0）
 *                row1: 放 3 张（起点 1）  ← 应为 3
 *                row2: 放 2 张（起点 4）
 * 实际放置却是    row0: 帧 [0,1,2] 从列 0
 *                row1: 帧 [1,2,3] 从列 0  ← 第 1、2 张重复出现
 * ```
 *
 * 本端与 iOS 端都覆写了 `firstColumnFor(row)` 恒返回 0 修掉了它
 * （SPEC.md §8 第 2 条）。所以这里分数**高才对** ——
 * 与桌面端「一致」意味着复刻一个 bug。
 */
@RunWith(AndroidJUnit4::class)
class PixelParityTests {

    /**
     * 读参考图。
     *
     * 放在 `androidTest/assets/` 下而不是 `res/`：测试 APK 只打包
     * assets，`androidTest` 源集的 res 不参与。且读的时候不需要
     * 任何资源 ID 机制，路径字符串就够。
     */
    private fun loadReference(name: String): Bitmap? =
        readAsset("reference/$name.png")

    /** 读输入底片。 */
    private fun loadInputs(case: String): List<Bitmap> {
        val names = listOf(
            "frame01_landscape", "frame02_portrait", "frame03_square",
            "frame04_ultrawide", "frame05_landscape", "frame06_portrait",
        )
        val all = names.mapNotNull { readAsset("inputs/$it.png") }
        return if (VerifyCases.usesOnlyFirstFrame(case)) all.take(1) else all
    }

    private fun readAsset(path: String): Bitmap? = runCatching {
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(path).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /** 渲染一个用例。 */
    private fun render(case: String): Bitmap {
        val config = VerifyCases.configFor(case)
        val raw = loadInputs(case)
        assertTrue("用例 $case 没有可用输入帧", raw.isNotEmpty())

        // 走生产流水线：解码 → EXIF → 反相 → 转向 → 裁切
        val processed = raw.map { bmp ->
            when (config.filmFormat) {
                SheetConfig.FilmFormat.F135 ->
                    ImageOps.process135(
                        bmp, config.thumbWidth, config.processingMode,
                        config.forceLandscape, config.subFormat,
                    )
                SheetConfig.FilmFormat.F120 ->
                    ImageOps.process120(
                        bmp, config.subFormat, config.thumbWidth,
                        config.processingMode, config.forceLandscape,
                    )
            }
        }

        val images = processed.map { it.asImageBitmap() }
        return when (config.filmFormat) {
            SheetConfig.FilmFormat.F135 ->
                Renderer135(config, images).render().asAndroidBitmap()
            SheetConfig.FilmFormat.F120 ->
                Renderer120(config, images).render().asAndroidBitmap()
        }
    }

    // MARK: - 尺寸对照：最重要的一条

    /**
     * 画布尺寸必须与桌面端**逐像素一致**。
     *
     * 这一条失败意味着布局算术有偏差，后面所有像素比对都失去意义
     * —— 比的是两张尺寸不同的图，meanD 纯属噪声。
     */
    @Test
    fun `画布尺寸与桌面端逐像素一致`() {
        val failures = mutableListOf<String>()
        for (case in VerifyCases.NAMES) {
            val ref = loadReference(case)
            if (ref == null) {
                failures += "$case：参考图缺失"
                continue
            }
            val got = render(case)
            if (ref.width != got.width || ref.height != got.height) {
                failures += "$case：期望 ${ref.width}×${ref.height}，" +
                    "实得 ${got.width}×${got.height}"
            }
        }
        assertTrue(
            "以下用例的画布尺寸与桌面端不符：\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    // MARK: - 像素对照

    /**
     * 像素差异分数。
     *
     * 单独跑（不并进上面的尺寸断言），这样尺寸已经对齐时它才有意义。
     */
    @Test
    fun `像素差异在合理范围内`() {
        val report = mutableListOf<String>()
        for (case in VerifyCases.NAMES) {
            val ref = loadReference(case) ?: continue
            val got = render(case)
            if (ref.width != got.width || ref.height != got.height) {
                report += "${case.padEnd(16)} SIZE    参考 ${ref.width}×${ref.height} " +
                    "实得 ${got.width}×${got.height}"
                continue
            }
            val d = diff(ref, got)
            report += "${case.padEnd(16)} OK      meanD=${"%.2f".format(d.mean)} " +
                "p95=${d.p95} max=${d.max} >32px=${"%.1f".format(d.bigPct)}%"
        }
        println("@@PARITY@@")
        report.forEach { println("@@ROW@@$it") }
        println("@@END@@")

        for (line in report.filter { it.contains("meanD=") }) {
            val case = line.substringBefore(" ").trim()
            val mean = line.substringAfter("meanD=").substringBefore(" ").toDouble()
            // 120 用例的分数天生更高：桌面端 120 多行丢图那个 bug 在本端已修，
            // 所以「与桌面端一致」恰好意味着复刻 bug。见类注释的详细推演。
            val limit = if (case.startsWith("120_")) 16.0 else 8.0
            assertTrue(
                "$case 像素差异 $mean 超出上限 $limit，多半是几何画错了：\n$line",
                mean in 0.0..limit,
            )
        }
    }

    /**
     * 差异的空间分布 — 用来定位「差在哪」。
     *
     * meanD 只说「差多少」，不说「差在哪」。把图切成 8×8 的格子分别算
     * meanD，就能看出差异是**均匀分布**（多半是重采样核或字体，那是平台
     * 差异，属正常）还是**集中在某几块**（多半是几何画错了）。
     */
    @Test
    fun `像素差异的空间分布`() {
        for (case in VerifyCases.NAMES) {
            val ref = loadReference(case) ?: continue
            val got = render(case)
            if (ref.width != got.width || ref.height != got.height) {
                println("@@GRID@@$case 尺寸不符，跳过")
                continue
            }
            val grid = gridDiff(ref, got, 8)
            val worstRow = grid.indices.maxBy { i -> grid[i].average() }
            val worst = grid[worstRow].average()
            val uniform = grid.flatten().average()
            println("@@GRID@@${case.padEnd(16)} 整体=${"%.2f".format(uniform)} " +
                "最差行带(#$worstRow)=${"%.2f".format(worst)} " +
                "各带=[${grid.joinToString(", ") { "%.1f".format(it.average()) }}]")
        }
    }

    /**
     * 空白边距必须**完全干净** —— meanD 接近 0。
     *
     * 那几块是纯色背景与片基，两端都不该在这里差出任何东西。
     * 非零就意味着某块区域被画错了位置或画错了内容 —— 这是比 meanD
     * 整体分数强得多的信号，因为它**不受重采样与字体差异的影响**。
     *
     * 实测底部两个行带：135 全部 ≤ 0.6，120 ≤ 2.0（底部是底边距，
     * 120 因行数差异有极少量内容落在其中）。
     */
    @Test
    fun `空白边距区域几乎无差异`() {
        for (case in VerifyCases.NAMES) {
            // 单张模式的画布只有胶片条本身，没有底部边距可言
            if (VerifyCases.usesOnlyFirstFrame(case)) continue

            val ref = loadReference(case) ?: continue
            val got = render(case)
            if (ref.width != got.width || ref.height != got.height) continue

            val grid = gridDiff(ref, got, 8)
            // 最后一行带是底部边距，按规格那里应该是空的
            val bottomRow = grid.last().average()
            assertTrue(
                "$case 底部边距 meanD=$bottomRow 不该非零（纯色背景，两端应完全一致）",
                bottomRow < 3.0,
            )
        }
    }

    /** 8×8 网格，每格一个列表（行优先，每行内部又是一段）。 */
    private fun gridDiff(a: Bitmap, b: Bitmap, n: Int): List<List<Double>> {        val w = a.width
        val h = a.height
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        b.getPixels(pb, 0, w, 0, 0, w, h)

        val cw = w / n
        val ch = h / n
        return (0 until n).map { gy ->
            (0 until n).map { gx ->
                var sum = 0L
                var cnt = 0
                val y0 = gy * ch
                val y1 = if (gy == n - 1) h else y0 + ch
                val x0 = gx * cw
                val x1 = if (gx == n - 1) w else x0 + cw
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        sum += kotlin.math.abs(luma(pa[y * w + x]) - luma(pb[y * w + x]))
                        cnt++
                        x++
                    }
                    y++
                }
                if (cnt == 0) 0.0 else sum.toDouble() / cnt
            }
        }
    }


    /** 差异统计。 */
    private data class Diff(
        val mean: Double,
        val p95: Int,
        val max: Int,
        /** 超过 32 的像素占比（百分比）—— 大差异区往往集中在几何错误处。 */
        val bigPct: Double,
    )

    private fun diff(
        a: Bitmap,
        b: Bitmap,
    ): Diff {
        val w = a.width
        val h = a.height
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        b.getPixels(pb, 0, w, 0, 0, w, h)

        val hist = IntArray(256)
        var sum = 0L
        for (i in pa.indices) {
            val d = luma(pa[i]) - luma(pb[i])
            val ad = if (d < 0) -d else d
            hist[ad]++
            sum += ad
        }
        val total = w * h
        val mean = sum.toDouble() / total

        var acc = 0
        var p95 = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= total * 0.95) { p95 = i; break }
        }
        val max = hist.indexOfLast { it > 0 }
        var big = 0
        for (i in 33..255) big += hist[i]
        return Diff(mean, p95, max, big * 100.0 / total)
    }

    /** Rec.601 亮度，与桌面端 `compare.py` 的 L 通道一致。 */
    private fun luma(argb: Int): Int =
        (((argb shr 16 and 0xFF) * 299 + (argb shr 8 and 0xFF) * 587 +
            (argb and 0xFF) * 114) / 1000)
}
