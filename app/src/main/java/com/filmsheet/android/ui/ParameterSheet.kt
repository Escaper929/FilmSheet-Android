package com.filmsheet.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.core.PerfMode
import kotlin.math.roundToInt

/**
 * 参数面板 — 字段与默认值对齐 iOS 端 `ParameterSheet.swift`
 * （桌面端 `processor/config_schema.py` 的 `FIELD_DEFS`）。
 *
 * 两端的字段集合必须一致：少一个字段，就意味着用户在 Android 上够不到
 * iOS 上能调的选项，出片就会不一样。
 *
 * 改任何参数都会重排预览 —— 面板是控制面，后面的预览是反馈。
 */
@Composable
fun ParameterSheet(
    config: SheetConfig,
    onConfigChange: (SheetConfig) -> Unit,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // 面板内容很长，用几乎全屏的对话框，否则滑不动
        modifier = Modifier.fillMaxWidth(0.95f),
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("恢复默认") }
        },
        title = { Text("参数") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FormatSection(config, onConfigChange)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LayoutSection(config, onConfigChange)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ImageSection(config, onConfigChange)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                PackSection(config, onConfigChange)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                InfoSection(config, onConfigChange)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutputSection(config, onConfigChange)

                Spacer(Modifier.height(8.dp))
                Text(
                    "预览用 ${SheetController.PREVIEW_THUMB_WIDTH}px 缩略图快速出图；" +
                        "保存时才按设定宽度完整渲染。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

// MARK: - 画幅

@Composable
private fun FormatSection(config: SheetConfig, onChange: (SheetConfig) -> Unit) {
    SectionTitle("画幅")

    ChipRow(
        label = "胶卷类型",
        options = SheetConfig.FilmFormat.entries.map { it.rawValue },
        selected = config.filmFormat.rawValue,
    ) { picked ->
        val format = SheetConfig.FilmFormat.fromRaw(picked)
        // 换片基必须换子格式：135 的子格式对 120 全是非法值
        onChange(config.copy(filmFormat = format, subFormat = ""))
    }

    ChipRow(
        label = "子画幅",
        options = config.validSubFormats(),
        selected = config.subFormat,
    ) { onChange(config.copy(subFormat = it)) }

    ChipRow(
        label = "齿孔",
        options = listOf(
            PerfMode.AUTO.rawValue,
            PerfMode.KS.rawValue,
            PerfMode.BH.rawValue,
        ),
        selected = config.perfMode.rawValue,
    ) { onChange(config.copy(perfMode = PerfMode.fromRaw(it))) }
}

// MARK: - 排版

@Composable
private fun LayoutSection(config: SheetConfig, onChange: (SheetConfig) -> Unit) {
    SectionTitle("排版")

    SliderRow(
        label = "缩略图宽度",
        value = config.thumbWidth,
        range = SheetConfig.THUMB_WIDTH_MIN..SheetConfig.THUMB_WIDTH_MAX,
        step = 20,
        display = config.thumbWidth.toString(),
    ) { onChange(config.copy(thumbWidth = it)) }

    SliderRow(
        label = "每行张数",
        value = config.columns,
        range = 1..12,
        step = 1,
        display = config.columns.toString(),
    ) { onChange(config.copy(columns = it)) }

    SliderRow(
        label = "间距",
        value = config.spacing,
        range = 0..80,
        step = 1,
        display = config.spacing.toString(),
    ) { onChange(config.copy(spacing = it)) }

    SwitchRow("强制横向构图", config.forceLandscape) {
        onChange(config.copy(forceLandscape = it))
    }
}

// MARK: - 图像

@Composable
private fun ImageSection(config: SheetConfig, onChange: (SheetConfig) -> Unit) {
    SectionTitle("图像")

    ChipRow(
        label = "处理",
        options = listOf(
            SheetConfig.ProcessingMode.POSITIVE.rawValue,
            SheetConfig.ProcessingMode.NEGATIVE.rawValue,
        ),
        selected = config.processingMode.rawValue,
    ) { onChange(config.copy(processingMode = SheetConfig.ProcessingMode.fromRaw(it))) }

    ChipRow(
        label = "风格",
        options = SheetConfig.RenderStyle.entries.map { it.displayName },
        selected = config.renderStyle.displayName,
    ) { picked ->
        onChange(
            config.copy(
                renderStyle = SheetConfig.RenderStyle.entries
                    .first { it.displayName == picked },
            ),
        )
    }

    SwitchRow("单张模式（每张单独一条片）", config.singlePhotoMode) {
        onChange(config.copy(singlePhotoMode = it))
    }
    if (config.singlePhotoMode) {
        // 层级依赖：只有单张模式打开时，「显示信息块」才有意义
        SwitchRow("　单张模式显示信息块", config.singlePhotoShowExtra) {
            onChange(config.copy(singlePhotoShowExtra = it))
        }
    }

    SwitchRow("批量导出两种风格", config.batchExportEnabled) {
        onChange(config.copy(batchExportEnabled = it))
    }
}

// MARK: - 包装图

@Composable
private fun PackSection(config: SheetConfig, onChange: (SheetConfig) -> Unit) {
    SectionTitle("包装图")

    ChipRow(
        label = "位置",
        options = listOf("left", "right"),
        selected = config.packPosition.rawValue,
    ) { onChange(config.copy(packPosition = SheetConfig.PackPosition.fromRaw(it))) }

    SliderRow(
        label = "大小",
        value = config.packSize,
        range = 10..100,
        step = 5,
        display = "${config.packSize}%",
    ) { onChange(config.copy(packSize = it)) }

    SwitchRow("描边", config.packBorderStroke) {
        onChange(config.copy(packBorderStroke = it))
    }

    if (config.packImageUri != null) {
        TextButton(onClick = { onChange(config.copy(packImageUri = null)) }) {
            Text("移除包装图", color = MaterialTheme.colorScheme.error)
        }
    }
}

// MARK: - 信息标注

@Composable
private fun InfoSection(config: SheetConfig, onChange: (SheetConfig) -> Unit) {
    SectionTitle("信息标注")

    ChipRow(
        label = "语言",
        options = listOf(
            SheetConfig.InfoLang.ZH.rawValue,
            SheetConfig.InfoLang.EN.rawValue,
        ),
        selected = config.infoLang.rawValue,
    ) { onChange(config.copy(infoLang = SheetConfig.InfoLang.fromRaw(it))) }

    // 字段顺序按 InfoLayout 的三行三列（SPEC §5.4），**不是**枚举声明顺序。
    // 面板里从上到下的顺序必须跟成片上的视觉顺序一致 —— 用户填「卷号」
    // 时得在「相机」上面。现在两者恰好相同，但那是巧合，改枚举就会漂。
    for (key in SheetConfig.InfoLayout.keysInLayoutOrder()) {
        OutlinedTextField(
            value = config.value(key),
            onValueChange = { onChange(config.withValue(key, it)) },
            label = { Text(key.label(config.infoLang)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = config.edgeText,
        onValueChange = { onChange(config.copy(edgeText = it)) },
        label = { Text("自定义片边文字") },
        // 非空时完全跳过品牌映射（SPEC §7.3），得说清楚
        supportingText = { Text("非空时会跳过品牌与型号的自动映射") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

// MARK: - 输出

@Composable
private fun OutputSection(config: SheetConfig, onChange: (SheetConfig) -> Unit) {
    SectionTitle("输出")

    ChipRow(
        label = "格式",
        options = SheetConfig.OutputFormat.entries.map { it.rawValue },
        selected = config.outputFormat.rawValue,
    ) { onChange(config.copy(outputFormat = SheetConfig.OutputFormat.fromRaw(it))) }

    // PNG 在 Android 上忽略 quality 参数，只在 JPG 时显示这一项
    if (config.outputFormat == SheetConfig.OutputFormat.JPG) {
        SliderRow(
            label = "质量",
            value = config.quality,
            range = 1..100,
            step = 1,
            display = config.quality.toString(),
        ) { onChange(config.copy(quality = it)) }
    }

    OutlinedTextField(
        value = config.signature,
        onValueChange = { onChange(config.copy(signature = it)) },
        label = { Text("签名水印") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

// MARK: - 零件

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun ChipRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(84.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (option in options) {
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    display: String,
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                display,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.End,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            // 步长要显式给，否则滑块是连续的，取整后会出现跳变
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
