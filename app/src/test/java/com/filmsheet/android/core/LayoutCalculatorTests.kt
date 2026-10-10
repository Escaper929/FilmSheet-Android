package com.filmsheet.android.core

import com.filmsheet.android.config.SheetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局算术测试 — 对应 SPEC.md §5。
 *
 * 这层是纯函数：配置 + 照片数 → 一张版式图。没有任何图形 API，
 * 所以它能被完整验证到每个像素 —— 这也是当初把它从渲染器里拆出来的原因。
 *
 * 断言写成**具体数值**而不是「公式等于自己」的自反式检查。
 * 那种检查永远通过，却挡不住任何漂移；这里的数字是桌面端与 iOS 端
 * 共同认可的结果，改动时应当先问「为什么变了」而不是直接更新期望值。
 */
class LayoutCalculatorTests {

    private val base = SheetConfig()

    // MARK: - 公共布局

    @Test
    fun `默认配置下画布尺寸符合规格`() {
        val l = LayoutCalculator.compute135(base, photoCount = 6)

        // scale = 400/400 = 1.0，所有 ×scale 常数就是字面值。
        assertEquals(50, l.sideMargin)
        assertEquals(25, l.topMargin)
        // 6 列 × 400 + 7 × 20 = 2540
        assertEquals(2540, l.contentW)
        // 2540 + 2×50 + 100 = 2740
        assertEquals(2740, l.totalW)
    }

    @Test
    fun `间距随缩略图宽度按比例放大`() {
        val l = LayoutCalculator.compute135(base.copy(thumbWidth = 800), photoCount = 6)
        // spacing = 20 × 800 / 400 = 40
        assertEquals(40, l.spacing)
        assertEquals(100, l.sideMargin)
        assertEquals(50, l.topMargin)
        // 6 × 800 + 7 × 40 = 5080
        assertEquals(5080, l.contentW)
    }

    @Test
    fun `无信息块时底边距是头部的两倍`() {
        val l = LayoutCalculator.compute135(base, photoCount = 6)
        // topRegion = 25 + 0 = 25 → bottomMargin = 50
        assertEquals(0, l.infoHeight)
        assertEquals(25, l.topRegionHeight())
        assertEquals(50, l.bottomMargin)
    }

    @Test
    fun `有信息块时底边距是头部的1_6倍`() {
        val c = base.withValue(SheetConfig.InfoKey.ROLL, "12")
        val l = LayoutCalculator.compute135(c, photoCount = 6)
        // 填了一行：topPad 20 + 1 × lineHeight 52 + bottomPad 15 = 87
        assertEquals(87, l.infoHeight)
        // topRegion = 25 + 87 = 112 → 112 × 1.6 = 179.2 → 179
        assertEquals(112, l.topRegionHeight())
        assertEquals(179, l.bottomMargin)
    }

    @Test
    fun `信息块高度按实际填了的行数增长`() {
        val one = base.withValue(SheetConfig.InfoKey.ROLL, "12")
        val two = one.withValue(SheetConfig.InfoKey.LAB, "诺丁山")
        val h1 = LayoutCalculator.compute135(one, 6).infoHeight
        val h2 = LayoutCalculator.compute135(two, 6).infoHeight
        assertEquals(87, h1)
        // 多一行就多一个 lineHeight
        assertEquals(h1 + 52, h2)
    }

    @Test
    fun `画布高度把胶片条与间距都算进去`() {
        val c = base.withValue(SheetConfig.InfoKey.ROLL, "12")
        val l = LayoutCalculator.compute135(c, photoCount = 6)
        // 135：scaleFactor = 400/36；stripH = 35 × 400/36 = 388.88 → 388
        // bagGap = 50 × 400/400 = 50
        assertEquals(388, l.stripH)
        assertEquals(50, l.bagGap)
        // 6 张 6 列 → 2 行（首行让出两个引带位）
        assertEquals(2, l.rows)
        // topArea = 25 + 87 + 65 = 177
        assertEquals(177, l.topAreaHeight)
        // totalH = 177 + 2×388 + 1×50 + 179 = 1182
        assertEquals(1182, l.totalH)
    }

    // MARK: - 135 几何

    @Test
    fun `画幅在35mm片条内垂直居中`() {
        val l = LayoutCalculator.compute135(base, photoCount = 6)
        // scaleFactor = 400/36 = 11.111
        // frameTopOffset = (35 − 24)/2 × 11.111 = 61.11 → 61
        assertEquals(61, l.frameTopOffsetPx)
        // frameH = 24 × 11.111 = 266.67 → 266
        assertEquals(266, l.frameHPx)
        // 画幅顶边 + 画幅高必须落在片条内
        assertTrue(l.frameTopOffsetPx + l.frameHPx <= l.stripH)
    }

