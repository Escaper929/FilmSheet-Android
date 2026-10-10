package com.filmsheet.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 物理常量与片边文字测试 — 对应 iOS 端 `SheetConfigTests.swift` 的
 * `FilmEngineConstantsTests` 与 `EdgeTextTests` 两个类。
 *
 * SPEC.md §2–3 的常量是「像胶片」与「像柱状图」的分界线；§7 的片边文字
 * 则是把一个中文胶卷字段翻译成真实片边印字。两端都得一模一样。
 */
class FilmEngineConstantsTests {

    // MARK: - SMPTE 常量

    @Test
    fun `135常量与规格一致`() {
        assertEquals(35.0, FilmEngine135.FILM_WIDTH_MM, 1e-9)
        assertEquals(36.0, FilmEngine135.FRAME_WIDTH_MM, 1e-9)
        assertEquals(24.0, FilmEngine135.FRAME_HEIGHT_MM, 1e-9)
        assertEquals(2.01, FilmEngine135.EDGE_TO_PERF_MM, 1e-9)
        assertEquals(2.794, FilmEngine135.PERF_DIM_Y_MM, 1e-9)
        assertEquals(1.981, FilmEngine135.PERF_DIM_X_KS_MM, 1e-9)
        assertEquals(1.854, FilmEngine135.PERF_DIM_X_BH_MM, 1e-9)
        assertEquals(0.508, FilmEngine135.PERF_RADIUS_MM, 1e-9)
        assertEquals(0.35, FilmEngine135.BH_CURVE_DEPTH_MM, 1e-9)
        assertEquals(4.750, FilmEngine135.PITCH_KS_MM, 1e-9)
        assertEquals(4.740, FilmEngine135.PITCH_BH_MM, 1e-9)
    }

    /** KS 齿孔更大、间距更宽 —— 这是民用与电影胶卷之间肉眼可辨的差别。 */
    @Test
    fun `KS齿孔比BH大`() {
        assertTrue(FilmEngine135.PERF_DIM_X_KS_MM > FilmEngine135.PERF_DIM_X_BH_MM)
        assertTrue(
            FilmEngine135.pitch(FilmEngine135.PerfType.KS) >
                FilmEngine135.pitch(FilmEngine135.PerfType.BH)
        )
    }

    @Test
    fun `子格式表与规格一致`() {
        val t = FilmEngine135.subFormatConfigs
        assertEquals(36.0, t.getValue("标准 36×24").widthMM, 1e-9)
        assertEquals(24.0, t.getValue("标准 36×24").heightMM, 1e-9)
        assertEquals(8, t.getValue("标准 36×24").perfsPerFrame)
        assertEquals(18.0, t.getValue("半格 18×24").widthMM, 1e-9)
        assertEquals(4, t.getValue("半格 18×24").perfsPerFrame)
        assertEquals(24.0, t.getValue("方形 24×24").widthMM, 1e-9)
        assertEquals(5, t.getValue("方形 24×24").perfsPerFrame)
        assertEquals(65.0, t.getValue("XPan 65×24").widthMM, 1e-9)
        assertEquals(14, t.getValue("XPan 65×24").perfsPerFrame)
    }

    /** UI 里能选的每个子格式都必须有对应配置，否则会静默回落到默认值。 */
    @Test
    fun `UI里可选的子格式都有配置项`() {
        for (name in com.filmsheet.android.config.SheetConfig.SUB_FORMATS_135) {
            assertTrue(FilmEngine135.subFormatConfigs.containsKey(name))
        }
        for (name in com.filmsheet.android.config.SheetConfig.SUB_FORMATS_120) {
            assertTrue(FilmEngine120.imageWidthsMM.containsKey(name))
        }
    }

    @Test
    fun `非法子格式回落到默认值`() {
        assertEquals(36.0, FilmEngine135.subFormatOrDefault("bogus").widthMM, 1e-9)
        assertEquals(56.0, FilmEngine120.imageWidthOrDefault("bogus"), 1e-9)
    }

    // MARK: - 120 的 ISO 732 常量

