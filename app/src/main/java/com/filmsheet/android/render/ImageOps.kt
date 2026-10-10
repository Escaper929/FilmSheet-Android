package com.filmsheet.android.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.core.ImageGeometry
import kotlin.math.max

/**
 * 图像预处理 — 移植自 iOS 端 `ImageOps.swift`
 * （桌面端 `processor/image_pipeline.py`）。
 *
 * 每帧的流水线：
 * ```
 * 解码 → 归一化方向 → 可选反相 → 按子格式的期望画幅转向 → 居中裁到画幅比 → 缩到缩略图宽
 * ```
 *
 * 桌面端用 PIL 的 LANCZOS 做缩小。iOS 端靠 ImageIO 的
 * `kCGImageSourceCreateThumbnailWithTransform` 把解码与降采样合成一次
 * 硬件加速的遍历，既更快，大比例缩小时质量也更好。
 * Android 端用 `inSampleSize` + `inScaled` 达到同样效果。
 *
 * **注意**：方向校正靠 EXIF 而不是解码参数。`inSampleSize` 不处理 EXIF 旋转，
 * 相机 JPEG 会躺着进来 —— 这是最容易出的错。
 */
object ImageOps {

    // MARK: - 期望宽高比

    // 下面三个都是转发给 [ImageGeometry] —— 几何判定是纯算术，放在 core 才能被
    // 纯 JVM 单测覆盖。留在这一层等于把同一份逻辑写两遍，而两份迟早会漂移。

    fun expected135Ratio(subFormat: String): Double =
        ImageGeometry.expected135Ratio(subFormat)

    fun isPortrait120Format(subFormat: String): Boolean =
        ImageGeometry.isPortrait120Format(subFormat)

    fun ratio120(subFormat: String): Double =
        SheetConfig.filmFormatRatios[subFormat] ?: 1.0

    // MARK: - 解码 + 方向

