package com.filmsheet.android.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.core.BatchPlan
import com.filmsheet.android.core.OutputNaming
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
     * 渲染一张拼版 —— **按设定宽度完整渲染**，用于导出。
     *
     * 预览走 [renderPreview]，那条路才是快的。两者共用同一套渲染器，
     * 只有配置里的 `thumbWidth` 不同 —— 分开写两套必然出现
     * 「预览好看、导出不一样」。
     */
    suspend fun render(config: SheetConfig, images: List<ImageBitmap>): ImageBitmap? =
        withContext(Dispatchers.Default) {
            if (images.isEmpty()) return@withContext null
            rendererFor(config, images).render()
        }

    /**
     * 渲染预览 — `thumbWidth` 压到 [PREVIEW_THUMB_WIDTH]。
     *
     * ## 为什么预览不能按设定宽度渲染
     *
     * `thumb_width = 1200` 时画布约 8000px 宽、每帧还要做一次位图缩放。
     * 拖动列数滑块时每秒触发几十次变更，每次都完整渲染会把界面卡死。
     *
     * 80px 沿用桌面端预览的取值：小到能塞进一帧的预算，又大到能看清
     * 排版与色调 —— 而这正是预览要反馈的东西。
     *
     * 齿孔这类细节在 80px 下看不清，但预览本来就不该用来判断齿孔，
     * 导出结果才是准的。
     */
    suspend fun renderPreview(
        config: SheetConfig,
        images: List<ImageBitmap>,
    ): ImageBitmap? = render(config.copy(thumbWidth = PREVIEW_THUMB_WIDTH), images)

    /**
     * 防抖时长（毫秒）。
     *
     * 250ms 与 iOS 端 `refreshPreview` 一致：短到感觉不到延迟，
     * 长到能把拖滑块时的连续变更合并成一次渲染。
     */
    companion object {
        const val PREVIEW_THUMB_WIDTH = 80
        const val PREVIEW_DEBOUNCE_MS = 250L
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

    /**
     * 写 PNG/JPEG 到相册。返回插入后的 Uri 或 null。
     *
     * 文件名走 [OutputNaming] 从信息字段派生，而不是时间戳 ——
     * 「Nikon F3_Portra 400」比「FilmSheet-1762...」在相册里好用得多。
     */
    suspend fun saveToGallery(
        bitmap: ImageBitmap,
        config: SheetConfig,
        filename: String = OutputNaming.generate(null, config.info, config.outputFormat),
    ): Uri? = withContext(Dispatchers.IO) {
        val bytes = encode(bitmap, config)
        val resolver = context.contentResolver
        val ext = OutputNaming.extFor(config.outputFormat)
        val mime = "image/$ext"

        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
            // 不写 RELATIVE_PATH 的话图会落在相册根目录，容易被系统清理
            put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/FilmSheet",
            )
            // IS_PENDING 让相册在写完前先藏起这张图 —— 否则用户会看到
            // 一张半张的图
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ) ?: return@withContext null

        runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
        }.onFailure {
            // 写失败必须删掉占位，否则相册里留一张 0 字节的坏图
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

    // MARK: - 批量导出

    /** 批量导出的结果汇总。 */
    data class BatchResult(
        val saved: List<Uri> = emptyList(),
        val failures: List<String> = emptyList(),
    ) {
        val allSucceeded: Boolean get() = failures.isEmpty()
    }

    /**
     * 按 [BatchPlan] 逐张渲染并保存。
     *
     * ## 为什么单张失败不中断整批
     *
     * 200 张照片里坏掉一张（文件被删、格式不支持），整批就白等了 ——
     * 前面的渲染都作废。所以失败只记下来，最后一并报。
     *
     * @param onProgress 已完成数 / 总数
     */
    suspend fun exportBatch(
        config: SheetConfig,
        images: List<ImageBitmap>,
        explicitName: String? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): BatchResult = withContext(Dispatchers.IO) {
        val plan = BatchPlan.plan(config, images.size, explicitName)
        if (plan.isEmpty()) return@withContext BatchResult()

        val saved = mutableListOf<Uri>()
        val failures = mutableListOf<String>()

        for ((i, item) in plan.withIndex()) {
            onProgress(i + 1, plan.size)

            // 单张模式只喂一张照片；整张导出喂全部
            val subset = if (item.singleIndex != null) {
                listOf(images[BatchPlan.photoIndexFor(images.size, item.singleIndex)])
            } else {
                images
            }

            val styleConfig = config.copy(
                renderStyle = item.style,
                singlePhotoMode = config.singlePhotoMode && item.singleIndex != null,
            )

            val bitmap = runCatching { rendererFor(styleConfig, subset).render() }
                .getOrNull()
            if (bitmap == null) {
                failures += "${item.filename}：渲染失败"
                continue
            }

            val uri = runCatching { saveToGallery(bitmap, config, item.filename) }
                .getOrNull()
            if (uri == null) {
                failures += "${item.filename}：写入相册失败"
            } else {
                saved += uri
            }
        }

        onProgress(plan.size, plan.size)
        BatchResult(saved, failures)
    }

    /** 读 URI 的显示名，用于填「相机」字段。 */
    fun displayNameOf(uri: Uri): String =
        PhotoPipeline.displayNameOf(context.contentResolver as ContentResolver, uri)
}
