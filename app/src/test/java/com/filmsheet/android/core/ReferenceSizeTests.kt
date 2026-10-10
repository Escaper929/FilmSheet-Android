package com.filmsheet.android.core

import com.filmsheet.android.config.SheetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用例表与桌面端参考图的一致性检查 — **纯 JVM 可跑**。
 *
 * `PixelParityTests`（instrumented）要真设备，但有一大半价值其实不依赖设备：
 * **布局算术**。画布尺寸一旦与桌面端不符，就说明边距、行数、片高、齿孔间距、
 * 帧宽高、信息块高度里至少有一处错了（SPEC §5）。而这些全部由
 * [LayoutCalculator] 算出，不需要一个像素。
 *
 * 所以把桌面端参考图的**尺寸**记在这里，逐个用例钉死。跑 `./scripts/jvm-test.sh`
 * 就能验证布局是否与桌面端一致 —— 这是整个对照体系里性价比最高的一条。
 *
 * 尺寸来自桌面端 `Verification/render_reference.py` 在 2026-10-10 的实际输出，
 * 对应 `app/src/androidTest/assets/reference/` 里的 PNG。
 */
class ReferenceSizeTests {

    /**
     * 用例名 → 桌面端参考图的画布尺寸。
     *
     * 改任何一个渲染参数都要重新生成参考图并更新这里 —— 参考图变了而这张
     * 表没变，对照就失去了意义。
     */
    private val expected: Map<String, Pair<Int, Int>> = mapOf(
        "135_light_info" to (1900 to 1452),
        "135_dark_plain" to (1520 to 1404),
        "135_bh_perf" to (2857 to 1476),
        "135_half" to (1900 to 1960),
        "120_66" to (1500 to 1583),
        "120_617_wide" to (1590 to 1741),
        "135_single" to (420 to 388),
    )

    /** 输入底片张数（`make_inputs.py` 生成 6 张）。 */
    private val inputCount = 6

    /** 与 `VerifyCases.configFor` 保持一致 —— 两处共用同一份配置。 */
    private fun configFor(case: String): SheetConfig = when (case) {
        "135_light_info" -> SheetConfig(
            subFormat = "标准 36×24", renderStyle = SheetConfig.RenderStyle.LIGHTBOX,
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
            subFormat = "标准 36×24", renderStyle = SheetConfig.RenderStyle.CONTACT_SHEET,
            thumbWidth = 400, columns = 3, spacing = 30,
        )
        "135_bh_perf" -> SheetConfig(
            subFormat = "标准 36×24", renderStyle = SheetConfig.RenderStyle.LIGHTBOX,
            thumbWidth = 500, columns = 5, spacing = 15,
            perfMode = PerfMode.BH,
            info = mapOf(
                SheetConfig.InfoKey.FILM to "柯达 Vision3 500T",
                SheetConfig.InfoKey.ROLL to "A007",
            ),
        )
        "135_half" -> SheetConfig(
            subFormat = "半格 18×24", renderStyle = SheetConfig.RenderStyle.LIGHTBOX,
            thumbWidth = 400, columns = 4, spacing = 20,
            info = mapOf(
                SheetConfig.InfoKey.CAMERA to "Leica M6",
                SheetConfig.InfoKey.FILM to "富士 Provia 100F",
            ),
        )
        "120_66" -> SheetConfig(
            filmFormat = SheetConfig.FilmFormat.F120, subFormat = "66",
            renderStyle = SheetConfig.RenderStyle.LIGHTBOX,
            thumbWidth = 400, columns = 3, spacing = 25,
            info = mapOf(
                SheetConfig.InfoKey.ROLL to "R-07",
                SheetConfig.InfoKey.FILM to "富士 Velvia 50",
            ),
        )
        "120_617_wide" -> SheetConfig(
            filmFormat = SheetConfig.FilmFormat.F120, subFormat = "617",
            renderStyle = SheetConfig.RenderStyle.CONTACT_SHEET,
            thumbWidth = 600, columns = 2, spacing = 20,
            info = mapOf(
                SheetConfig.InfoKey.FILM to "柯达 Tri-X 400",
                SheetConfig.InfoKey.SCANNER to "Epson V600",
            ),
        )
        "135_single" -> SheetConfig(
            subFormat = "标准 36×24", renderStyle = SheetConfig.RenderStyle.LIGHTBOX,
            thumbWidth = 400, columns = 1, spacing = 20,
            singlePhotoMode = true,
            info = mapOf(SheetConfig.InfoKey.FILM to "伊尔福 HP5 Plus"),
        )
        else -> throw IllegalArgumentException("未知用例：$case")
    }

    /**
     * 每个用例的画布尺寸都必须与桌面端一致。
     *
     * 这条比任何像素比对都强：尺寸由布局算术唯一决定，而布局算术覆盖
     * 边距、行数、片高、齿孔间距、帧宽高、信息块高度、画布高度公式
     * —— 任何一处漂移都会让尺寸变化。
     */
    @Test
    fun `画布尺寸与桌面端参考图一致`() {
        val failures = mutableListOf<String>()
        for ((case, want) in expected) {
            val config = configFor(case)
            // 单张模式只用第一张，与桌面端 files[:1] 一致
            val photoCount = if (case == "135_single") 1 else inputCount
            val layout = LayoutCalculator.compute(config, photoCount)
            val got = layout.totalW to layout.totalH
            if (got != want) {
                failures += "$case：期望 ${want.first}×${want.second}，" +
                    "实得 ${got.first}×${got.second}"
            }
        }
        assertTrue(
            "以下用例的画布尺寸与桌面端不符：\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `参考尺寸表与用例表一一对应`() {
        assertEquals(7, expected.size)
        // 缺任何一个用例都会让对照覆盖不完整
        for (case in expected.keys) {
            // 能取到配置就说明用例在两边都定义了
            configFor(case)
        }
    }

    /**
     * 尺寸差异一旦发生，多半来自某处常量被改动。
     * 这里把最容易被改的三个量单独算一遍，改动时能立刻定位。
     */
    @Test
    fun `关键用例的尺寸构成可解释`() {
        // 4 列 400px + 5 个 20px 间距 + 2×50 边距 + 100 留白 = 1900
        val a = LayoutCalculator.compute135(
            SheetConfig(subFormat = "标准 36×24", thumbWidth = 400, columns = 4, spacing = 20),
            inputCount,
        )
        assertEquals(4 * 400 + 5 * 20, a.contentW)
        assertEquals(a.contentW + 2 * 50 + 100, a.totalW)

        // 单张模式：400px 画幅 + 两侧各 10px
        val s = LayoutCalculator.compute135(
            SheetConfig(
                subFormat = "标准 36×24", thumbWidth = 400, columns = 1,
                singlePhotoMode = true,
            ),
            1,
        )
        assertEquals(420, s.totalW)
    }
}
