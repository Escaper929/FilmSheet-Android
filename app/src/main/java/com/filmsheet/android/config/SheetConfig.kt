package com.filmsheet.android.config

import com.filmsheet.android.core.FilmEngine120
import com.filmsheet.android.core.FilmEngine135
import com.filmsheet.android.core.PerfMode

/**
 * 配置模型 — 移植自 iOS 端 `SheetConfig.swift`，对齐 SPEC.md §1。
 *
 * 两端不共享代码（Kotlin 与 Swift 无法互相编译），共享的是这份规格。
 * 改任何默认值或清洗规则，先改 SPEC.md。
 */
data class SheetConfig(

    // 参数
    val thumbWidth: Int = 400,
    val spacing: Int = 20,
    val columns: Int = 6,
    val forceLandscape: Boolean = true,
    val edgeText: String = "",

    // 输出
    val outputFormat: OutputFormat = OutputFormat.JPG,
    val quality: Int = 95,

    // 片基
    val filmFormat: FilmFormat = FilmFormat.F135,
    val subFormat: String = FilmEngine135.DEFAULT_SUB_FORMAT_135,
    val infoLang: InfoLang = InfoLang.EN,

    // 包装图
    val packPosition: PackPosition = PackPosition.LEFT,
    val packBorderStroke: Boolean = true,
    val renderStyle: RenderStyle = RenderStyle.LIGHTBOX,
    val packSize: Int = 80,

    /**
     * 包装图的 content:// URI。
     *
     * 存 URI 而不是字节：一张包装图动辄几 MB，塞进配置对象会让每次参数
     * 变更都复制一遍。渲染时按需解码，且只为取尺寸解到 2048px 上限
     * （见 [com.filmsheet.android.core.LayoutCalculator.placePackImage]）。
     *
     * iOS 端对应的是 `packImageData: Data?`。两端都不把这个字段持久化到
     * 偏好设置里 —— 它是用户每次会话要重新挑的文件。
     */
    val packImageUri: String? = null,

    // 处理
    val processingMode: ProcessingMode = ProcessingMode.POSITIVE,
    val perfMode: PerfMode = PerfMode.AUTO,

    // 信息字段
    val info: Map<InfoKey, String> = emptyMap(),

    // 水印 / 批量
    val signature: String = "",
    val batchExportEnabled: Boolean = false,
    val singlePhotoMode: Boolean = false,
    val singlePhotoShowExtra: Boolean = false,
) {

    enum class FilmFormat(val rawValue: String) {
        F135("135"), F120("120");

        companion object {
            fun fromRaw(v: String): FilmFormat =
                entries.firstOrNull { it.rawValue == v } ?: F135
        }
    }

    enum class ProcessingMode(val rawValue: String) {
        POSITIVE("positive"), NEGATIVE("negative");

        companion object {
            fun fromRaw(v: String): ProcessingMode =
                entries.firstOrNull { it.rawValue == v } ?: POSITIVE
        }
    }

    enum class OutputFormat(val rawValue: String) {
        PNG("PNG"), JPG("JPG");

        companion object {
            fun fromRaw(v: String): OutputFormat =
                entries.firstOrNull { it.rawValue == v } ?: JPG
        }
    }

    enum class InfoLang(val rawValue: String) {
        ZH("zh"), EN("en");

        companion object {
            fun fromRaw(v: String): InfoLang = entries.firstOrNull { it.rawValue == v } ?: EN
        }
    }

    enum class PackPosition(val rawValue: String) {
        LEFT("left"), RIGHT("right");

        companion object {
            fun fromRaw(v: String): PackPosition =
                entries.firstOrNull { it.rawValue == v } ?: LEFT
        }
    }

    enum class RenderStyle(val displayName: String) {
        LIGHTBOX("灯板正片"),
        CONTACT_SHEET("接触印相"),
    }

    fun value(key: InfoKey): String = info[key] ?: ""

    fun withValue(key: InfoKey, value: String): SheetConfig =
        copy(info = info + (key to value))

    // MARK: - 校验（SPEC.md §1）

    /**
     * 当前片基合法的子格式集合。
     *
     * `subFormat` 是**跨字段约束** —— 合法值取决于 `filmFormat`。
     */
    fun validSubFormats(): List<String> = when (filmFormat) {
        FilmFormat.F135 -> SUB_FORMATS_135
        FilmFormat.F120 -> SUB_FORMATS_120
    }

    fun defaultSubFormat(): String = when (filmFormat) {
        FilmFormat.F135 -> FilmEngine135.DEFAULT_SUB_FORMAT_135
        FilmFormat.F120 -> FilmEngine120.DEFAULT_SUB_FORMAT
    }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (thumbWidth !in THUMB_WIDTH_MIN..THUMB_WIDTH_MAX) {
            errors += "thumb_width $thumbWidth out of range [$THUMB_WIDTH_MIN, $THUMB_WIDTH_MAX]"
        }
        if (columns !in 1..99) errors += "columns $columns out of range [1, 99]"
        if (quality !in 1..100) errors += "quality $quality out of range [1, 100]"
        if (packSize !in 10..100) errors += "pack_size $packSize out of range [10, 100]"
        if (subFormat.isNotEmpty() && subFormat !in validSubFormats()) {
            errors += "sub_format '$subFormat' invalid for film_format '${filmFormat.rawValue}'"
        }
        return errors
    }

    /**
     * 清洗 — `config_schema.sanitize_config`。
     *
     * 顺序**不可调换**：缺失的 `subFormat` 要依据 `filmFormat` 决定，
     * 所以子格式的两步必须早于通用 clamp。
     */
    fun sanitized(): SheetConfig {
        var c = this
        if (c.subFormat.isEmpty()) c = c.copy(subFormat = c.defaultSubFormat())
        if (c.subFormat !in c.validSubFormats()) c = c.copy(subFormat = c.defaultSubFormat())
        c = c.copy(
            thumbWidth = c.thumbWidth.coerceIn(THUMB_WIDTH_MIN, THUMB_WIDTH_MAX),
            columns = c.columns.coerceIn(1, 99),
            quality = c.quality.coerceIn(1, 100),
            packSize = c.packSize.coerceIn(10, 100),
        )
        return c
    }

    // MARK: - 信息字段

    /** 任一字段非空（纯空白不算）。 */
    val hasAnyInfo: Boolean
        get() = InfoKey.entries.any { value(it).isNotBlank() }

    /**
     * 填了内容的布局行数 —— 信息块高度按它算，不是固定三行。
     * 只填了卷号的拼版会明显更矮。
     */
    val activeInfoRows: Int
        get() = InfoLayout.rows.count { row ->
            row.count { it != null && value(it).isNotEmpty() } > 0
        }

    /** 三个信息字段的枚举，顺序与 SPEC.md §5.4 一致。 */
    enum class InfoKey(val labels: Pair<String, String>) {
        ROLL("卷号" to "Roll No."),
        CAMERA("相机" to "Cam"),
        FILM("胶卷" to "Film"),
        SHOOT_DATE("拍摄日期" to "Shot Date"),
        DEV_DATE("冲洗日期" to "Dev Date"),
        PROC("冲洗方式" to "Proc"),
        LAB("冲洗地点" to "Lab"),
        SCANNER("扫描仪" to "Scanner"),
        ;

        fun label(lang: InfoLang): String = when (lang) {
            InfoLang.ZH -> labels.first
            InfoLang.EN -> labels.second
        }

        /** 卷号读作 "Roll No. 12" 而非 "Roll No.: 12"，所以它不用冒号。 */
        val usesColon: Boolean get() = this != ROLL
    }

    /** 三行三列，第 2 行第 3 槽为空但仍占横向空间。 */
    object InfoLayout {
        val rows: List<List<InfoKey?>> = listOf(
            listOf(InfoKey.ROLL, InfoKey.CAMERA, InfoKey.FILM),
            listOf(InfoKey.SHOOT_DATE, InfoKey.DEV_DATE, null),
            listOf(InfoKey.PROC, InfoKey.LAB, InfoKey.SCANNER),
        )

        /**
         * 按版面顺序（从上到下、从左到右）列出全部字段。
         *
         * 参数面板要按这个顺序排 —— 用户填「卷号」时得看到「相机」在它下面。
         * 用 [InfoKey.entries] 的话两者**目前**恰好一致，但那是巧合：
         * 有人调整枚举顺序而忘了这里，UI 与成片的顺序就会悄悄错位。
         */
        fun keysInLayoutOrder(): List<InfoKey> =
            rows.flatten().filterNotNull()
    }

    companion object {
        const val THUMB_WIDTH_MIN = 300
        const val THUMB_WIDTH_MAX = 1200

        val SUB_FORMATS_135 = listOf("标准 36×24", "半格 18×24", "方形 24×24", "XPan 65×24")
        val SUB_FORMATS_120 = listOf("645", "66", "67", "68", "69", "612", "617", "624")

        fun subFormatsFor(format: FilmFormat): List<String> = when (format) {
            FilmFormat.F135 -> SUB_FORMATS_135
            FilmFormat.F120 -> SUB_FORMATS_120
        }

        /**
         * 画幅宽高比（W/H）— SPEC.md §3。
         *
         * 135 的所有子格式共用 1.5。120 各子格式按真实 mm 宽除以固定 56mm 高 ——
         * **645 是唯一的例外**：它是竖幅格式，SPEC.md §3 明确规定为
         * `56 / 41.5` 而不是通用的 `41.5 / 56`。所以这里不能用一条公式
         * 盖过整个表，645 必须单列。
         *
         * iOS 端 `SheetConfig.swift` 的 `filmFormatRatios` 是硬编码字面量表，
         * 645 那条写的是 `56.0 / 41.5`，与此一致。
         */
        val filmFormatRatios: Map<String, Double> = buildMap {
            put("135", 1.5)
            for ((name, widthMM) in FilmEngine120.imageWidthsMM) {
                put(
                    name,
                    if (name == "645") {
                        FilmEngine120.IMAGE_HEIGHT_MM / widthMM
                    } else {
                        widthMM / FilmEngine120.IMAGE_HEIGHT_MM
                    },
                )
            }
        }
    }
}