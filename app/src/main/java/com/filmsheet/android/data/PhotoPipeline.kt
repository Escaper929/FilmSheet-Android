package com.filmsheet.android.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.core.ImageGeometry
import com.filmsheet.android.render.ImageOps

/**
 * 照片流水线 — 把选中的 URI 变成可渲染的缩略图。
 *
 * ## 顺序不能换
 *
 * ```
 * 读字节 → 解码（带 EXIF 方向）→ 反相 → 按子格式转向 → 居中裁到画幅比
 * ```
 *
 * 三处容易出错：
 *
 * 1. **EXIF 必须在解码后立刻处理**。`inSampleSize` 不管方向，
 *    相机竖拍的 JPEG 会躺着进后续流程。
 * 2. **先裁后缩**。先缩会把本该裁掉的像素一起缩进来，高倍率下画质下降。
 * 3. **裁切用整数像素算完再喂给 `createBitmap`**。浮点直接传进去会被
 *    内部取整，边界上差 1 像素会抛异常。算术在 `ImageGeometry`，
 *    已逐像素测过。
 */
class PhotoPipeline(
    private val context: Context,
    private val config: SheetConfig,
) {

    /**
     * 解码上限。
     *
     * 缩略图宽最多 1200，源图 6000×4000 解成 Bitmap 要 96 MB，
     * 而拼版上只用得着 1200px。留一倍余量给裁切前的计算。
     */
    private val decodeMaxPixel: Int
        get() = maxOf(config.thumbWidth * 2, 2048)

    /**
     * 处理单张照片。
     *
     * @return 缩略图；读不到或解码失败返回 null（**不抛异常** ——
     *   一次选 36 张里坏掉一张，不该让整张拼版失败）
     */
    fun process(uri: Uri): ImageBitmap? {
        val bytes = readBytes(uri) ?: return null
        val decoded = ImageOps.decode(bytes, decodeMaxPixel) ?: return null

        val processed = when (config.filmFormat) {
            SheetConfig.FilmFormat.F135 -> ImageOps.process135(
                bmp = decoded,
                thumbWidth = config.thumbWidth,
                mode = config.processingMode,
                forceLandscape = config.forceLandscape,
                subFormat = config.subFormat,
            )
            SheetConfig.FilmFormat.F120 -> ImageOps.process120(
                bmp = decoded,
                subFormat = config.subFormat,
                thumbWidth = config.thumbWidth,
                mode = config.processingMode,
                forceLandscape = config.forceLandscape,
            )
        }
        return processed.asImageBitmap()
    }

    /** 顺序处理，失败的跳过。返回的顺序即用户选图的顺序。 */
    fun processAll(uris: List<Uri>): List<ImageBitmap> =
        uris.mapNotNull { process(it) }

    /**
     * 读 URI 的字节。
     *
     * 不用 `ContentResolver.openInputStream` 逐块拼 —— `readBytes()`
     * 一次到位。选的是系统照片分享过来的 URI，本身就在内存/缓存里。
     */
    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    /**
     * 包装图解码 —— 只需要尺寸参与布局，所以不必解全尺寸。
     *
     * 渲染时也只把它当图片贴过去（占 packSize 百分比的高度），
     * 超过 2048px 毫无意义。
     */
    fun decodePackImage(uri: Uri): ImageBitmap? {
        val bytes = readBytes(uri) ?: return null
        return ImageOps.decode(bytes, maxPixel = 2048)?.asImageBitmap()
    }

    companion object {

        /**
         * 查 URI 的文件名，用作「相机/胶卷」的默认值。
         *
         * 很多相机把文件名写成 `IMG_1234.jpg`，看不出机身型号；查不到就
         * 返回空串，让用户手填。
         */
        fun displayNameOf(resolver: ContentResolver, uri: Uri): String {
            return runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) cursor.getString(idx) else null
                        } else null
                    }
            }.getOrNull() ?: ""
        }

        /** 目标画幅比，供 UI 显示「这张片子的画幅是 3:2」。 */
        fun ratioOf(config: SheetConfig): Double = when (config.filmFormat) {
            SheetConfig.FilmFormat.F135 -> ImageGeometry.expected135Ratio(config.subFormat)
            SheetConfig.FilmFormat.F120 ->
                SheetConfig.filmFormatRatios[config.subFormat] ?: 1.0
        }
    }
}
