package com.filmsheet.android.render

import androidx.compose.ui.graphics.Color

/**
 * 渲染调色板 — 移植自 iOS 端 `RenderStyle.swift`，对应桌面端
 * `utils/helpers.py` 的 `STYLE_COLORS`。
 *
 * 两套配色是刻意分开的：
 *
 * - **灯板正片**：白底。胶片条是深色，片边文字用橙色（灯箱透射光下的观感），
 *   齿孔透出白光。
 * - **接触印相**：纯黑底。这是放大机接触印相的样子，片边文字转白色。
 *
 * 颜色在此处以非泛型的 [Color] 存放，避免纯逻辑层拖入 Compose 依赖 ——
 * [com.filmsheet.android.core] 与 [com.filmsheet.android.config] 仍可纯 JVM 编译。
 */
data class Palette(
    val canvasBG: Color,
    val filmBase: Color,
    val perfFill: Color,
    val textColor: Color,
    val textShadow: Color,
    val infoTextColor: Color,
    val infoLabelColor: Color,
    val packBorder: Color,
)

/**
 * 从 0xAARRGGBB 字面量构造 [Color]。
 *
 * 用 `0xFF...` 的 Int 而不是直接写 `Color(0xFF...)`，是因为后者在 Kotlin 里
 * 字面量类型会推成 `Long`，容易在重构时静默改变颜色。
 */
private fun argb(v: Int): Color = Color(v)

private val LIGHTBOX = Palette(
    canvasBG = argb(0xFFFFFFFF.toInt()),
    // 片基是深棕偏黑，不是纯黑 —— 纯黑会让整张拼版显得像色块。
    filmBase = argb(0xFF120E0C.toInt()),
    perfFill = argb(0xFFFFFFFF.toInt()),
    textColor = argb(0xFFFF8C3C.toInt()),
    textShadow = argb(0xFF000000.toInt()),
    infoTextColor = argb(0xFF141414.toInt()),
    infoLabelColor = argb(0xFF787878.toInt()),
    packBorder = argb(0xFFC8C8C8.toInt()),
)

private val CONTACT_SHEET = Palette(
    canvasBG = argb(0xFF000000.toInt()),
    filmBase = argb(0xFF191919.toInt()),
    perfFill = argb(0xFF000000.toInt()),
    textColor = argb(0xFFEBEBEB.toInt()),
    textShadow = argb(0xFF505050.toInt()),
    infoTextColor = argb(0xFFFFFFFF.toInt()),
    infoLabelColor = argb(0xFFB4B4B4.toInt()),
    packBorder = argb(0xFF505050.toInt()),
)

/**
 * 按配置取配色。
 *
 * 与 iOS 端 `RenderStyle.palette` 一一对应，包括显示名 —— 两端的 UI 文案
 * 不该一个写「灯板正片」另一个写「正片」。
 */
fun paletteFor(style: com.filmsheet.android.config.SheetConfig.RenderStyle): Palette =
    when (style) {
        com.filmsheet.android.config.SheetConfig.RenderStyle.LIGHTBOX -> LIGHTBOX
        com.filmsheet.android.config.SheetConfig.RenderStyle.CONTACT_SHEET -> CONTACT_SHEET
    }
