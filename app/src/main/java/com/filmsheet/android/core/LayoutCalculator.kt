package com.filmsheet.android.core

import com.filmsheet.android.config.SheetConfig

/**
 * 布局算术 — 移植自 iOS 端 `SheetRenderer.calcCommonLayout` +
 * `Renderer135.computeLayout` + `Renderer120.computeLayout`。
 *
 * 权威来源是 SPEC.md §5。**这一层不碰任何图形 API**：输入配置与照片数，
 * 输出一张 [SheetLayout]。这样它能被 JVM 单测直接验证，而不用起模拟器。
 *
 * ## 为什么单独拆出来
 *
 * iOS 端的布局计算散在三个 `computeLayout()` 里，共同部分靠基类兜住。
 * 但那些方法同时读写 `Canvas` 的字体缓存、加载包装图、在方法中间改
 * `setCols`，没法单独测。这里的拆法是：把纯算术抽干净，绘制层只消费结果。
 *
 * ## 截断语义
 *
 * 全程用 [Double.toInt]（向零截断），不用 `floor()` —— 见 SPEC.md §9。
 */
object LayoutCalculator {

    // MARK: - 公共布局

    /**
     * `renderer.py::_calc_common_layout` — 两种片基共用的骨架。
     *
     * @param photoCount 照片总数，只用来决定 135 的行数
     * @param hasPack 是否存在包装图（影响信息块高度）
     * @param spacingOverride 单张模式下调用方已把间距归零。SPEC.md §1 说
     *   「实际间距 = spacing × thumbWidth / 400」，但归零必须能传下来 ——
     *   否则单张模式里画幅与齿孔之间会留下不该有的缝。
     */
    fun calcCommonLayout(
        config: SheetConfig,
        photoCount: Int,
        cols: Int,
        rows: Int,
        stripH: Int,
        bagGap: Int,
        baseScale: Double = config.thumbWidth / 400.0,
        spacingOverride: Int? = null,
    ): SheetLayout {
        val thumbW = config.thumbWidth
        val spacing = spacingOverride ?: config.spacing * thumbW / 400

        val sideMargin = (50 * baseScale).toInt()
        val topMargin = (25 * baseScale).toInt()
        val contentW = cols * thumbW + (cols + 1) * spacing
        val totalW = contentW + 2 * sideMargin + (100 * baseScale).toInt()

        val infoHeight = computeInfoHeight(config, baseScale, hasPack = false)

        val infoToFilmGap = (65 * baseScale).toInt()
        val topAreaHeight = topMargin + infoHeight + infoToFilmGap
        val topRegionHeight = topMargin + infoHeight
        // 没有信息块时把头部高度翻倍当底部边距，是为了平衡胶片条上方的空白。
        val bottomMargin = if (infoHeight == 0) {
            (topRegionHeight * 2.0).toInt()
        } else {
            (topRegionHeight * 1.6).toInt()
        }
        val totalH = (topAreaHeight + rows * stripH +
            (rows - 1) * bagGap + bottomMargin).toInt()

        return SheetLayout(
            cols = cols,
            rows = rows,
            thumbW = thumbW,
            spacing = spacing,
            baseScale = baseScale,
            sideMargin = sideMargin,
            topMargin = topMargin,
            bottomMargin = bottomMargin,
            contentW = contentW,
            totalW = totalW,
            totalH = totalH,
            bagGap = bagGap,
            stripH = stripH,
            topAreaHeight = topAreaHeight,
            infoHeight = infoHeight,
        )
    }

    /**
     * `renderer.py::_compute_info_height`。
     *
     * 高度按**实际填了的行数**算，不是固定三行 —— 只填了卷号的拼版
     * 会明显更矮，这是刻意的设计而不是偷懒。
     *
     * `thumbW > 200` 的两套公式在 `thumb_width` 被 clamp 到 ≥300 之后
     * 永远走不到下面的分支，但保留它是为了与 iOS 端逐行对齐 ——
     * 桌面端 `thumb_width` 下限更低，两端的分支条件必须一致。
     */
    fun computeInfoHeight(
        config: SheetConfig,
        baseScale: Double,
        hasPack: Boolean,
    ): Int {
        val thumbW = config.thumbWidth
        val lineHeight = scaled(thumbW, baseScale, 52.0)
        val topPad = scaled(thumbW, baseScale, 20.0)
        val bottomPad = scaled(thumbW, baseScale, 15.0)

        var ih = 0
        if (config.hasAnyInfo && config.activeInfoRows > 0) {
            ih = topPad + config.activeInfoRows * lineHeight + bottomPad
        }
        if (hasPack && ih == 0) {
            ih = scaled(thumbW, baseScale, 140.0)
        }
        return ih
    }

    /**
     * SPEC.md 反复出现的那条三分支规则。
     *
     * `thumbW > 200` 时按 `thumbWidth / 400` 缩放，否则按 `baseScale`。
     * 两分支在 `thumbWidth ≥ 300` 时结果相同，但保留分支是为了两端
     * 逐行可对照 —— 差异一旦出现就是规格漂移的信号。
     */
    private fun scaled(thumbW: Int, baseScale: Double, factor: Double): Int =
        if (thumbW > 200) (factor * thumbW / 400.0).toInt() else (factor * baseScale).toInt()

