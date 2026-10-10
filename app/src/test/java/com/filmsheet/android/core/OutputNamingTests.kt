package com.filmsheet.android.core

import com.filmsheet.android.config.SheetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输出命名与批量策略测试 — 对应 iOS 端 `FilmSheetPipeline` 的 naming 段。
 *
 * 文件名会出现在用户的相册里，所以规则必须稳定：同一份配置每次都要得到
 * 同一个名字，否则相册里会堆出一堆「FilmSheet-1732...」的重名文件。
 */
class OutputNamingTests {

    private val fmt = SheetConfig.OutputFormat.JPG

    // MARK: - 文件名派生

    @Test
    fun `从信息字段派生文件名`() {
        var info = emptyMap<SheetConfig.InfoKey, String>()
        info += SheetConfig.InfoKey.ROLL to "12"
        info += SheetConfig.InfoKey.CAMERA to "Nikon F3"
        assertEquals("12_Nikon F3.jpg", OutputNaming.generate(null, info, fmt))
    }

    /** 四个字段按 卷号_相机_胶卷_拍摄日期 的固定顺序，不是 Map 的迭代顺序。 */
    @Test
    fun `字段顺序固定`() {
        var info = emptyMap<SheetConfig.InfoKey, String>()
        info += SheetConfig.InfoKey.SHOOT_DATE to "2026-10-10"
        info += SheetConfig.InfoKey.ROLL to "R07"
        assertEquals("R07_2026-10-10.jpg", OutputNaming.generate(null, info, fmt))
    }

    @Test
    fun `跳过空字段`() {
        var info = emptyMap<SheetConfig.InfoKey, String>()
        info += SheetConfig.InfoKey.ROLL to "12"
        info += SheetConfig.InfoKey.CAMERA to ""      // 空，跳过
        info += SheetConfig.InfoKey.FILM to "   "     // 纯空白，也跳过
        assertEquals("12.jpg", OutputNaming.generate(null, info, fmt))
    }

    @Test
    fun `无信息字段时回退`() {
        assertEquals("filmsheet_output.jpg", OutputNaming.generate(null, emptyMap(), fmt))
        assertEquals("filmsheet_output.png", OutputNaming.generate(null, emptyMap(),
            SheetConfig.OutputFormat.PNG))
    }

    /**
     * 用户明确指定的名字要赢。
     *
     * 但**占位名除外** —— 系统另存为对话框默认会填当前文件名，
     * 那个值恰好等于回退名时无法区分「用户没改」与「就想要这个名字」。
     */
    @Test
    fun `自定义名优先但占位名不算`() {
        var info = mapOf(SheetConfig.InfoKey.ROLL to "12")
        assertEquals("我的胶卷.jpg", OutputNaming.generate("我的胶卷.jpg", info, fmt))

        // 占位名视作未指定，改从信息字段派生
        assertEquals("12.jpg", OutputNaming.generate("filmsheet_output.jpg", info, fmt))
        assertEquals("12.png", OutputNaming.generate("filmsheet_output.png", info,
            SheetConfig.OutputFormat.PNG))
    }

    @Test
    fun `自定义名去空白后判定`() {
        var info = mapOf(SheetConfig.InfoKey.ROLL to "12")
        assertEquals("12.jpg", OutputNaming.generate("  filmsheet_output.jpg  ", info, fmt))
    }

    /** 文件名非法字符必须清掉 —— 否则相册写入会失败。 */
    @Test
    fun `清掉非法字符`() {
        var info = mapOf(SheetConfig.InfoKey.CAMERA to "a/b\\c:d*e?f\"g<h>i|j")
        val name = OutputNaming.generate(null, info, fmt)
        assertEquals("a_b_c_d_e_f_g_h_i_j.jpg", name)
        for (ch in ILLEGAL_CHARS) {
            assertTrue("残留非法字符 $ch：$name", !name.dropLast(4).contains(ch))
        }
    }

    /** 全是非法字符时不能返回空文件名 —— 每个字符替换成一个下划线。 */
    @Test
    fun `非法字符清洗后为空则回退`() {
        val info = mapOf(SheetConfig.InfoKey.CAMERA to "///")
        assertEquals("___.jpg", OutputNaming.generate(null, info, fmt))

        // 真的全空（字段有值但清洗后为空）才回退到占位名
        val blank = mapOf(SheetConfig.InfoKey.CAMERA to "\t")
        assertTrue(
            OutputNaming.generate(null, blank, fmt).endsWith(".jpg"),
        )
    }

    @Test
    fun `扩展名跟随输出格式`() {
        var info = mapOf(SheetConfig.InfoKey.ROLL to "12")
        assertEquals("12.png", OutputNaming.generate(null, info, SheetConfig.OutputFormat.PNG))
        assertEquals("12.jpg", OutputNaming.generate(null, info, SheetConfig.OutputFormat.JPG))
    }

