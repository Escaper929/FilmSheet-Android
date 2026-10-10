package com.filmsheet.android.core

/**
 * 135 胶片物理常量 — 移植自 iOS 端 `FilmEngine135.swift`。
 *
 * 权威来源是 FilmSheet-Swift 的 `SPEC.md` §2。**改这里之前先改 SPEC.md。**
 * 这些数字决定「像胶片」而不是「像柱状图」，任何改动都会移动每一个齿孔。
 *
 * 单位统一为毫米，与 SMPTE / ISO 732 一致；渲染时经 [scaleFactor] 换算成像素。
 */
object FilmEngine135 {

    // MARK: - 物理常量 (mm)

    const val FILM_WIDTH_MM = 35.0
    const val FRAME_WIDTH_MM = 36.0
    const val FRAME_HEIGHT_MM = 24.0
    const val EDGE_TO_PERF_MM = 2.01
    const val PERF_DIM_Y_MM = 2.794
    const val PERF_DIM_X_KS_MM = 1.981
    const val PERF_DIM_X_BH_MM = 1.854
    const val PERF_RADIUS_MM = 0.508
    const val BH_CURVE_DEPTH_MM = 0.35
    const val PITCH_KS_MM = 4.750
    const val PITCH_BH_MM = 4.740

    /** 齿孔类型：民用 KS 与电影 BH。 */
    enum class PerfType { KS, BH }

    /**
     * 齿孔类型判定 — `film_engine.determine_perf_type`。
     *
     * 显式选择优先；Auto 模式下按片名嗅探电影 stocks：电影胶片用 Bell & Howell，
     * 民用胶片用 Kodak Standard。匹配前先转小写，与 iOS 端一致。
     */
    fun determinePerfType(shootingInfo: String, choice: PerfMode): PerfType = when (choice) {
        PerfMode.KS -> PerfType.KS
        PerfMode.BH -> PerfType.BH
        PerfMode.AUTO -> {
            val lower = shootingInfo.lowercase()
            if (CINEMA_KEYWORDS.any { lower.contains(it) }) PerfType.BH else PerfType.KS
        }
    }

    fun pitch(type: PerfType): Double =
        if (type == PerfType.KS) PITCH_KS_MM else PITCH_BH_MM

    private val CINEMA_KEYWORDS = listOf(
        "5207", "5294", "5219", "5203",
        "vision3", "ektachrome", "motion", "cinema",
    )

    // MARK: - 子格式表

    /** 画幅宽 mm、画幅高 mm、每画幅齿孔数。 */
    data class SubFormat(val widthMM: Double, val heightMM: Double, val perfsPerFrame: Int)

    /** 与 iOS 端 `FilmEngine135.subFormatConfigs` 逐项对应。 */
    val subFormatConfigs: Map<String, SubFormat> = mapOf(
        "标准 36×24" to SubFormat(36.0, 24.0, 8),
        "半格 18×24" to SubFormat(18.0, 24.0, 4),
        "方形 24×24" to SubFormat(24.0, 24.0, 5),
        "XPan 65×24" to SubFormat(65.0, 24.0, 14),
    )

    fun subFormatOrDefault(name: String): SubFormat =
        subFormatConfigs[name] ?: subFormatConfigs.getValue(DEFAULT_SUB_FORMAT_135)

    const val DEFAULT_SUB_FORMAT_135 = "标准 36×24"
}

/**
 * 120 胶片物理常量（ISO 732）— 移植自 iOS 端 `Renderer120.swift`。
 *
 * 权威来源是 SPEC.md §3。120 **没有齿孔**，帧号印在衬纸上。
 *
 * 最容易搞错的一点：`thumbWidth` 映射到的是**画幅宽**，不是衬纸宽 ——
 * 这与 135 映射到画幅宽一致，但衬纸宽（61mm）是胶片条的**高度**。
 */
object FilmEngine120 {

    /** 衬纸宽 → 胶片条高度（软件里胶片条是横放的）。 */
    const val BACKING_WIDTH_MM = 61.0

    /** 画幅高固定 56mm，垂直居中。 */
    const val IMAGE_HEIGHT_MM = 56.0

    /** 上下边框：(61 − 56) / 2 = 2.5mm。 */
    const val IMAGE_BORDER_MM_TOP_BOTTOM = (BACKING_WIDTH_MM - IMAGE_HEIGHT_MM) / 2

    /** 子格式 → 真实画幅宽 mm（`renderers_120.IMAGE_WIDTHS_MM`）。 */
    val imageWidthsMM: Map<String, Double> = mapOf(
        "645" to 41.5,
        "66" to 56.0,
        "67" to 70.0,
        "68" to 76.5,
        "69" to 84.0,
        "612" to 112.0,
        "617" to 168.0,
        "624" to 224.0,
    )

    fun imageWidthOrDefault(subFormat: String): Double =
        imageWidthsMM[subFormat] ?: imageWidthsMM.getValue(DEFAULT_SUB_FORMAT)

    const val DEFAULT_SUB_FORMAT = "66"
}

/** 齿孔模式。命名与 iOS 端 `PerfMode` 的 rawValue 对齐。 */
enum class PerfMode(val rawValue: String) {
    AUTO("Auto"),
    KS("KS (民用)"),
    BH("BH (电影)"),
    ;

    companion object {
        fun fromRaw(v: String): PerfMode = entries.firstOrNull { it.rawValue == v } ?: AUTO
    }
}