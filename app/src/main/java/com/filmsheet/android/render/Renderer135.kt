package com.filmsheet.android.render

import androidx.compose.ui.graphics.ImageBitmap
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.core.FilmEngine135
import com.filmsheet.android.core.LayoutCalculator
import com.filmsheet.android.core.SeededRNG
import com.filmsheet.android.core.SheetLayout
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 135 胶片条渲染器 — 移植自 iOS 端 `Renderer135.swift`
 * （桌面端 `processor/renderers_135.py`）。
 *
 * 物理精确层：齿孔间距来自胶片引擎，画幅坐在真实 35mm 片宽上，
 * 片边印着品牌与帧号 —— 与真实实验室印片一致。
 */
class Renderer135(
    config: SheetConfig,
    images: List<ImageBitmap>,
    packImage: ImageBitmap? = null,
) : SheetRenderer(config, images, packImage) {

    private var resolvedCols: Int = config.columns

    override fun computeLayout(): SheetLayout {
        val l = LayoutCalculator.compute135(config, images.size)
        resolvedCols = l.cols
        return l
    }

    /** 单张模式隐藏包装图 / 信息块 / 水印，除非用户明确要求。 */
    override fun drawsExtraChrome(layout: SheetLayout): Boolean =
        !config.singlePhotoMode || config.singlePhotoShowExtra

    // MARK: - 齿孔

    private fun drawPerforations(canvas: SheetCanvas, layout: SheetLayout, y1: Int, y2: Int) {
        val pitch = layout.pitchPx
        val perfH = layout.perfHPx
        if (pitch <= 0 || perfH <= 0) return

        val centers: List<Float>
        if (layout.isSinglePhoto) {
            // 把 numPerfs 个齿孔均匀铺在内容宽度上，两侧留等量的空 ——
            // 这样最外两个齿孔与画面边缘的间距相等。
            val contentWidth = layout.contentW.toFloat()
            val totalSpan = (layout.numPerfs - 1) * pitch.toFloat()
            val gapLeft = ((contentWidth - totalSpan) / 2f).roundToInt()
            val base = layout.sideMargin + gapLeft
            centers = (0 until max(0, layout.numPerfs)).map { base + it * pitch.toFloat() }
        } else {
            val gap = 25
            val xs = mutableListOf<Float>()
            var x = gap
            while (x < layout.totalW - gap) {
                xs += x.toFloat()
                x += pitch
            }
            centers = xs
        }

        val ys = listOf(
            (y1 + layout.perfCenterOffsetPx).toFloat(),
            (y2 - layout.perfCenterOffsetPx).toFloat(),
        )

        for (x in centers) {
            for (cy in ys) {
                if (layout.perfType == FilmEngine135.PerfType.KS) {
                    // KS：一个普通圆角矩形。
                    val w = layout.perfWKSpx
                    canvas.fillRounded(
                        (x - w / 2f).toInt(), (cy - perfH / 2f).toInt(),
                        w, perfH, layout.perfRpx, colors.perfFill,
                    )
                } else {
                    // BH：矩形本体加两个椭圆端帽 —— Bell & Howell 齿孔
                    // 标志性的「束腰」外形。
                    val w = layout.perfWBHpx
                    val cd = layout.bhCdPx
                    val left = (x - w / 2f).toInt()
                    val top = (cy - perfH / 2f).toInt()
                    canvas.fill(left, top + cd, w, perfH - cd * 2, colors.perfFill)
                    canvas.fillEllipse(left, top, w, cd * 2, colors.perfFill)
                    canvas.fillEllipse(
                        left, top + perfH - cd * 2, w, cd * 2, colors.perfFill,
                    )
                }
            }
        }
    }

    // MARK: - 片边文字

    /**
     * 片边字体尺寸。
     *
     * 桌面端把它缩到标称值（每 400px 缩略图 16px）的 85%，因为文字要塞进
     * 片基里。照抄这个系数。
     */
    private fun edgeFontSize(layout: SheetLayout): Int =
        (16 * layout.thumbW / 400.0 * 0.85).toInt()

    override fun drawStripDecoration(
        canvas: SheetCanvas,
        layout: SheetLayout,
        row: Int,
        y1: Int,
        y2: Int,
        imgIdx: Int,
    ) {
        drawPerforations(canvas, layout, y1, y2)
        if (layout.isSinglePhoto) {
            drawSinglePhotoEdge(canvas, layout, y1, y2, row, imgIdx)
        } else {
            drawMultiPhotoEdge(canvas, layout, y1, y2, row, imgIdx)
        }
    }

    private fun drawMultiPhotoEdge(
        canvas: SheetCanvas,
        layout: SheetLayout,
        y1: Int,
        y2: Int,
        row: Int,
        imgIdx: Int,
    ) {
        val fontSize = edgeFontSize(layout)
        if (fontSize <= 0) return
        val font = canvas.font(fontSize)

        val margin = (40 * layout.baseScale).toInt()
        val totalW = layout.totalW.toFloat()
        val offsetRange = (0.08 * totalW).toInt()

        // 品牌沿上片边**散开**排 —— 真实胶片是不规则间隔反复印的，
        // 不会排成整齐的网格。
        val rng = SeededRNG(seed = edgeSeed(row, imgIdx))
        val baseRatios = when (rng.next(2, 3, 4)) {
            2 -> listOf(0.25f, 0.75f)
            3 -> listOf(0.18f, 0.50f, 0.82f)
            else -> listOf(0.12f, 0.38f, 0.62f, 0.88f)
        }

        val xs = baseRatios.map { r ->
            val baseX = margin + r * (totalW - margin * 2f)
            val offset = rng.int(-offsetRange..offsetRange)
            max(margin.toFloat(), minOf(totalW - margin, baseX + offset))
        }.sorted()

        val edgeYOffset = (0.9 * layout.scaleFactor).toInt()
        val topLine = edgeText.topLine
        for (x in xs) {
            canvas.drawText(
                topLine, x.toInt(), y1 + edgeYOffset,
                font, fontSize, colors.textColor, TextAnchor.MIDDLE,
            )
        }

        // 下片边：帧号对齐到各画面中心，相邻帧之间放三角。
        val bottomY = y2 - edgeYOffset
        val startCol = firstColumnFor(row)
        val centers = mutableListOf<Pair<Float, Int>>()
        var cur = imgIdx
        for (col in startCol until maxOf(startCol, layout.cols)) {
            if (cur >= images.size) break
            centers += layout.columnCenter(col, layout.frameWPx).toFloat() to (cur + 1)
            cur++
        }

        for ((cx, num) in centers) {
            drawFrameNumber(canvas, cx, bottomY.toFloat(), num, font, fontSize, colors.textColor)
        }
        for (i in 0 until max(0, centers.size - 1)) {
            drawTriangle(
                canvas,
                (centers[i].first + centers[i + 1].first) / 2f,
                bottomY.toFloat(),
                fontSize * 0.6f,
                colors.textColor,
            )
        }
    }

    private fun drawSinglePhotoEdge(
        canvas: SheetCanvas,
        layout: SheetLayout,
        y1: Int,
        y2: Int,
        row: Int,
        imgIdx: Int,
    ) {
        val fontSize = edgeFontSize(layout)
        if (fontSize <= 0) return
        val font = canvas.font(fontSize)
        val edgeYOffset = (0.9 * layout.scaleFactor).toInt()

        // 品牌居中放在画面上方，而不是散排。
        val topLine = edgeText.topLine
        if (topLine.isNotEmpty()) {
            val smallSize = maxOf(1, (fontSize * 0.9).toInt())
            canvas.drawText(
                topLine, layout.totalW / 2, y1 + edgeYOffset,
                font, smallSize, colors.textColor, TextAnchor.MIDDLE,
            )
        }

        // 下方的帧号。单张没有逐格序号，用实验室编号 1–40 顶上 ——
        // 桌面端也是这么做的。
        val bottomY = y2 - edgeYOffset
        val cx = if (layout.cols == 1) {
            layout.totalW / 2f
        } else {
            layout.columnCenter(0, layout.frameWPx).toFloat()
        }

        val rng = SeededRNG(seed = edgeSeed(row, imgIdx))
        drawFrameNumber(
            canvas, cx, bottomY.toFloat(), rng.int(1..40),
            font, fontSize, colors.textColor,
        )
    }

    private fun edgeSeed(row: Int, imgIdx: Int): Long =
        System.currentTimeMillis() / 1000 xor (row * 7919 + imgIdx).toLong()

    // MARK: - 照片放置

    override fun placeImages(
        canvas: SheetCanvas,
        layout: SheetLayout,
        row: Int,
        y1: Int,
        imgIdx: Int,
    ) {
        val frameW = layout.frameWPx
        val frameH = layout.frameHPx
        val yTop = y1 + layout.frameTopOffsetPx

        if (layout.cols == 1) {
            // 画面在片条上居中，使最外侧齿孔到画面两边的间距相等。
            if (imgIdx >= images.size) return
            val x = ((layout.totalW - frameW) / 2.0).toInt()
            canvas.drawImage(images[imgIdx], x, yTop, frameW, frameH)
            return
        }

        var idx = imgIdx
        val startCol = firstColumnFor(row)
        if (startCol >= layout.cols) return
        for (col in startCol until layout.cols) {
            if (idx >= images.size) break
            val x = layout.sideMargin + layout.spacing +
                col * (frameW + layout.spacing)
            canvas.drawImage(images[idx], x, yTop, frameW, frameH)
            idx++
        }
    }
}
