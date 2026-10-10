package com.filmsheet.android.core

import com.filmsheet.android.config.SheetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置编解码测试 — 对应 iOS 端 `SheetConfigTests` 的持久化那几条。
 *
 * 持久化最容易出的两类问题：
 *
 * 1. **往返丢失** —— 存进去再取出来某个字段变了。测试逐字段钉。
 * 2. **容错失效** —— 某个字段脏了就把**整份**配置作废。测试专门喂脏数据。
 */
class ConfigCodecTests {

    private val rich = SheetConfig(
        thumbWidth = 700, spacing = 35, columns = 4,
        forceLandscape = false, edgeText = "MY FILM",
        outputFormat = SheetConfig.OutputFormat.PNG, quality = 80,
        filmFormat = SheetConfig.FilmFormat.F120, subFormat = "617",
        infoLang = SheetConfig.InfoLang.ZH,
        packPosition = SheetConfig.PackPosition.RIGHT, packBorderStroke = false,
        renderStyle = SheetConfig.RenderStyle.CONTACT_SHEET, packSize = 55,
        processingMode = SheetConfig.ProcessingMode.NEGATIVE,
        perfMode = PerfMode.BH,
        info = mapOf(
            SheetConfig.InfoKey.ROLL to "R-07",
            SheetConfig.InfoKey.LAB to "诺丁山",
            SheetConfig.InfoKey.SCANNER to "Epson V600",
        ),
        signature = "FilmSheet",
        batchExportEnabled = true, singlePhotoMode = true, singlePhotoShowExtra = true,
    )

    @Test
    fun `往返后逐字段不变`() {
        val restored = ConfigCodec.decode(ConfigCodec.encode(rich))
        assertEquals(rich, restored)
    }

    @Test
    fun `往返保留部分填充的信息字段`() {
        val c = SheetConfig().withValue(SheetConfig.InfoKey.ROLL, "12")
        val restored = ConfigCodec.decode(ConfigCodec.encode(c))
        assertEquals("12", restored.value(SheetConfig.InfoKey.ROLL))
        assertEquals(1, restored.info.size)
    }

    @Test
    fun `空信息往返后仍为空`() {
        val restored = ConfigCodec.decode(ConfigCodec.encode(SheetConfig()))
        assertTrue(restored.info.isEmpty())
    }

    // MARK: - 容错

    /** 任一字段缺失就用默认值补上，不整份作废。 */
    @Test
    fun `缺字段时用默认值补齐`() {
        val restored = ConfigCodec.decode(mapOf("columns" to "9"))
        assertEquals(9, restored.columns)
        // 其余全是默认值
        assertEquals(SheetConfig().thumbWidth, restored.thumbWidth)
        assertEquals(SheetConfig().renderStyle, restored.renderStyle)
    }

    /** 空表与全部无效都回到默认值。 */
    @Test
    fun `空数据回默认值`() {
        assertEquals(SheetConfig(), ConfigCodec.decode(emptyMap()))
        assertEquals(SheetConfig(), ConfigCodec.decode(mapOf("junk" to "x")))
    }

    /**
     * 布尔只接受 `true` / `false`。
     *
     * 用 Kotlin 的 `String.toBoolean()` 的话，`"1"`、`"yes"`、任何非 "false"
     * 的字符串都会被当成 true —— 用户手工编辑偏好文件时会莫名打开开关。
     *
     * 用 `packBorderStroke`（默认 true）与 `singlePhotoMode`（默认 false）
     * 两条一起验：脏值必须落回**各自的默认值**，而不是统一的 false。
     */
    @Test
    fun `布尔字段严格解析`() {
        val d = SheetConfig()
        assertTrue(d.packBorderStroke)      // 默认 true
        assertFalse(d.singlePhotoMode)      // 默认 false

        // 「true」正合适
        assertTrue(ConfigCodec.decode(mapOf("packBorderStroke" to "true")).packBorderStroke)
        assertTrue(ConfigCodec.decode(mapOf("singlePhotoMode" to "true")).singlePhotoMode)

        // 「false」正合适
        assertFalse(ConfigCodec.decode(mapOf("packBorderStroke" to "false")).packBorderStroke)
        assertFalse(ConfigCodec.decode(mapOf("singlePhotoMode" to "false")).singlePhotoMode)

        // 垃圾值必须落回各自的默认值，而不是被当成 true 或统一成 false
        for (junk in listOf("1", "0", "yes", "TRUE", "", "  ", "false ", "null")) {
            assertEquals(
                "packBorderStroke 对「$junk」应落回默认 true",
                d.packBorderStroke,
                ConfigCodec.decode(mapOf("packBorderStroke" to junk)).packBorderStroke,
            )
            assertEquals(
                "singlePhotoMode 对「$junk」应落回默认 false",
                d.singlePhotoMode,
                ConfigCodec.decode(mapOf("singlePhotoMode" to junk)).singlePhotoMode,
            )
        }
    }