    @Test
    fun `120常量与规格一致`() {
        assertEquals(61.0, FilmEngine120.BACKING_WIDTH_MM, 1e-9)
        assertEquals(56.0, FilmEngine120.IMAGE_HEIGHT_MM, 1e-9)
        assertEquals(2.5, FilmEngine120.IMAGE_BORDER_MM_TOP_BOTTOM, 1e-9)
        assertEquals(41.5, FilmEngine120.imageWidthsMM.getValue("645"), 1e-9)
        assertEquals(224.0, FilmEngine120.imageWidthsMM.getValue("624"), 1e-9)
    }

    // MARK: - 齿孔类型判定

    @Test
    fun `显式选择优先于嗅探`() {
        assertEquals(
            FilmEngine135.PerfType.KS,
            FilmEngine135.determinePerfType("5207", PerfMode.KS),
        )
        assertEquals(
            FilmEngine135.PerfType.BH,
            FilmEngine135.determinePerfType("Portra 400", PerfMode.BH),
        )
    }

    @Test
    fun `auto能认出电影胶卷`() {
        // 注意关键词是 `cinema` 而非 `cine` —— iOS 端那份测试误写成了 `cine`，
        // 那条断言在 iOS 侧同样通不过（"cine" 不含 "cinema"）。
        for (stock in listOf(
            "柯达 Vision3 500T", "5207", "5294", "Ektachrome",
            "MOTION PICTURE", "cinema stock",
        )) {
            assertEquals(
                "$stock 应读作 Bell & Howell",
                FilmEngine135.PerfType.BH,
                FilmEngine135.determinePerfType(stock, PerfMode.AUTO),
            )
        }
    }

    /** `cine` 不是关键词，不能被误判成电影胶卷。 */
    @Test
    fun `cine不触发电影判定`() {
        assertEquals(
            FilmEngine135.PerfType.KS,
            FilmEngine135.determinePerfType("cine", PerfMode.AUTO),
        )
    }

    @Test
    fun `auto对民用胶卷回落KS`() {
        for (stock in listOf("柯达 Portra 400", "富士 Velvia 50", "伊尔福 HP5 Plus", "")) {
            assertEquals(
                "$stock 应读作 Kodak Standard",
                FilmEngine135.PerfType.KS,
                FilmEngine135.determinePerfType(stock, PerfMode.AUTO),
            )
        }
    }

    @Test
    fun `判定忽略大小写`() {
        assertEquals(
            FilmEngine135.PerfType.BH,
            FilmEngine135.determinePerfType("VISION3 500T", PerfMode.AUTO),
        )
        assertEquals(
            FilmEngine135.PerfType.BH,
            FilmEngine135.determinePerfType("vision3 500t", PerfMode.AUTO),
        )
    }

    @Test
    fun `PerfMode能从raw值还原`() {
        assertEquals(PerfMode.BH, PerfMode.fromRaw("BH (电影)"))
        assertEquals(PerfMode.KS, PerfMode.fromRaw("KS (民用)"))
        assertEquals(PerfMode.AUTO, PerfMode.fromRaw("bogus"))
    }

    // MARK: - 画幅宽高比

    @Test
    fun `宽高比表与规格一致`() {
        val r = com.filmsheet.android.config.SheetConfig.filmFormatRatios
        assertEquals(1.5, r.getValue("135"), 1e-9)
        assertEquals(1.0, r.getValue("66"), 1e-9)
        assertEquals(56.0 / 41.5, r.getValue("645"), 1e-9)
        assertEquals(168.0 / 56.0, r.getValue("617"), 1e-9)
    }

    @Test
    fun `每个120子格式都有宽高比`() {
        // 135 归并为单个 "135" 条目；120 的子格式以自己的名字为键。
        val r = com.filmsheet.android.config.SheetConfig.filmFormatRatios
        assertTrue(r.containsKey("135"))
        for (name in com.filmsheet.android.config.SheetConfig.SUB_FORMATS_120) {
            assertTrue("$name 在 UI 里可选却没有宽高比", r.containsKey(name))
        }
    }
}

/** SPEC.md §7 — 片边文字映射。 */
class EdgeTextTests {

