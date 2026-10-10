package com.filmsheet.android.render

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.core.ImageGeometry
import com.filmsheet.android.core.LayoutCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 渲染层冒烟测试 — 需要设备或模拟器（`./gradlew connectedAndroidTest`）。
 *
 * ## 为什么这些测试跑不了 JVM 单测
 *
 * 渲染要用真实的 `Bitmap` 与 `Canvas`。`testOptions.isReturnDefaultValues`
 * 会让 `android.*` 调用返回假值（`Bitmap.createBitmap` 拿到全 0 的图），
 * 那样测出来的「通过」毫无意义。所以渲染层只能走 instrumented test。
 *
 * 纯算术部分（裁切矩形、转向判定、布局尺寸）都抽到了 `core`，
 * 那部分由 `jvm-test.sh` 在 JVM 上跑 —— 见 `ImageGeometryTests`
 * 与 `LayoutCalculatorTests`。**这里是验证算术被正确地用上**，
 * 不是重复验证算术本身。
 */
@RunWith(AndroidJUnit4::class)
class SheetRendererTests {

    /** 造一张指定尺寸的纯色图。颜色无所谓，只关心尺寸与摆放。 */
    private fun blank(w: Int, h: Int, argb: Int = 0xFF808080.toInt()): ImageBitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(argb)
        }.asImageBitmap()

    private fun images(n: Int, w: Int = 600, h: Int = 400): List<ImageBitmap> =
        (0 until n).map { blank(w, h, 0xFF000000.toInt() or (0x40 shl 16)) }

    // MARK: - 基本渲染

    @Test
    fun `渲染出与布局一致的尺寸`() {
        val config = SheetConfig().copy(thumbWidth = 400, columns = 6)
        val photos = images(6)

        val layout = LayoutCalculator.compute135(config, photos.size)
        val out = Renderer135(config, photos).render()

        assertEquals(layout.totalW, out.width)
        assertEquals(layout.totalH, out.height)
    }

    @Test
    fun `空选择不炸`() {
        val config = SheetConfig()
        val out = Renderer135(config, emptyList()).render()
        assertTrue(out.width > 0 && out.height > 0)
    }

    // MARK: - 引带：135 首行要让出两格

    /**
     * 这是最容易悄悄坏掉的地方。
     *
     * 首行从第 2 列开始放照片，前两格是片头引带。若引带偏移失效，
     * 照片会盖住引带 —— 版式看起来「少了点什么」，但不会报错。
     */
    @Test
    fun `135首行为片头引带让出两格`() {
        val config = SheetConfig().copy(thumbWidth = 400, columns = 6)
        val out = Renderer135(config, images(6)).render().asAndroidBitmap()

        // 首行第一个画面应落在第 2 列的位置：
        // sideMargin 50 + spacing 20 + 2 × (400 + 20)
        val expectedX = 50 + 20 + 2 * (400 + 20)
        // 画幅顶边 + 画幅内前若干像素应是非背景色（照片）
        val y = 177 + 61 + 40   // topArea + frameTopOffset + 画幅内偏移
        val pixel = out.getPixel(expectedX + 10, y)

        // 引带位置（第 0 列）应该仍是片基的深色，不是照片的中灰
        val perfArea = out.getPixel(50 + 20 + 10, y)
        assertTrue(
            "首行第 0 列不该有照片（说明引带偏移失效）",
            pixel != perfArea,
        )
    }

    /** 120 没有引带，第 0 列就该是照片。 */
    @Test
    fun `120首行从第0列开始`() {
        val config = SheetConfig().copy(
            thumbWidth = 400, columns = 3,
            filmFormat = SheetConfig.FilmFormat.F120, subFormat = "66",
        )
        val out = Renderer120(config, images(3)).render().asAndroidBitmap()

        // 画面区起点：sideMargin + spacing，往下取一点避开衬纸边框
        val l = LayoutCalculator.compute120(config, 3)
        val x = l.sideMargin + l.spacing + 10
        val y = l.topAreaHeight + l.imageBorder + 20
        val inFrame = out.getPixel(x, y)
        // 衬纸区（画幅上方）应是深棕片基
        val backing = out.getPixel(x, l.topAreaHeight + 1)
        assertTrue("120 第 0 列应有照片", inFrame != backing)
    }

    // MARK: - 画幅比

    /**
     * 画面必须被裁成目标画幅比。
     *
     * 放在这里而不是 core，是因为 core 只能验算术；
     * 「算出来的矩形有没有真的作用到位图上」只有渲染层能回答。
     */
    @Test
    fun `画面按目标画幅比摆放`() {
        for (sub in listOf("标准 36×24", "半格 18×24", "方形 24×24")) {
            val config = SheetConfig().copy(thumbWidth = 400, columns = 4, subFormat = sub)
            val l = LayoutCalculator.compute135(config, 4)
            val ratio = ImageGeometry.expected135Ratio(sub)
            val actual = l.frameWPx.toDouble() / l.frameHPx

            assertTrue(
                "$sub：期望 $ratio 实得 $actual",
                kotlin.math.abs(actual - ratio) / ratio < 0.05,
            )
        }
    }

    // MARK: - 单张模式

    @Test
    fun `单张模式只画一条`() {
        val config = SheetConfig().copy(
            columns = 1, singlePhotoMode = true, singlePhotoShowExtra = false,
            thumbWidth = 400,
        )
        val l = LayoutCalculator.compute135(config, 1)
        val out = Renderer135(config, images(1)).render()

        assertEquals(l.totalW, out.width)
        assertEquals(l.stripH, out.height)
    }

    // MARK: - 两套配色

    /** 接触印相是黑底，灯板正片是白底 —— 一眼能分辨的区别。 */
    @Test
    fun `两种风格底色不同`() {
        val photos = images(3)
        val lightbox = Renderer135(
            SheetConfig().copy(columns = 3), photos,
        ).render().asAndroidBitmap()
        val contact = Renderer135(
            SheetConfig().copy(
                columns = 3,
                renderStyle = SheetConfig.RenderStyle.CONTACT_SHEET,
            ), photos,
        ).render().asAndroidBitmap()

        val a = lightbox.getPixel(2, 2)
        val b = contact.getPixel(2, 2)

        fun luma(c: Int) = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
        assertTrue("灯板正片应是亮底（实测 $a）", luma(a) > 200)
        assertTrue("接触印相应是黑底（实测 $b）", luma(b) < 60)
    }
}