    @Test
    fun `齿孔中心到片边的距离来自物理常量`() {
        val l = LayoutCalculator.compute135(base, photoCount = 6)
        // (edgeToPerf 2.01 + perfDimY/2 1.397) × 11.111 = 37.85 → 37
        assertEquals(37, l.perfCenterOffsetPx)
        // perfH = 2.794 × 11.111 = 31.04 → 31
        assertEquals(31, l.perfHPx)
    }

    @Test
    fun `齿孔类型从胶卷字段嗅探`() {
        val cinema = base.withValue(SheetConfig.InfoKey.FILM, "柯达 Vision3 500T")
        assertEquals(
            FilmEngine135.PerfType.BH,
            LayoutCalculator.compute135(cinema, 6).perfType,
        )
        val consumer = base.withValue(SheetConfig.InfoKey.FILM, "柯达 Portra 400")
        assertEquals(
            FilmEngine135.PerfType.KS,
            LayoutCalculator.compute135(consumer, 6).perfType,
        )
    }

    @Test
    fun `显式perfMode覆盖嗅探结果`() {
        val cinema = base.withValue(SheetConfig.InfoKey.FILM, "5207")
        val l = LayoutCalculator.compute135(cinema.copy(perfMode = PerfMode.KS), 6)
        assertEquals(FilmEngine135.PerfType.KS, l.perfType)
    }

    @Test
    fun `子格式改变画幅尺寸`() {
        val half = LayoutCalculator.compute135(base.copy(subFormat = "半格 18×24"), 6)
        assertEquals(18.0, half.frameWMM, 1e-9)
        assertEquals(4, half.perfsPerFrame)
        // scaleFactor = 400/18，画幅宽仍是 thumbWidth
        assertEquals(400, half.frameWPx)

        val xpan = LayoutCalculator.compute135(base.copy(subFormat = "XPan 65×24"), 6)
        assertEquals(65.0, xpan.frameWMM, 1e-9)
        assertEquals(14, xpan.perfsPerFrame)
    }

    @Test
    fun `单列进入单张模式`() {
        val l = LayoutCalculator.compute135(base.copy(columns = 1), photoCount = 1)
        assertTrue(l.isSinglePhoto)
        assertEquals(1, l.rows)
        // 注意：135 只有在 singlePhotoMode 打开时才把间距归零，
        // 「列数 = 1」本身不归零 —— 与 iOS 端 Renderer135 一致。
        assertEquals(20, l.spacing)
    }

    @Test
    fun `135单张模式把间距归零`() {
        val l = LayoutCalculator.compute135(
            base.copy(columns = 1, singlePhotoMode = true, singlePhotoShowExtra = true),
            photoCount = 1,
        )
        assertEquals(0, l.spacing)
        assertTrue(l.isSinglePhoto)
    }

    @Test
    fun `单张模式不要额外内容时画布只剩胶片条`() {
        val c = base.copy(
            columns = 1,
            singlePhotoMode = true,
            singlePhotoShowExtra = false,
        )
        val l = LayoutCalculator.compute135(c, photoCount = 1)
        assertEquals(400, l.frameWPx)
        assertEquals(420, l.totalW)   // 400 + 2 × 10
        assertEquals(l.stripH, l.totalH)
        assertEquals(0, l.topMargin)
        assertEquals(0, l.infoHeight)
    }

    @Test
    fun `单张模式要额外内容时上下对称边距`() {
        val c = base.copy(
            columns = 1,
            singlePhotoMode = true,
            singlePhotoShowExtra = true,
        )
        val l = LayoutCalculator.compute135(c, photoCount = 1)
        assertEquals(25, l.topMargin)
        assertEquals(25, l.bottomMargin)
        assertEquals(90, l.topAreaHeight)   // 25 + 65
        assertTrue(l.infoHeight == 0)
    }

    // MARK: - 120 几何

    @Test
    fun `120的缩略图宽度映射到画幅宽而非衬纸宽`() {
        // 这是 120 与 135 最容易搞错的地方：thumbWidth → 画幅宽，
        // 衬纸宽（61mm）是胶片条的**高度**。
        val l = LayoutCalculator.compute120(base.copy(subFormat = "66"), photoCount = 6)
        // 66 的画幅宽 56mm → scaleFactor = 400/56
        assertEquals(61.0 * 400 / 56, l.stripH.toDouble(), 1.0)
        // 画幅高固定 56mm
        assertEquals((56.0 * 400 / 56).toInt(), l.imgH)
        // 画幅宽就是 thumbWidth 本身
        assertEquals(400, l.frameWPx)
    }

