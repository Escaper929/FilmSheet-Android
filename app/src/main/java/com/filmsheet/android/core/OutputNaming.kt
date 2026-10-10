package com.filmsheet.android.core

import com.filmsheet.android.config.SheetConfig

/**
 * 输出命名与批量策略 — 移植自 iOS 端 `FilmSheetPipeline` 的 naming 段
 * （桌面端 `processor/filename_utils.py`）。
 *
 * 纯逻辑，不碰图形 API，所以能被纯 JVM 单测逐条验证。
 */
object OutputNaming {

    /**
     * 用户没给文件名时的回退名。
     *
     * 这些名字必须列进 [PLACEHOLDER_NAMES] —— 系统「另存为」对话框默认会
     * 填上当前文件名，若它恰好等于回退名，就无法区分「用户没改」与
     * 「用户就想要这个名字」，会白白丢掉从信息字段派生的好名字。
     */
    val PLACEHOLDER_NAMES = listOf(
        "filmsheet_output.jpg",
        "filmsheet_output.png",
        "filmsheet_output.jpeg",
    )

    /** 文件名里的非法字符。Android 与桌面端各自的限制略有出入，取并集。 */
    private const val ILLEGAL = "\\/:*?\"<>|"

    /**
     * 生成输出文件名。
     *
     * 优先用信息字段（卷号_相机_胶卷_拍摄日期）拼一个可辨认的名字，
     * 全空才回退到 `filmsheet_output`。
     *
     * @param explicit 用户指定的文件名；等于占位名时视作未指定
     * @param info 信息字段
     * @param fallbackExt 输出格式决定扩展名
     * @return 不含路径的文件名
     */
    fun generate(
        explicit: String?,
        info: Map<SheetConfig.InfoKey, String>,
        fallbackExt: SheetConfig.OutputFormat,
    ): String {
        val ext = extFor(fallbackExt)

        val custom = explicit?.trim()
        if (!custom.isNullOrEmpty() && custom !in PLACEHOLDER_NAMES) return custom

        val parts = listOf(
            SheetConfig.InfoKey.ROLL,
            SheetConfig.InfoKey.CAMERA,
            SheetConfig.InfoKey.FILM,
            SheetConfig.InfoKey.SHOOT_DATE,
        ).mapNotNull { key -> info[key]?.trim() }
            .filter { it.isNotEmpty() }

        if (parts.isEmpty()) return "filmsheet_output.$ext"

        val name = parts.joinToString("_").replaceIllegal()
        return if (name.isEmpty()) "filmsheet_output.$ext" else "$name.$ext"
    }

    private fun String.replaceIllegal(): String =
        map { if (it in ILLEGAL) '_' else it }.joinToString("")

    fun extFor(format: SheetConfig.OutputFormat): String =
        if (format == SheetConfig.OutputFormat.PNG) "png" else "jpg"

    /**
     * 批量导出时的风格后缀。
     *
     * 只有批量才加后缀 —— 单张导出用不着区分，加了反而让文件名变啰嗦。
     */
    fun styleSuffix(style: SheetConfig.RenderStyle, batch: Boolean): String {
        if (!batch) return ""
        return when (style) {
            SheetConfig.RenderStyle.LIGHTBOX -> "_light"
            SheetConfig.RenderStyle.CONTACT_SHEET -> "_contact"
        }
    }

    /**
     * 拼出最终文件名。
     *
     * @param index 单张模式下的序号；批量展开单张时给出，整张导出时为 null
     */
    fun outputName(
        base: String,
        style: SheetConfig.RenderStyle,
        batch: Boolean,
        index: Int?,
        format: SheetConfig.OutputFormat,
    ): String {
        val ext = extFor(format)
        val suffix = styleSuffix(style, batch)
        return if (index != null) "${base}${suffix}_single_$index.$ext"
        else if (suffix.isEmpty()) "$base.$ext" else "$base$suffix.$ext"
    }
}

