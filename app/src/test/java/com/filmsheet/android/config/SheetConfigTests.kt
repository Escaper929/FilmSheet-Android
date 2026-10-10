package com.filmsheet.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置与常量测试 — 与 iOS 端 `SheetConfigTests.swift` 对应。
 *
 * 两端不共享代码（Kotlin 与 Swift 无法互相编译），共享的只有 SPEC.md。
 * 所以这组测试的作用是**把值钉死**：改动必须是有意识的镜像改动，
 * 而不是碰巧漂移过去的。
 */
class SheetConfigTests {

    // MARK: - 默认值

    @Test
    fun `默认值符合规格`() {
        val c = SheetConfig()
        assertEquals(400, c.thumbWidth)
        assertEquals(20, c.spacing)
        assertEquals(6, c.columns)
        assertEquals(95, c.quality)
        assertEquals(SheetConfig.FilmFormat.F135, c.filmFormat)
        assertEquals("标准 36×24", c.subFormat)
        assertEquals(SheetConfig.InfoLang.EN, c.infoLang)
        assertEquals(SheetConfig.PackPosition.LEFT, c.packPosition)
        assertEquals(80, c.packSize)
        assertEquals(SheetConfig.RenderStyle.LIGHTBOX, c.renderStyle)
        assertEquals(SheetConfig.ProcessingMode.POSITIVE, c.processingMode)
        assertEquals(com.filmsheet.android.core.PerfMode.AUTO, c.perfMode)
    }

    // MARK: - subFormat 是跨字段约束

    @Test
    fun `空子格式按片基回填`() {
        var c = SheetConfig()
        c = c.copy(subFormat = "")
        assertEquals("标准 36×24", c.sanitized().subFormat)

        c = c.copy(filmFormat = SheetConfig.FilmFormat.F120)
        assertEquals("66", c.sanitized().subFormat)
    }

    @Test
    fun `非法子格式回落到本片基默认值`() {
        var c = SheetConfig()
        c = c.copy(filmFormat = SheetConfig.FilmFormat.F120, subFormat = "标准 36×24")
        assertEquals("66", c.sanitized().subFormat)

        c = c.copy(filmFormat = SheetConfig.FilmFormat.F135, subFormat = "617")
        assertEquals("标准 36×24", c.sanitized().subFormat)
    }

    /** 清洗的顺序不可调换：子格式的两步必须早于通用 clamp。 */
    @Test
    fun `清洗顺序保证非法值被修正`() {
        val c = SheetConfig(filmFormat = SheetConfig.FilmFormat.F120, subFormat = "bogus")
        assertEquals("66", c.sanitized().subFormat)
    }

    // MARK: - Clamp

    @Test
    fun `清洗把值夹到规格边界`() {
        assertEquals(1200, SheetConfig(thumbWidth = 5000).sanitized().thumbWidth)
        assertEquals(300, SheetConfig(thumbWidth = 10).sanitized().thumbWidth)
        assertEquals(1, SheetConfig(columns = 0).sanitized().columns)
        assertEquals(99, SheetConfig(columns = 500).sanitized().columns)
        assertEquals(1, SheetConfig(quality = 0).sanitized().quality)
        assertEquals(100, SheetConfig(quality = 999).sanitized().quality)
        assertEquals(10, SheetConfig(packSize = 0).sanitized().packSize)
        assertEquals(100, SheetConfig(packSize = 500).sanitized().packSize)
    }

    /** 合法值必须原样通过 —— 一次误触发的 clamp 会悄悄改掉输出尺寸。 */
    @Test
    fun `清洗不改动合法值`() {
        val before = SheetConfig()
        val after = before.sanitized()
        assertEquals(before.thumbWidth, after.thumbWidth)
        assertEquals(before.columns, after.columns)
        assertEquals(before.quality, after.quality)
        assertEquals(before.packSize, after.packSize)
        assertEquals(before.subFormat, after.subFormat)
    }

    // MARK: - 校验

    @Test
    fun `默认配置通过校验`() {
        assertTrue(SheetConfig().validate().isEmpty())
    }

    @Test
    fun `校验逐条报错`() {
        val c = SheetConfig(
            thumbWidth = 100,
            columns = 0,
            quality = 101,
            packSize = 5,
            subFormat = "66",   // 135 + 一个 120 的子格式
        )
        assertEquals(5, c.validate().size)
    }

    // MARK: - 信息行

    @Test
    fun `有效信息行只数填了的行`() {
        var c = SheetConfig()
        assertEquals(0, c.activeInfoRows)
        assertFalse(c.hasAnyInfo)

        c = c.withValue(SheetConfig.InfoKey.ROLL, "12")
        assertEquals(1, c.activeInfoRows)
        assertTrue(c.hasAnyInfo)

        // 相机与卷号同一布局行，高度不变。
        c = c.withValue(SheetConfig.InfoKey.CAMERA, "Nikon F3")
        assertEquals(1, c.activeInfoRows)

        // 冲洗地点在第 3 行。
        c = c.withValue(SheetConfig.InfoKey.LAB, "诺丁山")
        assertEquals(2, c.activeInfoRows)

        // 第 2 行第 3 槽是空位，永远不可能被填上。
        assertEquals(3, SheetConfig.InfoLayout.rows[1].size)
        assertEquals(null, SheetConfig.InfoLayout.rows[1][2])
    }

    @Test
    fun `纯空白不算填了`() {
        val c = SheetConfig().withValue(SheetConfig.InfoKey.ROLL, "   ")
        assertFalse(c.hasAnyInfo)
    }

    // MARK: - 标签

    @Test
    fun `卷号用空格而非冒号`() {
        assertFalse(SheetConfig.InfoKey.ROLL.usesColon)
        assertTrue(SheetConfig.InfoKey.CAMERA.usesColon)
    }

    @Test
    fun `标签随语言切换`() {
        assertEquals("卷号", SheetConfig.InfoKey.ROLL.label(SheetConfig.InfoLang.ZH))
        assertEquals("Roll No.", SheetConfig.InfoKey.ROLL.label(SheetConfig.InfoLang.EN))
    }

    @Test
    fun `每个枚举都能从raw值还原`() {
        assertEquals(SheetConfig.FilmFormat.F120, SheetConfig.FilmFormat.fromRaw("120"))
        assertEquals(SheetConfig.FilmFormat.F135, SheetConfig.FilmFormat.fromRaw("bogus"))
        assertEquals(
            SheetConfig.ProcessingMode.NEGATIVE,
            SheetConfig.ProcessingMode.fromRaw("negative"),
        )
        assertEquals(SheetConfig.OutputFormat.PNG, SheetConfig.OutputFormat.fromRaw("PNG"))
        assertEquals(SheetConfig.InfoLang.ZH, SheetConfig.InfoLang.fromRaw("zh"))
        assertEquals(
            SheetConfig.PackPosition.RIGHT,
            SheetConfig.PackPosition.fromRaw("right"),
        )
    }
}