    @Test
    fun `120没有齿孔但有上下衬纸边`() {
        val l = LayoutCalculator.compute120(base.copy(subFormat = "66"), photoCount = 6)
        // 上下边框 (61−56)/2 = 2.5mm
        assertEquals((2.5 * 400 / 56).toInt(), l.imageBorder)
        // 画面必须完整落在衬纸内
        assertTrue(l.imageBorder * 2 + l.imgH <= l.stripH)
    }

    @Test
    fun `120子格式改变缩放系数`() {
        val n645 = LayoutCalculator.compute120(base.copy(subFormat = "645"), 6)
        val n617 = LayoutCalculator.compute120(base.copy(subFormat = "617"), 6)
        // 617 的画幅宽 168mm，比 645 的 41.5mm 大得多 → 缩放系数小得多
        assertTrue(n617.stripH < n645.stripH)
        assertEquals(400, n617.frameWPx)
    }

    /**
     * 120 单列排下全部照片。
     *
     * 行数等于照片数，所以画布必须按**全部行**算高。
     * iOS 端 `Renderer120.computeLayout` 对 `cols == 1` 无条件设
     * `totalH = stripH`，6 张照片只留 1 行的高度，后 5 行被静默裁掉 ——
     * 这条测试就是钉住那个缺陷的。
     */
    @Test
    fun `120单列排下全部照片`() {
        val l = LayoutCalculator.compute120(base.copy(columns = 1), photoCount = 6)
        assertEquals(6, l.rows)
        // 多行单列不算「单张模式」，胶片条要完整画出来
        assertTrue(!l.isSinglePhoto)
        assertTrue(l.rowBottom(l.rows - 1) <= l.totalH)
    }

    @Test
    fun `120单列单张时才压缩成一条`() {
        val l = LayoutCalculator.compute120(base.copy(columns = 1), photoCount = 1)
        assertEquals(1, l.rows)
        assertTrue(l.isSinglePhoto)
        assertEquals(420, l.totalW)
        assertEquals(l.stripH, l.totalH)
        assertEquals(0, l.spacing)
    }

    /** 多行单列仍需正常间距，否则画面会紧贴画布左右边缘。 */
    @Test
    fun `120单列多行保留间距`() {
        val l = LayoutCalculator.compute120(base.copy(columns = 1), photoCount = 6)
        assertEquals(20, l.spacing)
    }

    @Test
    fun `120的行数不含引带`() {
        // 6 张 6 列在 135 需要 2 行（引带），120 只要 1 行。
        assertEquals(2, LayoutCalculator.compute135(base, 6).rows)
        assertEquals(1, LayoutCalculator.compute120(base, 6).rows)
    }

    // MARK: - 分派

    @Test
    fun `按片基分派到正确的计算器`() {
        assertEquals(
            LayoutCalculator.compute135(base, 6),
            LayoutCalculator.compute(base, 6),
        )
        val c120 = base.copy(filmFormat = SheetConfig.FilmFormat.F120, subFormat = "66")
        assertEquals(
            LayoutCalculator.compute120(c120, 6),
            LayoutCalculator.compute(c120, 6),
        )
    }

    // MARK: - 派生量

    @Test
    fun `行顶边按条高加间距推进`() {
        val l = LayoutCalculator.compute135(base, photoCount = 12)
        val y0 = l.rowTop(0)
        assertEquals(l.topAreaHeight, y0)
        assertEquals(y0 + l.stripH, l.rowBottom(0))
        // 第二行往下推一个条高加一个间距
        assertEquals(y0 + l.stripH + l.bagGap, l.rowTop(1))
    }

    /** 列中心用**实际画幅宽**，不是 thumbWidth —— 错了片边帧号就会与照片错位。 */
    @Test
    fun `列中心按实际画幅宽计算`() {
        val l = LayoutCalculator.compute135(base, photoCount = 6)
        // 第 0 列：sideMargin 50 + spacing 20 + 0 + 400/2 = 270
        assertEquals(270.0, l.columnCenter(0, l.frameWPx), 1e-9)
        // 第 1 列：多一个 (400 + 20)
        assertEquals(270.0 + 420, l.columnCenter(1, l.frameWPx), 1e-9)
    }

    @Test
    fun `半格子格式的列中心会左移`() {
        // 半格的画幅宽也是 thumbWidth（scaleFactor 变了），但间距计算基于它，
        // 所以这里主要验证 frameWPx 始终等于 thumbWidth。
        val l = LayoutCalculator.compute135(base.copy(subFormat = "半格 18×24"), 6)
        assertEquals(l.thumbW, l.frameWPx)
    }

    // MARK: - 包装图