    @Test
    fun `自定义文字跳过所有映射`() {
        val e = EdgeTextGenerator.generate("柯达 Portra 400", "AGFA OPTIMA 200")
        assertTrue(e.isCustom)
        assertEquals("AGFA OPTIMA 200", e.brand)
        assertEquals("", e.filmType)
        assertEquals("AGFA OPTIMA 200", e.topLine)
    }

    @Test
    fun `空白自定义文字不算自定义`() {
        val e = EdgeTextGenerator.generate("柯达 Portra 400", "   ")
        assertTrue(!e.isCustom)
        assertEquals("KODAK", e.brand)
    }

    @Test
    fun `品牌与型号都被映射`() {
        val e = EdgeTextGenerator.generate("柯达 Portra 400")
        assertEquals("KODAK", e.brand)
        assertEquals("Portra 400", e.filmType)
        assertTrue(!e.isCustom)
    }

    @Test
    fun `上行用两个空格连接`() {
        assertEquals("FUJIFILM  Velvia 50", EdgeTextGenerator.generate("富士 Velvia 50").topLine)
    }

    @Test
    fun `只有品牌时不含尾部空格`() {
        val e = EdgeTextGenerator.generate("柯达")
        assertEquals("KODAK", e.brand)
        assertEquals("KODAK", e.topLine)
    }

    /** 电影胶片不论零售品牌是什么，片边都印 EASTMAN。 */
    @Test
    fun `电影胶片强制EASTMAN`() {
        val e = EdgeTextGenerator.generate("柯达 Vision3 500T")
        assertEquals("EASTMAN", e.brand)
        assertEquals("Vision3 500T", e.filmType)
    }

    @Test
    fun `电影胶片剥掉零售品牌前缀`() {
        assertEquals("5207", EdgeTextGenerator.generate("柯达 5207").filmType)
        assertEquals("Ektachrome 100", EdgeTextGenerator.generate("富士 Ektachrome 100").filmType)
    }

    /** `乐凯C200` 没有空格，朴素拆分会把整串当品牌。中文前缀那一趟要救回来。 */
    @Test
    fun `中文品牌无空格也能拆开`() {
        val e = EdgeTextGenerator.generate("乐凯C200")
        assertEquals("LUCKY", e.brand)
        assertEquals("C200", e.filmType)
    }

    @Test
    fun `型号大小写被修正`() {
        assertEquals(
            "Portra 160NC",
            EdgeTextGenerator.generate("柯达 Portra 160nc").filmType,
        )
        assertEquals(
            "Portra 160NC",
            EdgeTextGenerator.generate("柯达 PORTRA 160NC").filmType,
        )
    }

    @Test
    fun `子串兜底能认出嵌套型号`() {
        // 品牌前缀没被拆干净时，型号仍应从整串里认出来。
        assertEquals("Portra 400", EdgeTextGenerator.generate("Kodak Portra 400").filmType)
    }

    @Test
    fun `未知品牌转大写而不是丢弃`() {
        assertEquals("FOMAPAN", EdgeTextGenerator.generate("fomapan").brand)
    }

    @Test
    fun `空输入不炸`() {
        val e = EdgeTextGenerator.generate("")
        assertEquals("", e.brand)
        assertEquals("", e.topLine)
    }

    /** 批号必须跨运行稳定 —— Python 的加盐 `hash()` 会每次都变，所以才用 FNV-1a。 */
    @Test
    fun `批号可复现`() {
        val a = EdgeTextGenerator.generate("柯达 Portra 400").lotCode
        val b = EdgeTextGenerator.generate("柯达 Portra 400").lotCode
        assertEquals(a, b)
        assertTrue(a.startsWith("E"))
    }

    @Test
    fun `不同胶卷的批号不同`() {
        assertNotEquals(
            EdgeTextGenerator.generate("柯达 Portra 400").lotCode,
            EdgeTextGenerator.generate("富士 Velvia 50").lotCode,
        )
    }

    @Test
    fun `批号格式为E加一位数字加四位数字`() {
        val code = EdgeTextGenerator.generate("柯达 Portra 400").lotCode
        assertTrue("批号格式不符：$code", Regex("^E\\d \\d{4}$").matches(code))
    }
}
