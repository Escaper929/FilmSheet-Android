package com.filmsheet.android.verify

import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.core.PerfMode

/**
 * 对照用例表 — **必须与桌面端 `Verification/render_reference.py` 的 CASES
 * 逐项一致**。
 *
 * 两端各存一份是刻意的：渲染引擎本来就不共享代码，用例表同理。改一处
 * 忘了另一处，对照出来的差异就没有意义了 —— 因为已经不知道在比什么。
 * 所以两份都要改，且改完要重新生成参考图。
 *
 * 7 个用例刻意覆盖每一处分叉：
 *
 * | 用例 | 覆盖 |
 * |---|---|
 * | `135_light_info` | 信息块三行、片头引带、KS 齿孔、签名水印 |
 * | `135_dark_plain` | 接触印相配色、无信息块时的底边距 |
 * | `135_bh_perf` | BH 齿孔（束腰外形）、非默认缩略图宽度、5 列 |
 * | `135_half` | 半格 18×24（竖幅，转向规则相反） |
 * | `120_66` | 120 衬纸、无齿孔 |
 * | `120_617_wide` | 120 宽幅 + 接触印相组合 |
 * | `135_single` | 单张模式（只画一条片） |
 */
object VerifyCases {

    val NAMES: List<String> = listOf(
        "135_light_info",
        "135_dark_plain",
        "135_bh_perf",
        "135_half",
        "120_66",
        "120_617_wide",
        "135_single",
    )

    /** 单张模式只用第一张图，与桌面端 `files[:1]` 一致。 */
    fun usesOnlyFirstFrame(case: String): Boolean = case == "135_single"

    fun configFor(case: String): SheetConfig = when (case) {
        "135_light_info" -> SheetConfig(
            filmFormat = SheetConfig.FilmFormat.F135,
            subFormat = "标准 36×24",
            renderStyle = SheetConfig.RenderStyle.LIGHTBOX,
            thumbWidth = 400, columns = 4, spacing = 20,
            info = mapOf(
                SheetConfig.InfoKey.ROLL to "12",
                SheetConfig.InfoKey.CAMERA to "Nikon F3",
                SheetConfig.InfoKey.FILM to "柯达 Portra 400",
                SheetConfig.InfoKey.SHOOT_DATE to "2026-03-14",
                SheetConfig.InfoKey.LAB to "诺丁山",
            ),
            signature = "FilmSheet",
        )

        "135_dark_plain" -> SheetConfig(
            filmFormat = SheetConfig.FilmFormat.F135,
            subFormat = "标准 36×24",
            renderStyle = SheetConfig.RenderStyle.CONTACT_SHEET,
            thumbWidth = 400, columns = 3, spacing = 30,
        )

        // BH 齿孔：片边印字会变成 EASTMAN，且齿孔是束腰外形
        "135_bh_perf" -> SheetConfig(
            filmFormat = SheetConfig.FilmFormat.F135,
            subFormat = "标准 36×24",
            renderStyle = SheetConfig.RenderStyle.LIGHTBOX,
            thumbWidth = 500, columns = 5, spacing = 15,
            perfMode = PerfMode.BH,
            info = mapOf(
                SheetConfig.InfoKey.FILM to "柯达 Vision3 500T",
                SheetConfig.InfoKey.ROLL to "A007",
            ),
        )

        // 半格是竖幅 18×24，转向规则与标准 36×24 相反
        "135_half" -> SheetConfig(
            filmFormat = SheetConfig.FilmFormat.F135,
            subFormat = "半格 18×24",
            renderStyle = SheetConfig.RenderStyle.LIGHTBOX,
            thumbWidth = 400, columns = 4, spacing = 20,
            info = mapOf(
                SheetConfig.InfoKey.CAMERA to "Leica M6",
                SheetConfig.InfoKey.FILM to "富士 Provia 100F",
            ),
        )

        "120_66" -> SheetConfig(
            filmFormat = SheetConfig.FilmFormat.F120,
            subFormat = "66",
            renderStyle = SheetConfig.RenderStyle.LIGHTBOX,
            thumbWidth = 400, columns = 3, spacing = 25,
            info = mapOf(
                SheetConfig.InfoKey.ROLL to "R-07",
                SheetConfig.InfoKey.FILM to "富士 Velvia 50",
            ),
        )

        "120_617_wide" -> SheetConfig(
            filmFormat = SheetConfig.FilmFormat.F120,
            subFormat = "617",
            renderStyle = SheetConfig.RenderStyle.CONTACT_SHEET,
            thumbWidth = 600, columns = 2, spacing = 20,
            info = mapOf(
                SheetConfig.InfoKey.FILM to "柯达 Tri-X 400",
                SheetConfig.InfoKey.SCANNER to "Epson V600",
            ),
        )

        "135_single" -> SheetConfig(
            filmFormat = SheetConfig.FilmFormat.F135,
            subFormat = "标准 36×24",
            renderStyle = SheetConfig.RenderStyle.LIGHTBOX,
            thumbWidth = 400, columns = 1, spacing = 20,
            singlePhotoMode = true,
            info = mapOf(SheetConfig.InfoKey.FILM to "伊尔福 HP5 Plus"),
        )

        else -> throw IllegalArgumentException("未知用例：$case")
    }
}