    /**
     * 解码并应用 EXIF 方向，长边降到不超过 [maxPixel]。
     *
     * 只解出缩略图够用的尺寸：一张 6000×4000 的原图解成 Bitmap 要占
     * 96 MB，而拼版上只用 400px 宽。
     *
     * @return 解好的位图（已转正），失败返回 null
     */
    fun decode(bytes: ByteArray, maxPixel: Int): Bitmap? {
        if (bytes.isEmpty()) return null

        // 第一趟：只读尺寸，不解像素。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 第二趟：按 2 的幂次降采样。
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPixel)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return applyExifOrientation(bmp, bytes)
    }

    /**
     * 算出 `inSampleSize`。
     *
     * Android 只接受 2 的幂（1/2/4/8…），所以结果是「降到不超过上限」
     * 而不是精确等于 —— 之后的精确尺寸由 [coverResizeCrop] 处理。
     */
    fun sampleSizeFor(width: Int, height: Int, maxPixel: Int): Int {
        if (maxPixel <= 0) return 1
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge / 2 >= maxPixel) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * 按 EXIF 旋转载正位图。桌面端第一步就是 `ImageOps.exif_transpose`。
     *
     * 注意 `ExifInterface` **没有** `ByteArray` 构造器，只有
     * `File` / `FileDescriptor` / `InputStream` 三种。走 InputStream ——
     * 字节已经在内存里了，落盘再读一遍纯属浪费。
     */
    fun applyExifOrientation(bmp: Bitmap, bytes: ByteArray): Bitmap {
        val degrees = runCatching {
            val exif = ExifInterface(bytes.inputStream())
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)
            ImageGeometry.degreesForExifOrientation(orientation)
        }.getOrDefault(0)

        // FLIP_* 那几个值涉及镜像，胶片排版不关心（相机不会产出镜像 JPEG），
        // 所以 degreesForExifOrientation 只认三个 ROTATE 值。
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // MARK: - 反相

    /**
     * 负片模式。
     *
     * 桌面端 `ImageOps.invert` 只反转 RGB，对扫描件来说 alpha 通道无意义。
     * Android 上没有等价的单步 API，用 ColorMatrix 一次算完 ——
     * 比逐像素改快一个量级。
     */
    fun inverted(bmp: Bitmap): Bitmap {
        val m = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        val invert = android.graphics.ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        m.postConcat(invert)
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(
            bmp, 0f, 0f,
            android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(m) },
        )
        return out
    }

    /** 旋转 90 度。展开画布以保持内容直立。 */
    fun rotated(bmp: Bitmap, clockwise: Boolean): Bitmap {
        val m = Matrix().apply { postRotate(if (clockwise) 90f else -90f) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // MARK: - 裁切 + 缩放

    /**
     * 居中裁到 W/H = [ratio]，再缩到 [targetWidth]。
     *
     * 对应桌面端的「先裁后缩」。裁切原点向下取整，与两端一致
     * （Python 的 `int()`、Swift 的 `Int()`、Kotlin 的 `toInt()` 都向零截断）。
     */
    fun cropToRatio(bmp: Bitmap, ratio: Double, targetWidth: Int): Bitmap? {
        if (ratio <= 0.0 || targetWidth <= 0) return null
        val rect = ImageGeometry.centerCropToRatio(bmp.width, bmp.height, ratio)
            ?: return null
        val cropped = Bitmap.createBitmap(bmp, rect.x, rect.y, rect.width, rect.height)
        val outH = max(1, (targetWidth / ratio).toInt())
        return Bitmap.createScaledBitmap(cropped, targetWidth, outH, true)
    }

    /**
     * 桌面端 `image_pipeline.cover_resize_crop` —— 放置照片的那一步。
     *
     * 缩放到刚好盖住目标框，再居中裁掉溢出的部分。
     */
    fun coverResizeCrop(bmp: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (targetW <= 0 || targetH <= 0) return bmp
        val rect = ImageGeometry.coverCrop(bmp.width, bmp.height, targetW, targetH)
            ?: return bmp
        val src = Bitmap.createBitmap(bmp, rect.x, rect.y, rect.width, rect.height)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    // MARK: - 各格式入口

    /** 桌面端 `image_pipeline.process_135_image`。 */
    fun process135(
        bmp: Bitmap,
        thumbWidth: Int,
        mode: SheetConfig.ProcessingMode,
        forceLandscape: Boolean,
        subFormat: String,
    ): Bitmap {
        var img = if (mode == SheetConfig.ProcessingMode.NEGATIVE) inverted(bmp) else bmp

        val ratio = expected135Ratio(subFormat)
        img = maybeRotate135(img, ratio, forceLandscape)
        return cropToRatio(img, ratio, thumbWidth) ?: img
    }

    /**
     * 桌面端 `image_pipeline._maybe_rotate_135`。
     *
     * 转向由**子格式**决定，不是全局的「横构图」开关。
     */
    private fun maybeRotate135(bmp: Bitmap, expectedRatio: Double, forceLandscape: Boolean): Bitmap =
        applyRotation(
            bmp,
            ImageGeometry.rotation135(bmp.width, bmp.height, expectedRatio, forceLandscape),
        )

    /** 把转向判定喂给 [ImageGeometry] 的结论落到 Bitmap 上。 */
    private fun applyRotation(bmp: Bitmap, rotation: ImageGeometry.Rotation): Bitmap =
        when (rotation) {
            ImageGeometry.Rotation.NONE -> bmp
            ImageGeometry.Rotation.CW90 -> rotated(bmp, clockwise = true)
            ImageGeometry.Rotation.CCW90 -> rotated(bmp, clockwise = false)
        }

    /** 桌面端 `image_pipeline.process_120_image`。 */
    fun process120(
        bmp: Bitmap,
        subFormat: String,
        thumbWidth: Int,
        mode: SheetConfig.ProcessingMode,
        forceLandscape: Boolean,
    ): Bitmap {
        var img = if (mode == SheetConfig.ProcessingMode.NEGATIVE) inverted(bmp) else bmp

        val ratio = ratio120(subFormat)
        // 原实现里 120 的两个分支都调 `rotate(-90, expand=True)`，
        // 也就是顺时针 90 度。645 是竖拍格式，转向条件与其余子格式相反。
        img = applyRotation(
            img,
            ImageGeometry.rotation120(
                srcW = img.width,
                srcH = img.height,
                isPortraitFormat = isPortrait120Format(subFormat),
                forceLandscape = forceLandscape,
            ),
        )
        return cropToRatio(img, ratio, thumbWidth) ?: img
    }
}
