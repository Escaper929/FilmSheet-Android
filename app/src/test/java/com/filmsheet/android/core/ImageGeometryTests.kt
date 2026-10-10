package com.filmsheet.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图像几何算术测试 — SPEC.md §9 与 §3。
 *
 * 裁切算错了不会崩，只会让照片被裁歪一点 —— 肉眼看出「不对劲」，
 * 但极难定位到是哪一行。所以这些矩形必须逐像素验证。
 *
 * 每条不变量都被单独钉死：矩形必须**完全落在源图内**、宽高必须**匹配
 * 目标比**、且**居中**。任何一条被破坏，照片就会被裁掉边或者偏心。
 */
class ImageGeometryTests {

    // MARK: - 居中裁切

    @Test
    fun `同宽高比时不裁`() {
        // 400×267 约等于 3:2，裁到 1.5 应该几乎不损失
        val r = ImageGeometry.centerCropToRatio(600, 400, 1.5)!!
        assertEquals(600, r.width)
        assertEquals(400, r.height)
        assertEquals(0, r.x)
        assertEquals(0, r.y)
    }

    @Test
    fun `偏宽的源图裁两侧`() {
        // 1000×600 裁到 1.0（正方形）：宽要收到 600，两边各去 200
        val r = ImageGeometry.centerCropToRatio(1000, 600, 1.0)!!
        assertEquals(600, r.width)
        assertEquals(600, r.height)
        assertEquals(200, r.x)
        assertEquals(0, r.y)
    }

    @Test
    fun `偏高的源图裁上下`() {
        // 600×1000 裁到 1.0：高要收到 600，上下各去 200
        val r = ImageGeometry.centerCropToRatio(600, 1000, 1.0)!!
        assertEquals(600, r.width)
        assertEquals(600, r.height)
        assertEquals(0, r.x)
        assertEquals(200, r.y)
    }

    /** 奇数余量时两边各舍掉一半，不偏向任何一侧。 */
    @Test
    fun `居中不偏心`() {
        val r = ImageGeometry.centerCropToRatio(1001, 1000, 1.0)!!
        val leftGap = r.x
        val rightGap = 1001 - r.right
        assertTrue("左 $leftGap 右 $rightGap 相差过大", kotlin.math.abs(leftGap - rightGap) <= 1)
    }

    /** 核心不变量：裁切矩形必须完整落在源图内，越界会让 createBitmap 崩。 */
    @Test
    fun `裁切矩形始终在源图内`() {
        for (sw in listOf(1, 7, 63, 640, 4000, 6001)) {
            for (sh in listOf(1, 5, 48, 480, 3000, 4001)) {
                for (ratio in listOf(0.5, 0.75, 1.0, 1.5, 2.78, 3.0)) {
                    val r = ImageGeometry.centerCropToRatio(sw, sh, ratio) ?: continue
                    val ctx = "${sw}x$sh ratio=$ratio → $r"
                    assertTrue("x 越界 $ctx", r.x >= 0)
                    assertTrue("y 越界 $ctx", r.y >= 0)
                    assertTrue("宽越界 $ctx", r.width in 1..sw)
                    assertTrue("高越界 $ctx", r.height in 1..sh)
                    assertTrue("右边越界 $ctx", r.right <= sw)
                    assertTrue("下边越界 $ctx", r.bottom <= sh)
                }
            }
        }
    }

    /** 裁完的宽高比必须真的等于目标比（允许 1 像素以内的取整误差）。 */
    @Test
    fun `裁后宽高比匹配目标`() {
        for (ratio in listOf(0.5, 0.75, 1.0, 1.5, 2.78, 3.0)) {
            for ((sw, sh) in listOf(1000 to 800, 800 to 1000, 4032 to 3024, 3024 to 4032)) {
                val r = ImageGeometry.centerCropToRatio(sw, sh, ratio)!!
                val actual = r.width.toDouble() / r.height
                val relErr = kotlin.math.abs(actual - ratio) / ratio
                assertTrue(
                    "期望 $ratio 实得 $actual（${sw}x$sh）",
                    relErr < 0.02,
                )
            }
        }
    }

    @Test
    fun `非法输入返回空`() {
        assertNull(ImageGeometry.centerCropToRatio(0, 100, 1.0))
        assertNull(ImageGeometry.centerCropToRatio(100, 0, 1.0))
        assertNull(ImageGeometry.centerCropToRatio(100, 100, 0.0))
        assertNull(ImageGeometry.centerCropToRatio(100, 100, -1.0))
    }

    /** 1×1 这种极端输入不能返回零面积，否则 createBitmap 抛异常。 */
    @Test
    fun `极小图也能给出合法矩形`() {
        val r = ImageGeometry.centerCropToRatio(1, 1, 3.0)
        assertNotNull(r)
        assertEquals(1, r!!.width)
        assertEquals(1, r.height)
    }