    /** 数字脏了不该变成 0 或别的离谱值。 */
    @Test
    fun `数字字段严格解析`() {
        val fallback = SheetConfig().columns
        assertEquals(fallback, ConfigCodec.decode(mapOf("columns" to "abc")).columns)
        assertEquals(fallback, ConfigCodec.decode(mapOf("columns" to "")).columns)
        // 小数不该被截断成 3 —— 那是另一个值，不是 3.5 的近似
        assertEquals(fallback, ConfigCodec.decode(mapOf("columns" to "3.5")).columns)
        assertEquals(7, ConfigCodec.decode(mapOf("columns" to "7")).columns)
        // 超界也不该被放行：清洗会在 ConfigStore.load 里跑
        assertEquals(999, ConfigCodec.decode(mapOf("columns" to "999")).columns)
    }

    /** 枚举认不出来时保留默认值，不整份作废。 */
    @Test
    fun `枚举字段容错`() {
        assertEquals(
            SheetConfig.FilmFormat.F135,
            ConfigCodec.decode(mapOf("filmFormat" to "film")).filmFormat,
        )
        assertEquals(
            SheetConfig.RenderStyle.LIGHTBOX,
            ConfigCodec.decode(mapOf("renderStyle" to "vaporwave")).renderStyle,
        )
    }

    /** 渲染风格存的是显示名 —— 那是对用户友好的形式。 */
    @Test
    fun `渲染风格按显示名存取`() {
        val c = SheetConfig().copy(renderStyle = SheetConfig.RenderStyle.CONTACT_SHEET)
        val encoded = ConfigCodec.encode(c)
        assertEquals("接触印相", encoded["renderStyle"])
        assertEquals(c.renderStyle, ConfigCodec.decode(encoded).renderStyle)
    }

    /** 未知的信息字段键直接忽略 —— 可能是旧版本留下的。 */
    @Test
    fun `忽略未知信息字段`() {
        val restored = ConfigCodec.decode(
            mapOf("info.ROLL" to "12", "info.LEGACY_FIELD" to "垃圾", "junk" to "x"),
        )
        assertEquals(1, restored.info.size)
        assertEquals("12", restored.value(SheetConfig.InfoKey.ROLL))
    }

    // MARK: - 包装图不持久化

    /**
     * 包装图**不写进**偏好文件。
     *
     * 它是张多 MB 的图。而且它本来就是每次会话重新挑的，用户不会指望
     * 重启后还在。
     */
    @Test
    fun `包装图不参与持久化`() {
        val c = SheetConfig().copy(packImageUri = "content://media/12345")
        val encoded = ConfigCodec.encode(c)
        assertTrue(
            "编码结果里不该出现包装图 URI：${encoded.keys}",
            encoded.keys.none { it.contains("pack", ignoreCase = true) && it.contains("uri", true) },
        )
        assertNull(encoded["packImageUri"])
        // 恢复后也确实是空的
        assertNull(ConfigCodec.decode(encoded).packImageUri)
    }

    /** 但包装图的位置、大小、描边这些设置要存 —— 那是用户的偏好。 */
    @Test
    fun `包装图的位置与大小要存`() {
        val c = SheetConfig().copy(
            packPosition = SheetConfig.PackPosition.RIGHT,
            packSize = 40,
            packBorderStroke = false,
        )
        val restored = ConfigCodec.decode(ConfigCodec.encode(c))
        assertEquals(SheetConfig.PackPosition.RIGHT, restored.packPosition)
        assertEquals(40, restored.packSize)
        assertFalse(restored.packBorderStroke)
    }

    // MARK: - 往返后的合法性

    /**
     * 恢复出来的配置应当始终是**合法的**。
     *
     * 偏好文件可能来自旧版本 —— 比如那时片基还是别的值，于是 `subFormat`
     * 对当前片基是非法的。`ConfigStore.load()` 会再跑一次 `sanitized()`，
     * 但编解码本身也不该产出明显不合理的值。
     */
    @Test
    fun `恢复后可被清洗成合法值`() {
        // 模拟「文件里 filmFormat 是 135，但 subFormat 却是 120 的」
        val restored = ConfigCodec.decode(
            mapOf("filmFormat" to "135", "subFormat" to "617"),
        )
        val cleaned = restored.sanitized()
        assertEquals("标准 36×24", cleaned.subFormat)
        assertTrue(cleaned.validate().isEmpty())
    }

    @Test
    fun `默认配置往返后仍是默认`() {
        assertEquals(SheetConfig(), ConfigCodec.decode(ConfigCodec.encode(SheetConfig())))
    }
}