    // MARK: - 风格后缀

    @Test
    fun `非批量不加后缀`() {
        assertEquals(
            "",
            OutputNaming.styleSuffix(SheetConfig.RenderStyle.LIGHTBOX, batch = false),
        )
    }

    @Test
    fun `批量加风格后缀`() {
        assertEquals(
            "_light",
            OutputNaming.styleSuffix(SheetConfig.RenderStyle.LIGHTBOX, batch = true),
        )
        assertEquals(
            "_contact",
            OutputNaming.styleSuffix(SheetConfig.RenderStyle.CONTACT_SHEET, batch = true),
        )
    }

    @Test
    fun `拼最终文件名`() {
        // 非批量：无后缀
        assertEquals(
            "12.jpg",
            OutputNaming.outputName("12", SheetConfig.RenderStyle.LIGHTBOX, false, null, fmt),
        )
        // 批量：带后缀
        assertEquals(
            "12_light.jpg",
            OutputNaming.outputName("12", SheetConfig.RenderStyle.LIGHTBOX, true, null, fmt),
        )
        // 单张：序号从 1 开始（人看的编号，不是下标）
        assertEquals(
            "12_contact_single_3.png",
            OutputNaming.outputName(
                "12", SheetConfig.RenderStyle.CONTACT_SHEET, true, 3,
                SheetConfig.OutputFormat.PNG,
            ),
        )
    }

    // MARK: - 批量策略

    @Test
    fun `批量关闭只出当前风格`() {
        val c = SheetConfig().copy(
            batchExportEnabled = false,
            renderStyle = SheetConfig.RenderStyle.LIGHTBOX,
        )
        assertEquals(listOf(SheetConfig.RenderStyle.LIGHTBOX), BatchPlan.stylesFor(c))
        assertEquals(1, BatchPlan.outputCount(c, photoCount = 6))
    }

    @Test
    fun `批量开启出两种风格`() {
        val c = SheetConfig().copy(batchExportEnabled = true)
        assertEquals(
            listOf(SheetConfig.RenderStyle.LIGHTBOX, SheetConfig.RenderStyle.CONTACT_SHEET),
            BatchPlan.stylesFor(c),
        )
        assertEquals(2, BatchPlan.outputCount(c, photoCount = 6))
    }

    /** 两种风格 × 6 张单排 = 12 张，UI 要提前告知。 */
    @Test
    fun `单张与批量叠加`() {
        val c = SheetConfig().copy(batchExportEnabled = true, singlePhotoMode = true)
        assertEquals(12, BatchPlan.outputCount(c, photoCount = 6))
    }

    @Test
    fun `非单张模式没有序号`() {
        val c = SheetConfig()
        assertTrue(BatchPlan.singleIndices(c, 6).isEmpty())
        assertEquals(1, BatchPlan.outputCount(c, 6))
    }

    @Test
    fun `单张照片不产出任何东西`() {
        assertTrue(BatchPlan.plan(SheetConfig(), photoCount = 0).isEmpty())
    }

    @Test
    fun `批量清单文件名互不重复`() {
        val c = SheetConfig().copy(batchExportEnabled = true, singlePhotoMode = true)
        val items = BatchPlan.plan(c, photoCount = 4)
        assertEquals(8, items.size)
        assertEquals(
            "批量导出不该产生重名文件",
            items.size,
            items.map { it.filename }.distinct().size,
        )
    }

    /** 顺序是「风格在外、照片在内」，相册里按名字排序能聚成一堆。 */
    @Test
    fun `清单按风格分组`() {
        val c = SheetConfig().copy(batchExportEnabled = true, singlePhotoMode = true)
        val items = BatchPlan.plan(c, photoCount = 3)
        assertEquals(
            listOf(
                SheetConfig.RenderStyle.LIGHTBOX,
                SheetConfig.RenderStyle.LIGHTBOX,
                SheetConfig.RenderStyle.LIGHTBOX,
                SheetConfig.RenderStyle.CONTACT_SHEET,
                SheetConfig.RenderStyle.CONTACT_SHEET,
                SheetConfig.RenderStyle.CONTACT_SHEET,
            ),
            items.map { it.style },
        )
        assertEquals(listOf(0, 1, 2), items.take(3).map { it.singleIndex })
    }

    /** 照片数在渲染途中变少时不能越界。 */
    @Test
    fun `照片下标夹到有效范围`() {
        assertEquals(0, BatchPlan.photoIndexFor(photoCount = 1, singleIndex = 5))
        assertEquals(2, BatchPlan.photoIndexFor(photoCount = 3, singleIndex = 2))
        assertEquals(0, BatchPlan.photoIndexFor(photoCount = 0, singleIndex = 0))
    }

    private companion object {
        const val ILLEGAL_CHARS = "\\/:*?\"<>|"
    }
}
