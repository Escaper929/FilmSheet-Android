package com.filmsheet.android.core

/**
 * 图像几何算术 — 与 [SheetLayout] 同层：纯计算，不碰 `android.graphics`。
 *
 * ## 为什么要拆出来
 *
 * `ImageOps` 里真正的几何只有两件事：**居中裁切到某个宽高比**，和
 * **盖满目标框后裁掉溢出**。两者都是整数像素的定点计算，而算错了不会
 * 崩、只会让照片被裁歪一点 —— 肉眼能看出「不对劲」，但极难定位。
 *
 * 把它们放到这里，就能被纯 JVM 单测逐个像素验证，不需要 Bitmap、不需要
 * Robolectric。`ImageOps` 只负责把算出来的矩形喂给 `Bitmap.createBitmap`。
 *
 * ## 截断语义（SPEC.md §9）
 *
 * 全部用 [Double.toInt]（向零截断），**不用 `Math.floor()`**。全部输入都是
 * 正数时两者等价，但一旦有负值（比如源图比目标还小导致 crop 尺寸为负），
 * `floor(-0.5) = -1` 而 `(-0.5).toInt() = 0`，差 1 会导致 `createBitmap`
 * 抛异常。
 */
object ImageGeometry {

    /** 一个居中裁切区域。坐标是源图上的像素。 */
    data class CropRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    ) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height
    }

    /**
     * 居中裁到 W/H = [ratio] — 对应桌面端 `image_pipeline._crop_to_ratio`
     * 的几何部分。
     *
     * 与源图同宽高比时返回全图（不裁）。源图偏宽则裁两侧，偏高则裁上下。
     */
    fun centerCropToRatio(srcW: Int, srcH: Int, ratio: Double): CropRect? {
        if (srcW <= 0 || srcH <= 0 || ratio <= 0.0) return null

        val iw = srcW.toDouble()
        val ih = srcH.toDouble()
        var cropW = iw
        var cropH = ih
        if (iw / ih > ratio) {
            cropW = ih * ratio
        } else {
            cropH = iw / ratio
        }

        // 向零截断，且必须夹在源图内 —— 浮点误差会让 cropW 略大于 srcW。
        val x = ((iw - cropW) / 2.0).toInt().coerceIn(0, srcW - 1)
        val y = ((ih - cropH) / 2.0).toInt().coerceIn(0, srcH - 1)
        val w = cropW.toInt().coerceIn(1, srcW - x)
        val h = cropH.toInt().coerceIn(1, srcH - y)
        return CropRect(x, y, w, h)
    }

    /**
     * 缩放到刚好盖住目标框，再居中裁掉溢出 ——
     * 桌面端 `image_pipeline.cover_resize_crop` 的几何部分。
     *
     * 这是放置照片那一步：目标框比例固定，源图比例不定，所以先算出
     * 「盖满后可见的那块源区域」。
     */
    fun coverCrop(srcW: Int, srcH: Int, targetW: Int, targetH: Int): CropRect? {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return null

        val iw = srcW.toDouble()
        val ih = srcH.toDouble()
        val scale = maxOf(targetW / iw, targetH / ih)

        val visibleW = minOf(iw, targetW / scale)
        val visibleH = minOf(ih, targetH / scale)

        val x = ((iw - visibleW) / 2.0).toInt().coerceIn(0, srcW - 1)
        val y = ((ih - visibleH) / 2.0).toInt().coerceIn(0, srcH - 1)
        val w = visibleW.toInt().coerceIn(1, srcW - x)
        val h = visibleH.toInt().coerceIn(1, srcH - y)
        return CropRect(x, y, w, h)
    }

    /** 目标框的宽高比 — [coverCrop] 里 `scale` 的等价物，供调用方复核。 */
    fun targetRatio(targetW: Int, targetH: Int): Double =
        if (targetH <= 0) 0.0 else targetW.toDouble() / targetH

    // MARK: - 转向判定

    /** 需要的转向。 */
    enum class Rotation { NONE, CW90, CCW90 }

    /**
     * 135 的转向判定 — 桌面端 `image_pipeline._maybe_rotate_135`。
     *
     * 转向由**子格式**决定，不是全局的「横构图」开关：
     * 半格（18×24）是竖幅，标准（36×24）是横幅，同一个用户偏好下
     * 两者的转向规则相反。
     *
     * @param forceLandscape 用户是否勾了「强制横构图」
     * @param expectedRatio 目标画幅比，< 1 竖幅、> 1 横幅、== 1 方画幅
     */
    fun rotation135(
        srcW: Int,
        srcH: Int,
        expectedRatio: Double,
        forceLandscape: Boolean,
    ): Rotation {
        if (!forceLandscape || expectedRatio == 1.0) return Rotation.NONE
        return when {
            expectedRatio > 1.0 && srcH > srcW -> Rotation.CW90
            expectedRatio < 1.0 && srcW > srcH -> Rotation.CCW90
            else -> Rotation.NONE
        }
    }

    /**
     * 120 的转向判定 — 桌面端 `image_pipeline.process_120_image`。
     *
     * 645 是**竖拍**格式（用户把相机竖着拿），其余是横拍。所以同样勾着
     * 「强制横构图」，645 与 66 的转向条件正好相反。
     *
     * 原始实现里两个分支都调 `rotate(-90, expand=True)`，即顺时针 90 度。
     */
    fun rotation120(
        srcW: Int,
        srcH: Int,
        isPortraitFormat: Boolean,
        forceLandscape: Boolean,
    ): Rotation {
        if (!forceLandscape) return Rotation.NONE
        return if (isPortraitFormat) {
            // 645：竖拍，横幅输入要转过来
            if (srcW > srcH) Rotation.CW90 else Rotation.NONE
        } else {
            // 66/67/68/69/612/617：横拍，竖幅输入要转过来
            if (srcH > srcW) Rotation.CW90 else Rotation.NONE
        }
    }

    /** 旋转 90 度会交换宽高 —— 布局与后续裁切都要用到。 */
    fun rotatedSize(w: Int, h: Int, rotation: Rotation): Pair<Int, Int> =
        if (rotation == Rotation.NONE) w to h else h to w

    // MARK: - 期望画幅比

    /** 桌面端 `image_pipeline._expected_135_ratio`。 */
    fun expected135Ratio(subFormat: String): Double = when (subFormat) {
        "半格 18×24" -> 18.0 / 24.0
        "方形 24×24" -> 1.0
        "XPan 65×24" -> 65.0 / 24.0
        else -> 36.0 / 24.0   // 标准 36×24 与非法值的回落
    }

    /** 桌面端 `image_pipeline._is_portrait_120_format` —— 只有 645 是竖拍。 */
    fun isPortrait120Format(subFormat: String): Boolean = subFormat == "645"

    /**
     * EXIF 旋转要多少度。
     *
     * 只认三个 ROTATE 值。FLIP_* 那几个涉及镜像，胶片排版不关心 ——
     * 相机不会产出镜像 JPEG，真出了也是上游在乱转。
     */
    fun degreesForExifOrientation(orientation: Int): Int = when (orientation) {
        6 -> 90     // ORIENTATION_ROTATE_90
        3 -> 180    // ORIENTATION_ROTATE_180
        8 -> 270    // ORIENTATION_ROTATE_270
        else -> 0   // 含 NORMAL 与所有 FLIP
    }
}
