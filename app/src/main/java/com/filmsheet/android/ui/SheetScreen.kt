package com.filmsheet.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.core.BatchPlan
import com.filmsheet.android.core.ReprocessRule
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 主界面 — 结构对照 iOS 端 `ContentView`：来源区 / 预览区 / 操作栏。
 *
 * 保存与批量导出都收在这一层，因为只有这里持有**照片列表** ——
 * 编排类（[SheetController]）只负责怎么渲染一张。
 */
@Composable
fun SheetScreen(
    config: SheetConfig,
    onConfigChange: (SheetConfig) -> Unit,
    /** 把刚保存到相册的 Uri 交出去，通常用来弹分享。 */
    shareLatest: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { SheetController(context) }

    var images by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var sourceUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var showParams by remember { mutableStateOf(false) }
    var savedCount by remember { mutableStateOf(0) }

    /** 完整渲染并存进相册。 */
    fun saveOne(newConfig: SheetConfig, src: List<ImageBitmap>) {
        scope.launch {
            busy = true
            status = "渲染中…"
            val bitmap = controller.render(newConfig, src)
            val uri = if (bitmap != null) {
                controller.saveToGallery(bitmap, newConfig)
            } else null
            busy = false
            status = when {
                bitmap == null -> "渲染失败"
                uri == null -> "写入相册失败"
                else -> "已保存"
            }
            if (uri != null) {
                savedCount++
                shareLatest(uri)
            }
        }
    }

    /** 按 [com.filmsheet.android.core.BatchPlan] 导出全部。 */
    fun exportAll(newConfig: SheetConfig, src: List<ImageBitmap>) {
        val total = BatchPlan.outputCount(newConfig, src.size)
        if (total <= 0) {
            status = "没有可导出的内容"
            return
        }
        scope.launch {
            busy = true
            progress = 0f
            val result = controller.exportBatch(newConfig, src) { done, count ->
                progress = done.toFloat() / count.coerceAtLeast(1)
                status = "导出 $done/$count"
            }
            busy = false
            progress = 0f
            savedCount += result.saved.size
            status = if (result.failures.isEmpty()) {
                "已导出 ${result.saved.size} 张"
            } else {
                // 部分失败要说清楚，否则用户不知道少了什么
                "已导出 ${result.saved.size}/${total} 张，${result.failures.size} 张失败"
            }
            result.saved.firstOrNull()?.let { shareLatest(it) }
        }
    }

    // 防抖任务：拖滑块时每秒几十次变更，每次都渲染会把界面卡死。
    // 新一次变更会取消上一次未完成的渲染 —— 这与 iOS 端
    // `SheetViewModel.refreshPreview` 的 previewTask?.cancel() 同构。
    var previewJob by remember { mutableStateOf<Job?>(null) }

    fun schedulePreview(newConfig: SheetConfig, src: List<ImageBitmap>) {
        if (src.isEmpty()) {
            preview = null
            return
        }
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(SheetController.PREVIEW_DEBOUNCE_MS)
            preview = controller.renderPreview(newConfig, src)
        }
    }

    /**
     * 应用配置变更。
     *
     * ## 为什么不能一律只重排预览
     *
     * `images` 里存的是**已经按配置处理过的缩略图**。而画幅、负片、
     * 缩略图宽度这几个参数是在**预处理阶段**生效的（裁切 / 反相 / 缩放
     * 都发生在解码之后），布局阶段改不了。
     *
     * 所以这三者一变，必须重新走一遍 PhotoPipeline，否则预览会拿旧画幅的
     * 缩略图按新画幅排 —— 看着「排版变了」，其实照片本身还是错的。
     */
    fun applyConfig(newConfig: SheetConfig) {
        onConfigChange(newConfig)
        if (sourceUris.isEmpty()) return

        if (!ReprocessRule.needsReprocess(config, newConfig)) {
            schedulePreview(newConfig, images)
            return
        }
        scope.launch {
            busy = true
            status = "重新处理照片…"
            progress = 0f
            val reprocessed = controller.loadPhotos(newConfig, sourceUris) { done, total ->
                progress = done.toFloat() / total
            }
            images = reprocessed
            busy = false
            status = "已重新处理 ${reprocessed.size} 张"
            schedulePreview(newConfig, reprocessed)
        }
    }

    /**
     * 系统 PhotoPicker。
     *
     * API 33+ 是系统 Photo Picker，**不需要任何权限**；低版本由 androidx 的
     * ActivityResultContracts 回退到 `ACTION_OPEN_DOCUMENT`。所以整份
     * manifest 里一条存储权限都没声明。
     */
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_SELECTION),
    ) { picked ->
        if (picked.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = "正在读取 ${picked.size} 张照片…"
            progress = 0f
            // 配置变了要重新处理：画幅比、反相、转向都取决于它
            val loaded = controller.loadPhotos(config, picked) { done, total ->
                progress = done.toFloat() / total
            }
            images = loaded
            sourceUris = picked
            busy = false
            status = if (loaded.size == picked.size) {
                "已载入 ${loaded.size} 张"
            } else {
                // 有照片读不出来 —— 说清楚比静默丢弃好
                "已载入 ${loaded.size}/${picked.size} 张（部分无法读取）"
            }
            schedulePreview(config, loaded)
        }
    }

    val pickPack = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                status = "正在读取包装图…"
                val pack = controller.loadPackImage(config, uri)
                onConfigChange(config.copy(packImageUri = uri.toString()))
                busy = false
                status = if (pack != null) "包装图已设置" else "包装图读取失败"
                if (pack != null) schedulePreview(config.copy(packImageUri = uri.toString()), images)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 来源区 ────────────────────────────────────────────────
        Column(modifier = Modifier.padding(16.dp)) {
            Button(
                onClick = {
                    pickPhotos.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("选择照片", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (images.isEmpty()) "未选择照片" else "已选 ${images.size} 张",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (images.isNotEmpty()) {
                    TextButton(onClick = {
                        images = emptyList()
                        sourceUris = emptyList()
                        preview = null
                        status = ""
                    }) { Text("清空") }
                }
            }
        }

        // ── 预览区 ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF171717)),
            contentAlignment = Alignment.Center,
        ) {
            val shot = preview
            when {
                busy -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFFFF8C3C))
                    Spacer(Modifier.height(12.dp))
                    Text(status, color = Color.White.copy(alpha = 0.7f))
                }

                shot != null -> Image(
                    // shot 已经是 Compose 的 ImageBitmap（渲染器直接产出它），
                    // 不需要也不能再调 asImageBitmap() —— 那个扩展是给
                    // android.graphics.Bitmap 用的
                    bitmap = shot,
                    contentDescription = "排版预览",
                    // 不做插值：预览被缩得很小，插值反而糊掉齿孔这类细节，
                    // 看不出真实排版的样子
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                )

                else -> Text(
                    "选择照片后这里显示排版预览",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }

        // ── 操作栏 ────────────────────────────────────────────────
        Column(modifier = Modifier.padding(16.dp)) {
            if (busy && progress in 0.001f..0.999f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            // 只放「改完必须重排」的高频参数。缩略图宽度、间距、输出质量
            // 这些进参数面板，否则主屏会被滑块塞满。
            QuickParams(
                config = config,
                onChange = ::applyConfig,
                enabled = !busy,
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        pickPack.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !busy,
                ) { Text("包装图") }

                OutlinedButton(onClick = { showParams = true }) { Text("参数") }

                OutlinedButton(
                    onClick = { schedulePreview(config, images) },
                    enabled = !busy && images.isNotEmpty(),
                ) { Text("重排") }

                Button(
                    // 预览是 80px 的小图，保存必须**重新完整渲染** ——
                    // 直接存预览等于给用户一张 80px 的废图
                    onClick = { saveOne(config, images) },
                    enabled = !busy && images.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }

            // 批量导出只在真的会出多张时才出现 —— 单张模式下「导出」与
            // 「保存」是同一件事，摆两个按钮只会让人犹豫。
            val batchTotal = BatchPlan.outputCount(config, images.size)
            if (batchTotal > 1 && images.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { exportAll(config, images) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("批量导出 $batchTotal 张（两种风格 / 单张展开）")
                }
            }

            if (status.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            if (savedCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "本次已保存 $savedCount 张",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showParams) {
        ParameterSheet(
            config = config,
            onConfigChange = ::applyConfig,
            onDismiss = { showParams = false },
            onReset = {
                onConfigChange(SheetConfig())
                schedulePreview(SheetConfig(), images)
                showParams = false
            },
        )
    }
}

/**
 * 主屏上的高频参数。
 *
 * 只放「改完必须重排」的：列数、片基、画幅、风格。其余（缩略图宽度、
 * 间距、输出质量）以后放参数面板。
 */
@Composable
private fun QuickParams(
    config: SheetConfig,
    onChange: (SheetConfig) -> Unit,
    enabled: Boolean,
) {
    val switcher = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(switcher),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChoiceRow("片基", SheetConfig.FilmFormat.entries.map { it.rawValue },
                config.filmFormat.rawValue, enabled) { picked ->
                val format = SheetConfig.FilmFormat.fromRaw(picked)
                // 换片基必须换子格式：135 的子格式对 120 全是非法值
                onChange(config.copy(filmFormat = format, subFormat = ""))
            }

            ChoiceRow("画幅", config.validSubFormats(), config.subFormat, enabled) {
                onChange(config.copy(subFormat = it))
            }

            ChoiceRow("列数", listOf("1", "2", "3", "4", "5", "6", "8", "10"),
                config.columns.toString(), enabled) {
                onChange(config.copy(columns = it.toInt()))
            }

            ChoiceRow("风格", SheetConfig.RenderStyle.entries.map { it.displayName },
                config.renderStyle.displayName, enabled) { picked ->
                onChange(
                    config.copy(
                        renderStyle = SheetConfig.RenderStyle.entries
                            .first { it.displayName == picked },
                    ),
                )
            }
        }
    }
}

/** 一行标签 + 若干选项按钮。 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(40.dp))
        for (option in options) {
            if (option == selected) {
                Button(
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                ) { Text(option, style = MaterialTheme.typography.labelMedium) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                ) { Text(option, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

/**
 * 与 iOS 端 `maxSelectionCount: 200` 对齐。
 *
 * 200 不是随便定的：行计划曾因互递归需要约 8.6×10^10 次调用才能排完一整卷
 * （见 README「行计划为什么必须是一次性前缀和」）。修成前缀和之后 200 张
 * 只要几毫秒，但上限与 iOS 端一致，方便两端比对同一批照片的结果。
 */
private const val MAX_SELECTION = 200
