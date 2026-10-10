package com.filmsheet.android.render

import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.core.EdgeText
import com.filmsheet.android.core.EdgeTextGenerator
import com.filmsheet.android.core.FilmEngine135
import com.filmsheet.android.core.LayoutCalculator
import com.filmsheet.android.core.RowPlan
import com.filmsheet.android.core.SheetLayout

/**
 * 渲染器基类 — 移植自 iOS 端 `SheetRenderer.swift`（桌面端 `renderer.py` 的
 * `BaseRenderer`）。
 *
 * ## 渲染顺序不可调换
 *
 * ```
 * 包装图 → 信息块 → 胶片条（片基 → 装饰 → 画面）→ 水印
 * ```
 *
 * 包装图先画，是因为信息块要按它剩下的空间来排文字 —— 顺序反了，
 * 信息块会按整幅宽度测量，压到包装图底下。
 *
 * ## 图片接口是泛型的
 *
 * 图片由调用方以 [ImageBitmap] 形式提供，渲染器不做解码。这样单元测试可以
 * 塞任意位图进来，而生产代码的解码走 `androidx.exifinterface` + `BitmapFactory`
 * （见 ImageOps 等价物）。渲染器只管「把这张图放到这个矩形里」。
 *
 * @param images 已按 EXIF 校正方向的照片，顺序即帧号顺序
 * @param packImage 已解码的包装图；null 表示本张拼版不带包装图。
 *   由调用方从 `config.packImageUri` 解出，渲染器不碰 ContentResolver ——
 *   这样它能被纯 JVM 测试直接构造。
 */