/**
 * 批量导出策略 — 「导出哪几张、什么风格」。
 *
 * ## 批量是什么
 *
 * 桌面端 `batch_export` 的意思是：**同一次导出出两套**，一套灯板正片、
 * 一套接触印相（黑底）。用户对比着看，决定选哪张去放大。
 *
 * 另有一条独立的轴是「单张模式」——每张照片单独排一张拼版。
 * 两者可叠加：既出两种风格，又每张单排。
 *
 * 纯逻辑，可单测。
 */
object BatchPlan {

    /**
     * 要导出的风格集合。
     *
     * 批量关闭时只有当前这一种 —— 没人想为不需要的第二套等两倍时间。
     */
    fun stylesFor(config: SheetConfig): List<SheetConfig.RenderStyle> =
        if (config.batchExportEnabled) {
            SheetConfig.RenderStyle.entries.toList()
        } else {
            listOf(config.renderStyle)
        }

    /**
     * 单张模式的序号：每个序号对应「第几张照片单独出一张拼版」。
     *
     * 非单张模式返回空列表。
     */
    fun singleIndices(config: SheetConfig, photoCount: Int): List<Int> =
        if (config.singlePhotoMode) (0 until photoCount).toList() else emptyList()

    /** 总共要出几张图。UI 上要提前告诉用户，免得以为卡住了。 */
    fun outputCount(config: SheetConfig, photoCount: Int): Int =
        stylesFor(config).size * maxOf(1, singleIndices(config, photoCount).size)

    /**
     * 一次导出的完整清单。
     *
     * 顺序刻意是「风格在外、照片在内」——同一种风格的文件名挨在一起，
     * 在相册里按名字排序时能聚成一堆。
     */
    data class Item(
        val style: SheetConfig.RenderStyle,
        /** 单张模式下的全局照片下标；整张导出时为 null */
        val singleIndex: Int?,
        val filename: String,
    )

    fun plan(config: SheetConfig, photoCount: Int, explicitName: String? = null): List<Item> {
        if (photoCount <= 0) return emptyList()
        val base = OutputNaming.generate(explicitName, config.info, config.outputFormat)
        val styles = stylesFor(config)
        val singles = singleIndices(config, photoCount)

        if (singles.isEmpty()) {
            return styles.map { style ->
                Item(
                    style = style,
                    singleIndex = null,
                    filename = OutputNaming.outputName(
                        base, style, batch = config.batchExportEnabled,
                        index = null, format = config.outputFormat,
                    ),
                )
            }
        }

        return buildList {
            for (style in styles) {
                for (i in singles) {
                    add(
                        Item(
                            style = style,
                            singleIndex = i,
                            filename = OutputNaming.outputName(
                                base, style, batch = config.batchExportEnabled,
                                index = i + 1, format = config.outputFormat,
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 单张模式下第 [singleIndex] 张拼版该用第几张照片。
     *
     * 越界时夹到有效范围 —— 照片数在「选图完成」到「开始渲染」之间可能变少
     * （用户删了几张），硬取会越界。
     */
    fun photoIndexFor(photoCount: Int, singleIndex: Int): Int =
        singleIndex.coerceIn(0, (photoCount - 1).coerceAtLeast(0))
}

/**
 * 「配置变化是否需要重新预处理照片」的判定。
 *
 * 界面里存的是**已经处理好的缩略图**，而裁切、反相、转向、缩放都发生在
 * 预处理阶段 —— 布局阶段改不了它们。所以这几项一变就得重跑流水线，
 * 否则预览会拿旧画幅的缩略图按新画幅排：看着「排版变了」，照片却是错的。
 *
 * 逐项列出而不是「黑名单」，是因为漏判很隐蔽 —— 要等导出才发现。
 */
object ReprocessRule {

    fun needsReprocess(old: SheetConfig, new: SheetConfig): Boolean =
        old.filmFormat != new.filmFormat ||
            old.subFormat != new.subFormat ||
            old.processingMode != new.processingMode ||
            old.forceLandscape != new.forceLandscape ||
            old.thumbWidth != new.thumbWidth
}
