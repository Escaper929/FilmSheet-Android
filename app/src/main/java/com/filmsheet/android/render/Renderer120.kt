package com.filmsheet.android.render

import androidx.compose.ui.graphics.ImageBitmap
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.core.FilmEngine120
import com.filmsheet.android.core.LayoutCalculator
import com.filmsheet.android.core.SheetLayout

/**
 * 120 胶片条渲染器 — 移植自 iOS 端 `Renderer120.swift`
 * （桌面端 `processor/renderers_120.py`）。
 *
 * 按 ISO 732 的物理模型：
 * ```
 * 衬纸宽 61mm  → 胶片条高度（软件里胶片条是横放的）
 * 画幅高 56mm  → 固定，垂直居中
 * 画幅宽       → 随子格式变化，thumb_width 映射到的是**这个**
 * 上下边框 2.5mm → (61 − 56) / 2
 * ```
 *
 * **没有齿孔**：120 的帧号印在衬纸上，片边文字就坐在那条衬纸里。
 */
class Renderer120(
    config: SheetConfig,
    images: List<ImageBitmap>,
    packImage: ImageBitmap? = null,
) : SheetRenderer(config, images, packImage) {

    override fun computeLayout(): SheetLayout =
        LayoutCalculator.compute120(config, images.size)

    /**
     * 对应桌面端 `renderers_120.render()`：单列时去掉包装图 / 信息块 / 水印，
     * 除非用户明确要求。
     *
     * 注意 120 这里看的是**解析后的列数**，而不只是 `singlePhotoMode` 标志 ——
     * 桌面端就是这个行为（SPEC.md §8 第 2 条）。
     */
    override fun drawsExtraChrome(layout: SheetLayout): Boolean =
        layout.cols != 1 || config.singlePhotoShowExtra

    /**
     * 120 没有打孔引带，每行都从第 0 列开始。
     *
     * 共享的默认假设 135 的两格引带 —— 沿用它会让第 1 行重复第 0 行的照片。
     * 这正是 SPEC.md §8 第 2 条修掉的桌面端 bug。
     */
    override fun firstColumnFor(row: Int, cols: Int): Int = 0

    // MARK: - 衬纸上的帧号

    override fun drawStripDecoration(
        canvas: SheetCanvas,
        layout: SheetLayout,
        row: Int,
        y1: Int,
        y2: Int,
        imgIdx: Int,
    ) {
        val fontSize = (16 * layout.thumbW / 400.0 * 0.85).toInt()
        if (fontSize <= 0) return
        val font = canvas.font(fontSize)

        // 文字坐在 2.5mm 的衬纸边里，往里挪 60% —— 衬纸带很窄，
        // 文字不能碰到画面。
        val borderMid =
            (FilmEngine120.IMAGE_BORDER_MM_TOP_BOTTOM * layout.scaleFactor * 0.6).toInt()
        val xCenter = if (layout.cols == 1) {
            layout.sideMargin + layout.contentW / 2
        } else {
            layout.totalW / 2
        }
        canvas.drawText(
            edgeText.topLine, xCenter, y1 + borderMid,
            font, fontSize, colors.textColor, TextAnchor.MIDDLE,
        )

        // 下衬纸：帧号对齐到各画面中心。
        val bottomY = y2 - borderMid
        val centers = mutableListOf<Pair<Float, Int>>()
        var cur = imgIdx
        if (layout.cols > 0) {
            for (col in 0 until layout.cols) {
                if (cur >= images.size) break
                centers += layout.columnCenter(col, layout.thumbW).toFloat() to (cur + 1)
                cur++
            }
        }

        for ((cx, num) in centers) {
            drawFrameNumber(canvas, cx, bottomY.toFloat(), num, font, fontSize, colors.textColor)
        }
        for (i in 0 until maxOf(0, centers.size - 1)) {
            drawTriangle(
                canvas,
                (centers[i].first + centers[i + 1].first) / 2f,
                bottomY.toFloat(),
                fontSize * 0.6f,
                colors.textColor,
            )
        }
    }

    // MARK: - 照片放置

    override fun placeImages(
        canvas: SheetCanvas,
        layout: SheetLayout,
        row: Int,
        y1: Int,
        imgIdx: Int,
    ) {
        val frameW = layout.thumbW
        val imgH = layout.imgH
        val yTop = y1 + layout.imageBorder

        if (layout.cols == 1) {
            if (imgIdx >= images.size) return
            val x = ((layout.contentW - frameW) / 2.0 + layout.sideMargin).toInt()
            canvas.drawImage(images[imgIdx], x, yTop, frameW, imgH)
            return
        }

        var idx = imgIdx
        for (col in 0 until layout.cols) {
            if (idx >= images.size) break
            val x = layout.sideMargin + layout.spacing + col * (frameW + layout.spacing)
            canvas.drawImage(images[idx], x, yTop, frameW, imgH)
            idx++
        }
    }
}