    @Test
    fun `左侧包装图给出文字右边界`() {
        val c = base.withValue(SheetConfig.InfoKey.ROLL, "12")
        val l = LayoutCalculator.compute135(c, 6)
        val pack = LayoutCalculator.placePackImage(c, l, packPixelW = 800, packPixelH = 1200)
        assertNotNull(pack)
        assertNotNull(pack!!.packRight)
        assertNull(pack.packLeft)
        assertTrue(pack.packRight!! > l.sideMargin)
    }

    @Test
    fun `右侧包装图给出文字左边界`() {
        val c = base.withValue(SheetConfig.InfoKey.ROLL, "12")
            .copy(packPosition = SheetConfig.PackPosition.RIGHT)
        val l = LayoutCalculator.compute135(c, 6)
        val pack = LayoutCalculator.placePackImage(c, l, packPixelW = 800, packPixelH = 1200)
        assertNotNull(pack)
        assertNotNull(pack!!.packLeft)
        assertNull(pack.packRight)
        assertTrue(pack.packLeft!! < l.totalW - l.sideMargin)
    }

    @Test
    fun `包装图宽度不超过画布的35%`() {
        val c = base.withValue(SheetConfig.InfoKey.ROLL, "12")
            .copy(thumbWidth = 1200, packSize = 100)
        val l = LayoutCalculator.compute135(c, 6)
        // 一个极宽的包装图必须被 35% 上限收住
        val pack = LayoutCalculator.placePackImage(c, l, packPixelW = 4000, packPixelH = 400)
        assertNotNull(pack)
        assertTrue(pack!!.width <= (l.totalW * 0.35).toInt())
    }

    @Test
    fun `没有信息块时不画包装图`() {
        val l = LayoutCalculator.compute135(base, 6)
        assertNull(LayoutCalculator.placePackImage(base, l, 800, 1200))
    }

    @Test
    fun `关闭边框时描边宽度为零`() {
        val c = base.withValue(SheetConfig.InfoKey.ROLL, "12")
            .copy(packBorderStroke = false)
        val l = LayoutCalculator.compute135(c, 6)
        val pack = LayoutCalculator.placePackImage(c, l, 800, 1200)
        assertEquals(0, pack!!.border)

        val withBorder = LayoutCalculator.placePackImage(
            base.withValue(SheetConfig.InfoKey.ROLL, "12"), l, 800, 1200,
        )
        assertTrue(withBorder!!.border > 0)
    }

    // MARK: - 不变量

    /** 画布必须装得下所有胶片条，否则最后一行会被裁掉。 */
    @Test
    fun `画布总能装下所有行`() {
        for (thumbW in listOf(300, 400, 700, 1200)) {
            for (cols in listOf(1, 2, 6, 12)) {
                for (n in listOf(1, 6, 24, 72, 200)) {
                    for (fmt in listOf(
                        SheetConfig.FilmFormat.F135,
                        SheetConfig.FilmFormat.F120,
                    )) {
                        val c = base.copy(
                            thumbWidth = thumbW,
                            columns = cols,
                            filmFormat = fmt,
                            subFormat = if (fmt == SheetConfig.FilmFormat.F120) "66" else "标准 36×24",
                        )
                        val l = LayoutCalculator.compute(c, n)
                        val stripsBottom = l.rowBottom(l.rows - 1)
                        assertTrue(
                            "thumbW=$thumbW cols=$cols n=$n $fmt：行底 $stripsBottom 超出画布 ${l.totalH}",
                            stripsBottom <= l.totalH,
                        )
                        assertTrue(
                            "thumbW=$thumbW cols=$cols n=$n $fmt：内容宽 ${l.contentW} 超出画布 ${l.totalW}",
                            l.contentW <= l.totalW,
                        )
                    }
                }
            }
        }
    }

    /** 每一列的画面都必须落在画布内，否则边缘的列会被裁掉一半。 */
    @Test
    fun `每一列都落在画布内`() {
        for (cols in listOf(1, 2, 6, 12)) {
            for (sub in listOf("标准 36×24", "半格 18×24", "XPan 65×24")) {
                val c = base.copy(columns = cols, subFormat = sub)
                val l = LayoutCalculator.compute135(c, 12)
                val left = l.columnCenter(0, l.frameWPx) - l.frameWPx / 2.0
                val right = l.columnCenter(cols - 1, l.frameWPx) + l.frameWPx / 2.0
                assertTrue("$sub cols=$cols 左溢出 $left", left >= 0)
                assertTrue("$sub cols=$cols 右溢出 $right / ${l.totalW}", right <= l.totalW)
            }
        }
    }

    private fun SheetLayout.topRegionHeight(): Int = topMargin + infoHeight
}