    // MARK: - 盖满裁切

    @Test
    fun `盖满裁切保留目标比例`() {
        // 1000×1000 盖到 400×300：需按宽缩放，可见区是 1000×750
        val r = ImageGeometry.coverCrop(1000, 1000, 400, 300)!!
        assertEquals(1000, r.width)
        assertEquals(750, r.height)
        assertEquals(0, r.x)
        assertEquals(125, r.y)
    }

    @Test
    fun `盖满裁切去掉多余的一边`() {
        // 800×600 盖到 300×300：按高缩放，可见区是 800×600 全保留？
        // 不 —— 目标是方的，按 max(300/800, 300/600)=0.5 缩，可见 600×600
        val r = ImageGeometry.coverCrop(800, 600, 300, 300)!!
        assertEquals(600, r.width)
        assertEquals(600, r.height)
        assertEquals(100, r.x)
        assertEquals(0, r.y)
    }

    /** 盖满裁切也必须在源图内，且居中。 */
    @Test
    fun `盖满裁切矩形始终在源图内且居中`() {
        for ((sw, sh) in listOf(
            100 to 100, 1000 to 600, 600 to 1000,
            4032 to 3024, 3024 to 4032, 1 to 1, 3 to 7,
        )) {
            for ((tw, th) in listOf(
                400 to 300, 400 to 266, 100 to 400, 1200 to 400, 1 to 1,
            )) {
                val r = ImageGeometry.coverCrop(sw, sh, tw, th)!!
                val ctx = "${sw}x$sh → ${tw}x$th 得 $r"
                assertTrue("x 越界 $ctx", r.x >= 0)
                assertTrue("y 越界 $ctx", r.y >= 0)
                assertTrue("右边越界 $ctx", r.right <= sw)
                assertTrue("下边越界 $ctx", r.bottom <= sh)
                assertTrue("宽越界 $ctx", r.width in 1..sw)
                assertTrue("高越界 $ctx", r.height in 1..sh)

                // 「盖满」的几何含义：先按 scale = max(目标/源) 缩放，
                // 缩放后**至少一边**恰好等于目标框、另一边超出，
                // 所以可见区 = min(源边, 目标边/scale)，且必须居中取全。
                //
                // 这里比的是「源图上可见多少像素」与理论值是否一致 ——
                // 不要比缩放后的尺寸，那会把自己绕进去。
                val scale = maxOf(tw.toDouble() / sw, th.toDouble() / sh)
                val wantW = minOf(sw.toDouble(), tw / scale)
                val wantH = minOf(sh.toDouble(), th / scale)
                assertTrue(
                    "$ctx：可见宽 ${r.width} 应为 $wantW",
                    kotlin.math.abs(r.width - wantW) <= 1.0,
                )
                assertTrue(
                    "$ctx：可见高 ${r.height} 应为 $wantH",
                    kotlin.math.abs(r.height - wantH) <= 1.0,
                )
            }
        }
    }

    @Test
    fun `盖满裁切非法输入返回空`() {
        assertNull(ImageGeometry.coverCrop(0, 100, 10, 10))
        assertNull(ImageGeometry.coverCrop(100, 100, 0, 10))
        assertNull(ImageGeometry.coverCrop(100, 100, 10, 0))
    }

    // MARK: - 135 转向

    @Test
    fun `标准135把竖幅转成横幅`() {
        assertEquals(
            ImageGeometry.Rotation.CW90,
            ImageGeometry.rotation135(3000, 4000, 1.5, forceLandscape = true),
        )
    }

    @Test
    fun `半格135把横幅转成竖幅`() {
        // 半格是 18×24，ratio < 1 → 期望竖幅
        assertEquals(
            ImageGeometry.Rotation.CCW90,
            ImageGeometry.rotation135(4000, 3000, 18.0 / 24.0, forceLandscape = true),
        )
    }

    /** 方向已经对时不该转 —— 多转一次就是 90 度。 */
    @Test
    fun `方向已对不动`() {
        assertEquals(
            ImageGeometry.Rotation.NONE,
            ImageGeometry.rotation135(4000, 3000, 1.5, forceLandscape = true),
        )
        assertEquals(
            ImageGeometry.Rotation.NONE,
            ImageGeometry.rotation135(3000, 4000, 18.0 / 24.0, forceLandscape = true),
        )
    }

    /** 方画幅无方向可言。 */
    @Test
    fun `方画幅永不转向`() {
        assertEquals(
            ImageGeometry.Rotation.NONE,
            ImageGeometry.rotation135(3000, 4000, 1.0, forceLandscape = true),
        )
    }

