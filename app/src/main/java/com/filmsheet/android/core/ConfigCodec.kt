package com.filmsheet.android.core

import com.filmsheet.android.config.SheetConfig

/**
 * 配置编解码 — 移植自 iOS 端 `SheetConfig.stored` / `restore(from:)`。
 *
 * ## 两条设计规则
 *
 * **1. 逐字段容错。** 任一字段缺失或类型不对，就用默认值补上，而不是整份
 * 丢弃。偏好文件可能被别的版本写过、可能被手工改过、可能因为升级而字段
 * 改名 —— 整份丢弃的话，用户点开 App 发现自己的参数全没了。
 *
 * **2. 包装图不持久化。** 它是张多 MB 的图（配置里只存 URI），
 * 写进偏好文件会把它撑大。而它本来就是**每次会话重新挑**的东西，
 * 用户不会指望重启后还在。
 *
 * 这里只做纯逻辑的字符串 ↔ 配置转换，不碰任何存储 API ——
 * 所以能被纯 JVM 单测覆盖，存到哪由 [com.filmsheet.android.data.ConfigStore] 决定。
 */
object ConfigCodec {

    // MARK: - 编码

    /** 编码成扁平字符串键值对。 */
    fun encode(config: SheetConfig): Map<String, String> = buildMap {
        put("thumbWidth", config.thumbWidth.toString())
        put("spacing", config.spacing.toString())
        put("columns", config.columns.toString())
        put("forceLandscape", config.forceLandscape.toString())
        put("edgeText", config.edgeText)
        put("outputFormat", config.outputFormat.rawValue)
        put("quality", config.quality.toString())
        put("filmFormat", config.filmFormat.rawValue)
        put("subFormat", config.subFormat)
        put("infoLang", config.infoLang.rawValue)
        put("packPosition", config.packPosition.rawValue)
        put("packBorderStroke", config.packBorderStroke.toString())
        // 渲染风格存的是显示名（"接触印相"）而不是内部标识 —— 那是对用户
        // 友好的形式，也与 iOS 端 `displayName` 的取用方式一致
        put("renderStyle", config.renderStyle.displayName)
        put("packSize", config.packSize.toString())
        put("processingMode", config.processingMode.rawValue)
        put("perfMode", config.perfMode.rawValue)
        put("signature", config.signature)
        put("batchExportEnabled", config.batchExportEnabled.toString())
        put("singlePhotoMode", config.singlePhotoMode.toString())
        put("singlePhotoShowExtra", config.singlePhotoShowExtra.toString())
        // 包装图的 URI 也不存，同规则 2
        for ((key, value) in config.info) {
            put("info.${key.name}", value)
        }
    }

    // MARK: - 解码

    /** 从键值对恢复。`map` 为空或全无效时返回默认值。 */
    fun decode(map: Map<String, String>): SheetConfig {
        if (map.isEmpty()) return SheetConfig()
        val d = SheetConfig()

        return d.copy(
            thumbWidth = map.int("thumbWidth", d.thumbWidth),
            spacing = map.int("spacing", d.spacing),
            columns = map.int("columns", d.columns),
            forceLandscape = map.bool("forceLandscape", d.forceLandscape),
            edgeText = map["edgeText"] ?: d.edgeText,
            quality = map.int("quality", d.quality),
            outputFormat = map.enum("outputFormat", d.outputFormat) { raw ->
                SheetConfig.OutputFormat.fromRaw(raw)
            },
            filmFormat = map.enum("filmFormat", d.filmFormat) { raw ->
                SheetConfig.FilmFormat.fromRaw(raw)
            },
            // 空子格式交给 sanitized() 依据片基回填
            subFormat = map["subFormat"]?.takeIf { it.isNotEmpty() } ?: d.subFormat,
            infoLang = map.enum("infoLang", d.infoLang) { raw ->
                SheetConfig.InfoLang.fromRaw(raw)
            },
            packPosition = map.enum("packPosition", d.packPosition) { raw ->
                SheetConfig.PackPosition.fromRaw(raw)
            },
            packBorderStroke = map.bool("packBorderStroke", d.packBorderStroke),
            renderStyle = map.enum("renderStyle", d.renderStyle) { raw ->
                SheetConfig.RenderStyle.entries.firstOrNull { it.displayName == raw }
                    ?: d.renderStyle
            },
            packSize = map.int("packSize", d.packSize),
            processingMode = map.enum("processingMode", d.processingMode) { raw ->
                SheetConfig.ProcessingMode.fromRaw(raw)
            },
            perfMode = map.enum("perfMode", d.perfMode) { raw ->
                PerfMode.fromRaw(raw)
            },
            signature = map["signature"] ?: d.signature,
            batchExportEnabled = map.bool("batchExportEnabled", d.batchExportEnabled),
            singlePhotoMode = map.bool("singlePhotoMode", d.singlePhotoMode),
            singlePhotoShowExtra = map.bool("singlePhotoShowExtra", d.singlePhotoShowExtra),
            info = map.infoFields(),
        )
    }

    // MARK: - 单个字段的容错读取

    private fun Map<String, String>.int(key: String, fallback: Int): Int =
        this[key]?.toIntOrNull() ?: fallback

    /**
     * 布尔值容错。
     *
     * 只接受 `true` / `false`。这样手工编辑偏好文件写进去的 `1`、`yes`、
     * 乱码都会落回默认值，而不是被 Kotlin 的 `toBoolean()` 当成 true ——
     * 那会把「强制横构图」之类的开关莫名打开。
     */
    private fun Map<String, String>.bool(key: String, fallback: Boolean): Boolean =
        when (this[key]) {
            "true" -> true
            "false" -> false
            else -> fallback
        }

    /** 枚举容错：无法识别时保留 [fallback]，而不是整份配置作废。 */
    private inline fun <T> Map<String, String>.enum(
        key: String,
        fallback: T,
        parse: (String) -> T,
    ): T {
        val raw = this[key] ?: return fallback
        return parse(raw)
    }

    /**
     * 恢复信息字段。
     *
     * 未知键直接忽略 —— 可能是旧版本留下的，也可能是别的功能占用了同样的键。
     * 保留它没有意义，`SheetConfig.InfoKey.entries` 才是权威的字段集合。
     */
    private fun Map<String, String>.infoFields(): Map<SheetConfig.InfoKey, String> {
        val out = mutableMapOf<SheetConfig.InfoKey, String>()
        for (key in SheetConfig.InfoKey.entries) {
            this["info.${key.name}"]?.let { out[key] = it }
        }
        return out
    }
}
