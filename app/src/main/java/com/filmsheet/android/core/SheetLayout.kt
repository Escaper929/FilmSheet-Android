package com.filmsheet.android.core

/**
 * 布局结果 — 移植自 iOS 端 `Layout.swift`，是渲染器的参数袋。
 *
 * 权威来源是 SPEC.md §5。所有字段都是**画布像素**，不是毫米 ——
 * 毫米常量在 [FilmEngine135] / [FilmEngine120] 里，换算只发生在
 * [LayoutCalculator] 一处。
 *
 * 用 data class 而不是 `Map<String, Any>`：Android 上打错一个键应该编译不过，
 * 而不是安静地少一个字段、渲染出一个错位的版式。
 */
data class SheetLayout(

    // MARK: 网格

    val cols: Int = 0,
    val rows: Int = 0,
    val thumbW: Int = 0,
    val spacing: Int = 0,

    /** `thumbWidth / 400.0` — 一切「× scale」常数的基准。 */
    val baseScale: Double = 1.0,

    // MARK: 边距与整体尺寸

    val sideMargin: Int = 0,
    val topMargin: Int = 0,
    val bottomMargin: Int = 0,
    val contentW: Int = 0,
    val totalW: Int = 0,
    val totalH: Int = 0,
    val bagGap: Int = 0,
    val stripH: Int = 0,
    val topAreaHeight: Int = 0,

    // MARK: 信息块

    val infoHeight: Int = 0,

    // MARK: 包装图夹逼出的文字边界

    /** 左侧包装图的右边缘 + 间隙；文字左界不得小于它。 */
    val packRight: Int? = null,
    /** 右侧包装图的左边缘 − 间隙；文字右界不得大于它。 */
    val packLeft: Int? = null,

    // MARK: 135 几何

    val frameWMM: Double = 36.0,
    val frameHMM: Double = 24.0,
    val perfsPerFrame: Int = 8,
    /** 毫米 → 像素的换算系数 = `thumbW / frameWMM`。 */
    val scaleFactor: Double = 1.0,
    /** 齿孔中心到片边的距离：`edgeToPerf + perfDimY/2`。 */
    val perfCenterOffsetPx: Int = 0,
    /** 画幅在 35mm 片条内的垂直居中偏移。 */
    val frameTopOffsetPx: Int = 0,
    val frameHPx: Int = 0,
    val frameWPx: Int = 0,
    val perfHPx: Int = 0,
    val perfWKSpx: Int = 0,
    val perfWBHpx: Int = 0,
    val perfRpx: Int = 0,
    val bhCdPx: Int = 0,
    val perfType: FilmEngine135.PerfType = FilmEngine135.PerfType.KS,
    val pitchPx: Int = 0,
    val isSinglePhoto: Boolean = false,
    val numPerfs: Int = 8,

    // MARK: 120 几何

    val targetRatio: Double = 1.0,
    val imgH: Int = 0,
    val imageBorder: Int = 0,
) {

    // MARK: 派生量

    /** 某行胶片条的顶边 y。 */
    fun rowTop(row: Int): Int = topAreaHeight + row * (stripH + bagGap)

    /** 某行胶片条的底边 y。 */
    fun rowBottom(row: Int): Int = rowTop(row) + stripH

    /**
     * 某一列里画面的中心 x — `renderer.py::column_center`。
     *
     * 注意用的是**实际画幅宽** `frameW` 而不是 `thumbW`：135 的画幅宽由
     * 子格式决定（36 / 18 / 24 / 65mm），而 120 直接传 `thumbW`。
     * 片边文字的帧号就对着这个中心画，错了就会与照片错位。
     */
    fun columnCenter(col: Int, frameW: Int): Double =
        (sideMargin + spacing + col * (frameW + spacing)) + frameW / 2.0
}
