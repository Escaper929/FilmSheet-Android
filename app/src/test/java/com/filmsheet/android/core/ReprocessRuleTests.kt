package com.filmsheet.android.core

import com.filmsheet.android.config.SheetConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「配置变化是否需要重新预处理照片」的判定。
 *
 * 这条判定写错的后果特别隐蔽：漏判时预览看着「排版变了」，但照片本身
 * 还是旧画幅裁的 —— 要等导出才发现。所以每个字段都要单独钉死。
 */
class ReprocessRuleTests {

    private val base = SheetConfig()

    private fun needs(old: SheetConfig, new: SheetConfig): Boolean =
        ReprocessRule.needsReprocess(old, new)

    // MARK: - 需要重新预处理（影响画幅/像素）

    @Test
    fun `换片基要重处理`() {
        assertTrue(needs(base, base.copy(filmFormat = SheetConfig.FilmFormat.F120)))
    }

    @Test
    fun `换画幅要重处理`() {
        assertTrue(needs(base, base.copy(subFormat = "半格 18×24")))
    }

    @Test
    fun `换负片要重处理`() {
        // 反相发生在解码之后、重排之前，必须重跑
        assertTrue(
            needs(base, base.copy(processingMode = SheetConfig.ProcessingMode.NEGATIVE)),
        )
    }

    @Test
    fun `换强制横构图要重处理`() {
        // 转向会交换画幅宽高
        assertTrue(needs(base, base.copy(forceLandscape = false)))
    }

    @Test
    fun `换缩略图宽度要重处理`() {
        // 缩放发生在预处理，布局阶段只消费结果
        assertTrue(needs(base, base.copy(thumbWidth = 800)))
    }

    // MARK: - 不必重处理（只影响排版）

    @Test
    fun `布局类参数不必重处理`() {
        for (new in listOf(
            base.copy(columns = 4),
            base.copy(spacing = 50),
            base.copy(renderStyle = SheetConfig.RenderStyle.CONTACT_SHEET),
            base.copy(packPosition = SheetConfig.PackPosition.RIGHT),
            base.copy(packSize = 50),
            base.copy(packBorderStroke = false),
            base.copy(signature = "FilmSheet"),
            base.copy(quality = 80),
            base.copy(outputFormat = SheetConfig.OutputFormat.PNG),
            base.copy(infoLang = SheetConfig.InfoLang.ZH),
            base.copy(perfMode = PerfMode.BH),
            base.copy(edgeText = "MY FILM"),
            base.withValue(SheetConfig.InfoKey.ROLL, "12"),
            base.copy(singlePhotoMode = true),
            base.copy(batchExportEnabled = true),
        )) {
            assertFalse("不该触发重处理：$new", needs(base, new))
        }
    }

    @Test
    fun `相同配置不重处理`() {
        assertFalse(needs(base, base))
        assertFalse(needs(base, base.copy()))
    }

    /** 同时变多个字段时，只要有一个需要重处理就该重处理。 */
    @Test
    fun `多字段变化仍能识别`() {
        assertTrue(
            needs(
                base,
                base.copy(columns = 3, processingMode = SheetConfig.ProcessingMode.NEGATIVE),
            ),
        )
    }
}