    // MARK: - 135

    /**
     * `Renderer135.computeLayout`。
     *
     * @param filmText 胶卷字段的值，用于自动判定齿孔类型（KS / BH）
     */
    fun compute135(config: SheetConfig, photoCount: Int): SheetLayout {
        val thumbW = config.thumbWidth
        var spacing = config.spacing * thumbW / 400
        var cols = config.columns
        if (config.singlePhotoMode) {
            cols = 1
            // 零间距让齿孔紧贴画面边缘。
            spacing = 0
        }

        // 首行要让出两个片头引带位，行数必须把它们算进去，
        // 否则一整卷 72 张会静默丢掉最后两张。
        val rows = if (cols == 1) 1 else RowPlan.rowsFor135(photoCount, cols)

        val sub = FilmEngine135.subFormatOrDefault(config.subFormat)
        val frameWMM = sub.widthMM
        val frameHMM = sub.heightMM
        val perfsPerFrame = sub.perfsPerFrame

        val scaleFactor = thumbW / frameWMM
        val stripH = (FilmEngine135.FILM_WIDTH_MM * scaleFactor).toInt()
        val bagGap = (50 * thumbW / 400.0).toInt()

        val perfType = FilmEngine135.determinePerfType(
            shootingInfo = config.value(SheetConfig.InfoKey.FILM),
            choice = config.perfMode,
        )

        var l = calcCommonLayout(
            config, photoCount, cols, rows, stripH, bagGap,
            spacingOverride = spacing,
        )

        var pitchPx = 0
        var numPerfs = perfsPerFrame
        if (cols == 1 || config.singlePhotoMode) {
            pitchPx = (FilmEngine135.pitch(perfType) * scaleFactor).toInt()
            if (config.subFormat == "XPan 65×24") numPerfs = 14
            l = l.copy(isSinglePhoto = true, numPerfs = numPerfs, pitchPx = pitchPx)

            // 单张且不要额外内容时只出胶片条：无信息块、无边距，
            // 画布正好是画幅宽 + 两侧各 10px。
            if (config.singlePhotoMode && !config.singlePhotoShowExtra) {
                val marginPerSide = 10
                val frameWPx = (frameWMM * scaleFactor).toInt()
                l = l.copy(
                    totalW = frameWPx + 2 * marginPerSide,
                    contentW = frameWPx,
                    sideMargin = marginPerSide,
                    topAreaHeight = 0,
                    bottomMargin = 0,
                    topMargin = 0,
                    totalH = l.stripH,
                )
            } else if (l.infoHeight == 0) {
                // 上下对称边距让胶片条视觉居中。
                val sym = (25 * l.baseScale).toInt()
                l = l.copy(
                    topMargin = sym,
                    bottomMargin = sym,
                    topAreaHeight = sym + (65 * l.baseScale).toInt(),
                )
            }
        }

        // 画幅在 35mm 片条内垂直居中。
        val frameTopOffsetPx = ((FilmEngine135.FILM_WIDTH_MM - frameHMM) / 2.0 * scaleFactor).toInt()

        return l.copy(
            frameWMM = frameWMM,
            frameHMM = frameHMM,
            perfsPerFrame = perfsPerFrame,
            scaleFactor = scaleFactor,
            perfCenterOffsetPx =
                ((FilmEngine135.EDGE_TO_PERF_MM + FilmEngine135.PERF_DIM_Y_MM / 2.0) * scaleFactor).toInt(),
            frameTopOffsetPx = frameTopOffsetPx,
            frameHPx = (frameHMM * scaleFactor).toInt(),
            frameWPx = (frameWMM * scaleFactor).toInt(),
            perfHPx = (FilmEngine135.PERF_DIM_Y_MM * scaleFactor).toInt(),
            perfWKSpx = (FilmEngine135.PERF_DIM_X_KS_MM * scaleFactor).toInt(),
            perfWBHpx = (FilmEngine135.PERF_DIM_X_BH_MM * scaleFactor).toInt(),
            perfRpx = (FilmEngine135.PERF_RADIUS_MM * scaleFactor).toInt(),
            bhCdPx = (FilmEngine135.BH_CURVE_DEPTH_MM * scaleFactor).toInt(),
            perfType = perfType,
            pitchPx = if (pitchPx != 0) pitchPx else (FilmEngine135.pitch(perfType) * scaleFactor).toInt(),
        )
    }

    // MARK: - 120