    @Test
    fun `不强制横构图时不转向`() {
        assertEquals(
            ImageGeometry.Rotation.NONE,
            ImageGeometry.rotation135(3000, 4000, 1.5, forceLandscape = false),
        )
    }

    // MARK: - 120 转向

    /** 645 是竖拍：横幅输入要转；而 66 是横拍：竖幅输入要转。条件相反。 */
    @Test
    fun `645与66的转向条件相反`() {
        // 645（竖拍）遇到横幅输入
        assertEquals(
            ImageGeometry.Rotation.CW90,
            ImageGeometry.rotation120(4000, 3000, isPortraitFormat = true, forceLandscape = true),
        )
        // 66（横拍）遇到竖幅输入
        assertEquals(
            ImageGeometry.Rotation.CW90,
            ImageGeometry.rotation120(3000, 4000, isPortraitFormat = false, forceLandscape = true),
        )
    }

    /** 方向已对就不动。 */
    @Test
    fun `120方向已对不动`() {
        assertEquals(
            ImageGeometry.Rotation.NONE,
            ImageGeometry.rotation120(3000, 4000, isPortraitFormat = true, forceLandscape = true),
        )
        assertEquals(
            ImageGeometry.Rotation.NONE,
            ImageGeometry.rotation120(4000, 3000, isPortraitFormat = false, forceLandscape = true),
        )
    }

    @Test
    fun `120不强制时不转向`() {
        assertEquals(
            ImageGeometry.Rotation.NONE,
            ImageGeometry.rotation120(4000, 3000, isPortraitFormat = true, forceLandscape = false),
        )
    }

    /** 正方形输入两种格式都不该动 —— 转了也是 90 度白转。 */
    @Test
    fun `120方形输入不动`() {
        assertEquals(
            ImageGeometry.Rotation.NONE,
            ImageGeometry.rotation120(3000, 3000, isPortraitFormat = true, forceLandscape = true),
        )
        assertEquals(
            ImageGeometry.Rotation.NONE,
            ImageGeometry.rotation120(3000, 3000, isPortraitFormat = false, forceLandscape = true),
        )
    }

    @Test
    fun `旋转90度交换宽高`() {
        val (w, h) = ImageGeometry.rotatedSize(3000, 4000, ImageGeometry.Rotation.CW90)
        assertEquals(4000, w)
        assertEquals(3000, h)
        val (w2, h2) = ImageGeometry.rotatedSize(3000, 4000, ImageGeometry.Rotation.NONE)
        assertEquals(3000, w2)
        assertEquals(4000, h2)
    }

    // MARK: - 期望画幅比

    @Test
    fun `135子格式比值与规格一致`() {
        assertEquals(1.5, ImageGeometry.expected135Ratio("标准 36×24"), 1e-9)
        assertEquals(18.0 / 24.0, ImageGeometry.expected135Ratio("半格 18×24"), 1e-9)
        assertEquals(1.0, ImageGeometry.expected135Ratio("方形 24×24"), 1e-9)
        assertEquals(65.0 / 24.0, ImageGeometry.expected135Ratio("XPan 65×24"), 1e-9)
    }

    /** 非法值必须回落到标准 36×24，不能给出一个乱七八糟的比值。 */
    @Test
    fun `135非法子格式回落到标准`() {
        assertEquals(1.5, ImageGeometry.expected135Ratio("bogus"), 1e-9)
        assertEquals(1.5, ImageGeometry.expected135Ratio(""), 1e-9)
        // 120 的子格式名传进来也要安全回落
        assertEquals(1.5, ImageGeometry.expected135Ratio("66"), 1e-9)
    }

    @Test
    fun `只有645是竖拍格式`() {
        assertTrue(ImageGeometry.isPortrait120Format("645"))
        for (name in listOf("66", "67", "68", "69", "612", "617", "624")) {
            assertTrue("$name 不该被判成竖拍", !ImageGeometry.isPortrait120Format(name))
        }
    }

    // MARK: - EXIF

    @Test
    fun `EXIF旋转角映射正确`() {
        assertEquals(90, ImageGeometry.degreesForExifOrientation(6))   // ROTATE_90
        assertEquals(180, ImageGeometry.degreesForExifOrientation(3))  // ROTATE_180
        assertEquals(270, ImageGeometry.degreesForExifOrientation(8))  // ROTATE_270
        assertEquals(0, ImageGeometry.degreesForExifOrientation(1))    // NORMAL
    }

    /** 镜像类取值一律归零 —— 胶片排版不处理镜像。 */
    @Test
    fun `EXIF镜像不当成旋转`() {
        for (orientation in listOf(2, 4, 5, 7, -1, 99)) {
            assertEquals(
                "orientation=$orientation 不该触发旋转",
                0,
                ImageGeometry.degreesForExifOrientation(orientation),
            )
        }
    }
}