abstract class SheetRenderer(
    val config: SheetConfig,
    val images: List<ImageBitmap>,
    private val packImage: ImageBitmap? = null,
) {
    val colors: Palette = paletteFor(config.renderStyle)

    /** 行计划，构造时算一次。 */
    private var rowPlan: RowPlan = emptyPlan()

    /** 齿孔类型，构造时定一次。 */
    val perfType: FilmEngine135.PerfType by lazy {
        FilmEngine135.determinePerfType(
            shootingInfo = config.value(SheetConfig.InfoKey.FILM),
            choice = config.perfMode,
        )
    }

    /** 片边文字，构造时定一次。 */
    val edgeText: EdgeText by lazy {
        EdgeTextGenerator.generate(
            infoFilm = config.value(SheetConfig.InfoKey.FILM),
            customEdgeText = config.edgeText,
        )
    }

    private fun emptyPlan(): RowPlan =
        if (config.filmFormat == SheetConfig.FilmFormat.F135) {
            RowPlan.for135(0, config.columns.coerceAtLeast(1))
        } else {
            RowPlan.for120(0, config.columns.coerceAtLeast(1))
        }

    // MARK: - 子类入口

    /** 算布局。135 / 120 各自的胶片条几何差异全在这里。 */
    protected abstract fun computeLayout(): SheetLayout

    /** 格式特有的片条装饰：135 画齿孔，120 画衬纸上的帧号。 */
    protected abstract fun drawStripDecoration(
        canvas: SheetCanvas,
        layout: SheetLayout,
        row: Int,
        y1: Int,
        y2: Int,
        imgIdx: Int,
    )

    /** 格式特有的照片放置。 */
    protected abstract fun placeImages(
        canvas: SheetCanvas,
        layout: SheetLayout,
        row: Int,
        y1: Int,
        imgIdx: Int,
    )

    /**
     * 单张模式下是否还要画包装图 / 信息块 / 水印。
     *
     * 取布局而不是配置，是因为判断依赖**解析后的列数** —— 单张模式会强制
     * 把列数改成 1，而这个只有算完布局才知道。
     */
    protected open fun drawsExtraChrome(layout: SheetLayout): Boolean = true

    // MARK: - 主流程

    fun render(): ImageBitmap {
        val layout = computeLayout()
        rowPlan = buildRowPlan(layout.cols)

        val canvas = SheetCanvas(layout.totalW, layout.totalH, colors.canvasBG)

        if (drawsExtraChrome(layout)) {
            drawPackImage(canvas, layout)
            drawInfoBlock(canvas, layout)
        }

        drawStrips(canvas, layout)

        if (drawsExtraChrome(layout)) {
            drawWatermark(canvas, layout)
        }
        return canvas.toImageBitmap()
    }

    /**
     * 按当前片基建行计划。
     *
     * 用已解析的列数而不是 `config.columns` —— 单张模式会把列数强制成 1，
     * 而行推进的守卫依赖它。
     */
    private fun buildRowPlan(resolvedCols: Int): RowPlan {
        val cols = resolvedCols.coerceAtLeast(1)
        val n = images.size
        return if (config.filmFormat == SheetConfig.FilmFormat.F135) {
            RowPlan.for135(n, cols)
        } else {
            RowPlan.for120(n, cols)
        }
    }

    private fun firstImageIndex(row: Int): Int = rowPlan.firstImageIndex(row)

    private fun countImagesInRow(row: Int): Int = rowPlan.countInRow(row)

    /** 行首列。135 首行让出两个片头引带位；120 恒为 0（无打孔引带）。 */
    protected open fun firstColumnFor(row: Int): Int =
        if (config.filmFormat == SheetConfig.FilmFormat.F120) {
            0
        } else {
            (if (row == 0 && currentCols > 1) 2 else 0)
        }

    private var currentCols: Int = 1

    // MARK: - 信息块

    /**
     * `renderer.py::_compute_info_height` → 由 [LayoutCalculator] 承担，
     * 这里不再重复实现（SPEC.md §5.2）。
     */

    /**
     * 信息块 — 桌面端 `film_processor._draw_info_block`。
     *
     * 三行三列。列宽取该列最宽的「标签+值」，超出可用宽度时**按比例**压缩
     * —— 包装图占位时必然发生。
     */
    protected fun drawInfoBlock(canvas: SheetCanvas, layout: SheetLayout) {
        if (layout.infoHeight <= 0) return

        val thumbW = layout.thumbW
        val baseScale = layout.baseScale
        val fontSize = (34 * thumbW / 400.0).toInt()
        if (fontSize <= 0) return
        val font = canvas.font(fontSize)
        val lang = config.infoLang

        var textLeft = layout.sideMargin
        var textRight = layout.totalW - layout.sideMargin
        layout.packRight?.let { textLeft = maxOf(textLeft, it) }
        layout.packLeft?.let { textRight = minOf(textRight, it) }
        if (textRight <= textLeft) return

        val colGap = if (thumbW > 200) {
            (40 * thumbW / 400.0).toInt()
        } else {
            (40 * baseScale).toInt()
        }
        val numCols = SheetConfig.InfoLayout.rows.maxOf { it.size }
        val slotWidths = IntArray(numCols)

        // 第一趟：量每个槽的宽度。
        for (row in SheetConfig.InfoLayout.rows) {
            row.forEachIndexed { colIdx, key ->
                if (key == null) return@forEachIndexed
                val full = infoTextFor(key, config.value(key))
                slotWidths[colIdx] = maxOf(
                    slotWidths[colIdx],
                    canvas.textWidth(full, font, fontSize) + colGap,
                )
            }
        }

        val totalSlotW = slotWidths.sum()
        val available = textRight - textLeft
        if (totalSlotW > available && totalSlotW > 0) {
            val f = available.toDouble() / totalSlotW
            for (i in slotWidths.indices) slotWidths[i] = (slotWidths[i] * f).toInt()
        }

        // 第二趟：画。
        val lineHeight = if (thumbW > 200) {
            (52 * thumbW / 400.0).toInt()
        } else {
            (52 * baseScale).toInt()
        }
        val topPadding = if (thumbW > 200) {
            (20 * thumbW / 400.0).toInt()
        } else {
            (20 * baseScale).toInt()
        }
        val rollGap = if (thumbW > 200) (8 * thumbW / 400.0).toInt() else (8 * baseScale).toInt()

        var renderedRow = 0
        for (row in SheetConfig.InfoLayout.rows) {
            val hasValue = row.filterNotNull().any { config.value(it).isNotEmpty() }
            if (!hasValue) continue

            val absY = layout.topMargin + topPadding + renderedRow * lineHeight
            var absX = textLeft

            row.forEachIndexed { colIdx, key ->
                if (key == null) {
                    // 空槽仍占横向空间（SPEC.md §5.4）。
                    absX += slotWidths[colIdx]
                    return@forEachIndexed
                }
                val value = config.value(key)
                val labelStr = if (key.usesColon) "${key.label(lang)}: " else key.label(lang)
                canvas.drawText(
                    labelStr, absX, absY, font, fontSize, colors.infoLabelColor,
                )
                if (value.isNotEmpty()) {
                    var valX = absX + canvas.textWidth(labelStr, font, fontSize)
                    // 卷号用一小段空白代替冒号。
                    if (!key.usesColon) valX += rollGap
                    canvas.drawText(
                        value, valX, absY, font, fontSize, colors.infoTextColor,
                    )
                }
                absX += slotWidths[colIdx]
            }
            renderedRow++
        }
    }

    /** 槽内的完整文本。空值时标签不带冒号，避免留下「Film: 」这种空标签。 */
    private fun infoTextFor(key: SheetConfig.InfoKey, value: String): String {
        val label = key.label(config.infoLang)
        return if (key.usesColon) {
            if (value.isEmpty()) "$label: " else "$label: $value"
        } else {
            if (value.isEmpty()) label else "$label $value"
        }
    }

    // MARK: - 包装图

    /** 包装图 — 桌面端 `renderer.py::_draw_pack_image`。 */
    protected fun drawPackImage(canvas: SheetCanvas, layout: SheetLayout) {
        val pack = packImage ?: return
        val place = LayoutCalculator.placePackImage(
            config, layout, pack.width, pack.height,
        ) ?: return

        canvas.drawImage(pack, place.x, place.y, place.width, place.height)

        if (place.border > 0) {
            val b = place.border
            canvas.stroke(
                place.x - b, place.y - b,
                place.width + 2 * b, place.height + 2 * b,
                colors.packBorder, b,
            )
        }
    }

    // MARK: - 胶片条

    /**
     * 桌面端 `renderer.py::_draw_strips`。
     *
     * 每行先铺片基，再画装饰，最后放照片。帧计数器按该行**实际放了几张**推进，
     * 不是固定按 `cols` —— 首行让出两个引带位，按 cols 算会让一整卷 72 张
     * 静默丢掉最后两张（SPEC.md §4.2）。
     */
    protected fun drawStrips(canvas: SheetCanvas, layout: SheetLayout) {
        if (layout.rows <= 0) return
        for (row in 0 until layout.rows) {
            val y1 = layout.rowTop(row)
            val y2 = layout.rowBottom(row)
            canvas.fill(0, y1, layout.totalW, layout.stripH, colors.filmBase)

            val imgIdx = firstImageIndex(row)
            drawStripDecoration(canvas, layout, row, y1, y2, imgIdx)
            placeImages(canvas, layout, row, y1, imgIdx)
        }
    }

    // MARK: - 帧号与分隔三角

    /**
     * 桌面端 `film_processor._draw_triangle`。
     *
     * 30° 顶角的左向等腰三角 —— 真实胶片上分隔帧号的那个「播放」标记。
     */
    protected fun drawTriangle(
        canvas: SheetCanvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: androidx.compose.ui.graphics.Color,
    ) {
        val halfAngle = 15.0 * Math.PI / 180.0
        val triW = (size * kotlin.math.cos(halfAngle)).toFloat()
        val triH = (size * kotlin.math.sin(halfAngle)).toFloat()
        canvas.fillPolygon(
            listOf(
                cx - triW to cy,
                (cx + triW * 0.1f) to (cy - triH),
                (cx + triW * 0.1f) to (cy + triH),
            ),
            color,
        )
    }

    /** 帧号 + 其后的三角，居中对齐在 `cx`。 */
    protected fun drawFrameNumber(
        canvas: SheetCanvas,
        cx: Float,
        y: Float,
        number: Int,
        font: Typeface,
        fontSize: Int,
        color: androidx.compose.ui.graphics.Color,
    ) {
        val s = number.toString()
        val nw = canvas.textWidth(s, font, fontSize)
        val ix = (cx - nw / 2f).toInt()
        val iy = y.toInt()
        canvas.drawText(s, ix, iy, font, fontSize, color, TextAnchor.MIDDLE)
        drawTriangle(
            canvas, ix + nw + 25f, y, fontSize * 0.7f, color,
        )
    }

    // MARK: - 水印

    /**
     * 桌面端 `renderer.py::_draw_watermark`。
     *
     * 阴影偏移一个像素，这样它在白色灯板底与黑色接触印相底上都还看得清。
     */
    protected fun drawWatermark(canvas: SheetCanvas, layout: SheetLayout) {
        val sig = config.signature.trim()
        if (sig.isEmpty()) return

        val fontSize = (18 * layout.thumbW / 400.0).toInt()
        if (fontSize <= 0) return
        val font = canvas.font(fontSize)
        val margin = (30 * layout.baseScale).toInt()
        val textW = canvas.textWidth(sig, font, fontSize)

        var x = layout.totalW - margin - textW
        var y = layout.totalH - margin - fontSize
        if (x < margin) x = margin
        if (y < margin) y = margin

        canvas.drawText(sig, x + 1, y + 1, font, fontSize, colors.textShadow)
        canvas.drawText(sig, x, y, font, fontSize, colors.textColor)
    }
}