    /** `Renderer120.computeLayout`。 */
    fun compute120(config: SheetConfig, photoCount: Int): SheetLayout {
        val thumbW = config.thumbWidth
        val cols = config.columns
        val rows = RowPlan.rowsFor120(photoCount, cols)
        // 只有真正压成一条胶片条时才把间距归零（画面居中、贴边无碍）。
        // 多行单列仍需正常间距，否则画面会紧贴画布左右边缘。
        val singleStrip = cols == 1 && rows == 1
        val spacing = if (singleStrip) 0 else config.spacing * thumbW / 400

        val subFormat = config.subFormat
        val targetRatio = SheetConfig.filmFormatRatios[subFormat] ?: 1.0
        val imgWMM = FilmEngine120.imageWidthOrDefault(subFormat)

        // 注意：thumbWidth 映射到的是**画幅宽**，不是衬纸宽。
        // 衬纸宽（61mm）是胶片条的**高度**。
        val scaleFactor = thumbW / imgWMM
        val stripH = (FilmEngine120.BACKING_WIDTH_MM * scaleFactor).toInt()
        val imgH = (FilmEngine120.IMAGE_HEIGHT_MM * scaleFactor).toInt()
        val bagGap = (50 * scaleFactor).toInt()

        var l = calcCommonLayout(
            config, photoCount, cols, rows, stripH, bagGap,
            spacingOverride = spacing,
        )

        // 单列时压缩布局，与 135 的单张模式行为一致。
        //
        // 但压缩的前提是**真的只有一条胶片条**（rows == 1）。120 单列时行数
        // 等于照片数 —— 6 张照片排 6 行，若按一条的高度裁画布，后 5 行会被
        // 静默裁掉。iOS 端 `Renderer120.computeLayout` 正是缺了这个判断，
        // 它对 `cols == 1` 无条件设 `totalH = stripH`。
        //
        // 135 不受影响：`rowsFor135` 在 cols == 1 时恒返回 1，条件天然成立。
        if (cols == 1) {
            l = l.copy(isSinglePhoto = singleStrip)
            if (!config.singlePhotoShowExtra && singleStrip) {
                val marginPerSide = 10
                l = l.copy(
                    sideMargin = marginPerSide,
                    contentW = thumbW,
                    totalW = thumbW + 2 * marginPerSide,
                    topAreaHeight = 0,
                    bottomMargin = 0,
                    topMargin = 0,
                    totalH = stripH,
                )
            } else if (l.infoHeight == 0) {
                val sym = (25 * l.baseScale).toInt()
                l = l.copy(
                    topMargin = sym,
                    bottomMargin = sym,
                    topAreaHeight = sym + (65 * l.baseScale).toInt(),
                )
            }
        }

        return l.copy(
            targetRatio = targetRatio,
            imgH = imgH,
            scaleFactor = scaleFactor,
            imageBorder = (FilmEngine120.IMAGE_BORDER_MM_TOP_BOTTOM * scaleFactor).toInt(),
            // 120 的画面宽度就是 thumbW 本身；frameWPx 在这个渲染器里
            // 不参与放置，但共享的 columnCenter 会读它。
            frameWPx = thumbW,
        )
    }

    /** 按片基分派。 */
    fun compute(config: SheetConfig, photoCount: Int): SheetLayout =
        when (config.filmFormat) {
            SheetConfig.FilmFormat.F135 -> compute135(config, photoCount)
            SheetConfig.FilmFormat.F120 -> compute120(config, photoCount)
        }

    // MARK: - 包装图

    /**
     * 包装图的位置与尺寸 — `renderer.py::_draw_pack_image` 的纯算术部分。
     *
     * @param packPixelW 包装图解码后的像素宽
     * @param packPixelH 包装图解码后的像素高
     * @return 包装图矩形 + 它给文字区让出的边界；无包装图时全为 null
     */
    data class PackPlacement(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val border: Int,
        val packRight: Int?,
        val packLeft: Int?,
    )

    fun placePackImage(
        config: SheetConfig,
        layout: SheetLayout,
        packPixelW: Int,
        packPixelH: Int,
    ): PackPlacement? {
        if (layout.infoHeight <= 0 || packPixelW <= 0 || packPixelH <= 0) return null

        val baseScale = layout.baseScale
        val topBlankHeight = layout.topMargin + layout.infoHeight + (65 * baseScale).toInt()
        // 上限随拼版缩放，包装图不会在大图上喧宾夺主。
        val maxPackH = (368 * baseScale).toInt()
        var packH = minOf((topBlankHeight * config.packSize / 100.0).toInt(), maxPackH)
        var packW = (packH * packPixelW / packPixelH.toDouble()).toInt()

        val maxAllowW = (layout.totalW * 0.35).toInt()
        if (packW > maxAllowW) {
            packW = maxAllowW
            packH = (packW * packPixelH / packPixelW.toDouble()).toInt()
        }
        if (packW <= 0 || packH <= 0) return null

        val packY = (topBlankHeight - packH) / 2
        val border = if (config.packBorderStroke) maxOf(2, (2 * baseScale).toInt()) else 0

        return if (config.packPosition == SheetConfig.PackPosition.LEFT) {
            val packX = layout.sideMargin
            PackPlacement(packX, packY, packW, packH, border, packRight = packX + packW + 12, packLeft = null)
        } else {
            val packX = layout.totalW - layout.sideMargin - packW
            PackPlacement(packX, packY, packW, packH, border, packRight = null, packLeft = packX - 12)
        }
    }
}
