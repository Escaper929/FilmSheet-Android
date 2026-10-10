package com.filmsheet.android.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.core.PerfMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 配置存储的**真实往返**测试 — 需要设备。
 *
 * `ConfigCodecTests`（纯 JVM）验证的是编解码逻辑；这里验证的是
 * 「真的写进 SharedPreferences 再读出来还是那样」—— 那是 API 调用层面的事，
 * 逻辑测试验不了。
 */
@RunWith(AndroidJUnit4::class)
class ConfigStoreTests {

    private lateinit var store: ConfigStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = ConfigStore(context)
        store.clear()
    }

    @Test
    fun `空存储返回默认值`() {
        assertEquals(SheetConfig(), store.load())
    }

    @Test
    fun `往返后配置不变`() {
        val c = SheetConfig(
            thumbWidth = 800, spacing = 40, columns = 5,
            outputFormat = SheetConfig.OutputFormat.PNG, quality = 70,
            filmFormat = SheetConfig.FilmFormat.F120, subFormat = "617",
            infoLang = SheetConfig.InfoLang.ZH,
            packPosition = SheetConfig.PackPosition.RIGHT,
            renderStyle = SheetConfig.RenderStyle.CONTACT_SHEET,
            perfMode = PerfMode.BH,
            signature = "FilmSheet",
            info = mapOf(SheetConfig.InfoKey.ROLL to "R-07"),
        )
        store.save(c)
        assertEquals(c, store.load())
    }

    /**
     * 保存后再加载，会跑一次 `sanitized()` ——
     * 所以「非法但被清洗过」的组合应当以清洗后的形态存在。
     */
    @Test
    fun `加载时自动清洗`() {
        // thumbWidth 超出范围，但 sanitized() 会夹住
        store.save(SheetConfig(thumbWidth = 9999))
        assertEquals(SheetConfig.THUMB_WIDTH_MAX, store.load().thumbWidth)
        assertTrue(store.load().validate().isEmpty())
    }

    /** 包装图不持久化 —— 每次会话重新挑。 */
    @Test
    fun `包装图不被保存`() {
        store.save(SheetConfig(packImageUri = "content://media/external/images/media/42"))
        assertNull(store.load().packImageUri)
    }

    /**
     * 清空某个信息字段后，那个键必须真的消失。
     *
     * 信息字段是逐个键存的，若删除逻辑有漏，下次加载时用户已经清掉的
     * 「卷号」会自己冒回来。
     */
    @Test
    fun `清空信息字段后不会复活`() {
        val withRoll = SheetConfig()
            .withValue(SheetConfig.InfoKey.ROLL, "12")
            .withValue(SheetConfig.InfoKey.LAB, "诺丁山")
        store.save(withRoll)
        assertEquals("12", store.load().value(SheetConfig.InfoKey.ROLL))

        // 只留「冲洗地点」
        store.save(SheetConfig().withValue(SheetConfig.InfoKey.LAB, "诺丁山"))
        val after = store.load()
        assertEquals("", after.value(SheetConfig.InfoKey.ROLL))
        assertEquals("诺丁山", after.value(SheetConfig.InfoKey.LAB))
        assertEquals(1, after.info.size)
    }

    /** 连续多次保存不累积残留键。 */
    @Test
    fun `多次保存不累积残留`() {
        repeat(5) { i ->
            store.save(SheetConfig(columns = i + 1, signature = "sig$i"))
        }
        val c = store.load()
        assertEquals(5, c.columns)
        assertEquals("sig4", c.signature)
        assertTrue(c.info.isEmpty())
    }

    /** clear 之后回到默认值。 */
    @Test
    fun `清除后回到默认`() {
        store.save(SheetConfig(thumbWidth = 900, signature = "x"))
        store.clear()
        assertEquals(SheetConfig(), store.load())
    }
}
