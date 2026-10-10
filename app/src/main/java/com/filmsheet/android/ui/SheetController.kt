package com.filmsheet.android.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.data.PhotoPipeline
import com.filmsheet.android.render.Renderer120
import com.filmsheet.android.render.Renderer135
import com.filmsheet.android.render.SheetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 界面状态机 — 承载选图、渲染、保存的编排。
 *
 * 没有用 ViewModel：这个 App 只有单屏，状态都由界面自己持有即可。
 * 若后续要加参数面板跨屏共享状态，再换成 `ViewModel` 也只需挪这一层。
 *
 * **所有耗时操作都在 [Dispatchers.Default] 或 IO 上跑**，绝不能碰主线程 ——
 * 渲染一张 1200px 宽的拼版是几秒级操作，放主线程会掉帧甚至 ANR。
 */
class SheetController(private val context: Context) {

    /**
     * 渲染一张拼版。
     *
     * 与 iOS 端 `SheetRenderer.isPreview` 一样，预览与导出走**同一条**
     * 渲染路径，只有图片列表不同 —— 分开写两套必然出现
     * 「预览好看、导出不一样」。
     */
    suspend fun render(config: SheetConfig, images: List<ImageBitmap>): ImageBitmap? =
        withContext(Dispatchers.Default) {
            if (images.isEmpty()) return@withContext null
            val renderer = rendererFor(config, images)
            renderer.render()
        }

    /** 处理选中的 URI 列表。 */
    suspend fun loadPhotos(
        config: SheetConfig,
        uris: List<Uri>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<ImageBitmap> = withContext(Dispatchers.Default) {
        val pipeline = PhotoPipeline(context, config)
        val out = ArrayList<ImageBitmap>(uris.size)
        uris.forEachIndexed { i, uri ->
            onProgress(i + 1, uris.size)
            pipeline.process(uri)?.let { out += it }
        }
        out
    }

    /** 处理包装图。 */
    suspend fun loadPackImage(config: SheetConfig, uri: Uri): ImageBitmap? =
        withContext(Dispatchers.Default) {
            PhotoPipeline(context, config).decodePackImage(uri)
        }

    private fun rendererFor(
        config: SheetConfig,
        images: List<ImageBitmap>,
        pack: ImageBitmap? = null,
    ): SheetRenderer = when (config.filmFormat) {
        SheetConfig.FilmFormat.F135 -> Renderer135(config, images, pack)
        SheetConfig.FilmFormat.F120 -> Renderer120(config, images, pack)
    }

    // MARK: - 编码

    /**
     * 按配置编码成 JPEG / PNG。
     *
     * PNG 在 `Bitmap.CompressFormat.PNG` 下忽略 quality 参数（这是 Android
     * 的既定行为），所以只在 JPG 路径上传 quality。
     */
    suspend fun encode(
        bitmap: ImageBitmap,
        config: SheetConfig,
    ): ByteArray = withContext(Dispatchers.Default) {
        val bmp = bitmap.asAndroidBitmap()
        val format = when (config.outputFormat) {
            SheetConfig.OutputFormat.PNG -> Bitmap.CompressFormat.PNG
            SheetConfig.OutputFormat.JPG -> Bitmap.CompressFormat.JPEG
        }
        ByteArrayOutputStream().use { out ->
            bmp.compress(format, config.quality, out)
            out.toByteArray()
        }
    }

    /** 写 PNG/JPEG 到相册。返回插入后的 Uri 或 null。 */
    suspend fun saveToGallery(bitmap: ImageBitmap, config: SheetConfig): Uri? =
        withContext(Dispatchers.IO) {
            val bytes = encode(bitmap, config)
            val resolver = context.contentResolver
            val ext = if (config.outputFormat == SheetConfig.OutputFormat.PNG) "png" else "jpg"
            val mime = "image/$ext"
            val name = "FilmSheet-${System.currentTimeMillis()}.$ext"

            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                // 不写 RELATIVE_PATH 的话图会落在相册根目录，容易被系统清理
                put(
                    android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/FilmSheet",
                )
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
            ) ?: return@withContext null

            runCatching {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
            }.onFailure {
                resolver.delete(uri, null, null)
                return@withContext null
            }

            // IS_PENDING 必须在写完后清掉，否则相册里看不到
            resolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                },
                null, null,
            )
            uri
        }

    /** 读 URI 的显示名，用于填「相机」字段。 */
    fun displayNameOf(uri: Uri): String =
        PhotoPipeline.displayNameOf(context.contentResolver as ContentResolver, uri)
}
