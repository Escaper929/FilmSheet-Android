package com.filmsheet.android.core

/**
 * 片边文字 — 移植自 iOS 端 `EdgeText.swift`，规格见 SPEC.md §7。
 *
 * 真实 135 胶片沿片边印片名：上行是品牌 + 型号，下行是帧计数器。
 * 这里负责把用户填的「胶卷」字段翻译成那行上行文字，外加一个稳定的批号。
 */
data class EdgeText(
    val brand: String,
    val filmType: String,
    val lotCode: String,
    val direction: String,
    val isCustom: Boolean,
) {
    /** 画在片边上行的那一行：`"KODAK  Portra 400"`。注意是**两个空格**。 */
    val topLine: String
        get() = if (filmType.isEmpty()) brand else "$brand  $filmType"
}

object EdgeTextGenerator {

    /**
     * 中文品牌 → 英文。中文键与其音译/大写形式映射到同一值，
     * 这样 `柯达` 与 `Kodak` 行为完全一致。
     */
    private val brandMap: Map<String, String> = buildMap {
        put("柯达", "KODAK"); put("Kodak", "KODAK"); put("KODAK", "KODAK")
        put("富士", "FUJIFILM"); put("Fujifilm", "FUJIFILM"); put("FUJIFILM", "FUJIFILM")
        put("柯尼卡", "KONICA"); put("Konica", "KONICA"); put("KONICA", "KONICA")
        put("伊尔福", "ILFORD"); put("Ilford", "ILFORD"); put("ILFORD", "ILFORD")
        put("阿克发", "AGFA"); put("Agfa", "AGFA"); put("AGFA", "AGFA")
        put("乐凯", "LUCKY"); put("Lucky", "LUCKY"); put("LUCKY", "LUCKY")
        put("波尔", "PORST"); put("Porst", "PORST"); put("PORST", "PORST")
        put("斯达法", "STADIA"); put("Stadia", "STADIA"); put("STADIA", "STADIA")
        put("哈苏", "HASSELBLAD"); put("Hasselblad", "HASSELBLAD"); put("HASSELBLAD", "HASSELBLAD")
    }

    /** 中文型号 → 英文，兼带大小写修正（`Portra 160nc` → `Portra 160NC`）。 */
    private val typeMap: Map<String, String> = buildMap {
        put("Portra 160", "Portra 160"); put("Portra 160nc", "Portra 160NC")
        put("Portra 400", "Portra 400"); put("Portra 400nc", "Portra 400NC")
        put("Portra 800", "Portra 800")
        put("Ektar 100", "Ektar 100")
        put("Gold 200", "Gold 200"); put("Ultramax 200", "Ultramax 200")
        put("Ultramax 400", "Ultramax 400"); put("Supra 200", "Supra 200")
        put("Supra 400", "Supra 400")
        put("ColorPlus 200", "ColorPlus 200")
        put("Tri-X 400", "Tri-X 400"); put("T-Max 100", "T-Max 100")
        put("T-Max 400", "T-Max 400"); put("Panatomic-X", "Panatomic-X")
        put("Pro 400H", "Pro 400H")
        put("Velvia 50", "Velvia 50"); put("Provia 100F", "Provia 100F")
        put("Astia 100F", "Astia 100F"); put("Eterna 500T", "Eterna 500T")
        put("Vision3 500T", "Vision3 500T"); put("Vision3 250D", "Vision3 250D")
        put("Vision3 50D", "Vision3 50D")
        put("Superia 200", "Superia 200"); put("Superia 400", "Superia 400")
        put("Superia 800", "Superia 800")
        put("CineVision 500T", "CineVision 500T")
        put("5207", "5207"); put("5219", "5219"); put("5294", "5294")
        put("5203", "5203"); put("5222", "5222")
        put("横轴", "横轴"); put("纵轴", "纵轴")
        put("C200", "C200"); put("c200", "C200")
    }

    /**
     * 子串匹配必须**长键优先**，否则 `Portra 400` 里会先撞上假想的 `Portra`
     * 前缀而拿到错的结果。预先排好序，避免每次生成都重排。
     */
    private val typeKeysByLengthDesc: List<String> =
        typeMap.keys.sortedByDescending { it.length }

    /** 电影胶片无论零售品牌是什么，片边都印 EASTMAN。 */
    private val cinemaKeywords = listOf(
        "VISION3", "CINEVISION", "EKTACHROME", "MOTION PICTURE",
        "5207", "5219", "5294", "5203", "5222",
    )

    private val brandPrefixes = listOf("柯达", "Kodak", "KODAK", "富士", "FUJIFILM")

    /**
     * @param infoFilm 用户填的「胶卷」字段
     * @param customEdgeText 用户自定义片边文字，非空时**完全跳过**所有映射
     */
    fun generate(infoFilm: String, customEdgeText: String = ""): EdgeText {
        val custom = customEdgeText.trim()
        if (custom.isNotEmpty()) {
            // 自定义文字直接用 —— 用户会填 `AGFA OPTIMA 200` 这类任何表都不覆盖的东西。
            return EdgeText(custom, "", "", "◀", isCustom = true)
        }

        val raw = infoFilm.trim()
        val upperFilm = raw.uppercase()

        val brand: String
        val filmType: String

        if (cinemaKeywords.any { upperFilm.contains(it) }) {
            brand = "EASTMAN"
            filmType = stripBrandPrefix(raw)
        } else {
            val parts = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var rawBrand = parts.firstOrNull() ?: ""
            var rawFilmType = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""

            // `乐凯C200` 没有空格，上面的拆分会把整串当成品牌。
            // 所以品牌表未命中时，再试一次中文品牌前缀匹配。
            if (brandMap[rawBrand] == null) {
                var matched = false
                for (cn in brandMap.keys.filter { !it.isAscii() }) {
                    if (rawBrand.startsWith(cn)) {
                        rawBrand = cn
                        rawFilmType = raw.drop(cn.length).trim()
                        matched = true
                        break
                    }
                }
                if (!matched) rawFilmType = raw
            }

            brand = brandMap[rawBrand] ?: rawBrand.uppercase()
            filmType = mapFilmType(rawFilmType)
        }

        // 批号由 品牌+型号 哈希而来。Python 的 `hash()` 每进程加盐，
        // 批号会每次运行都变；FNV-1a 给出同样的「一卷一码」性质且可复现。
        val lotHash = fnv1a24(brand + filmType)
        val lotCode = "E${lotHash / 10000 % 10} ${lotHash % 10000}"

        return EdgeText(brand, filmType, lotCode, "◀", isCustom = false)
    }

    private fun stripBrandPrefix(text: String): String {
        for (p in brandPrefixes) {
            if (text.startsWith(p)) return text.drop(p.length).trim()
        }
        return text
    }

    /** 匹配顺序：精确 → 忽略大小写 → 子串（长键优先）。 */
    private fun mapFilmType(raw: String): String {
        typeMap[raw]?.let { return it }
        val lower = raw.lowercase()
        for ((k, v) in typeMap) {
            if (k.lowercase() == lower) return v
        }
        // 子串兜底：`Kodak Portra 400` 里含 `Portra 400`。
        for (k in typeKeysByLengthDesc) {
            if (raw.contains(k)) return typeMap.getValue(k)
        }
        return raw
    }

    private fun String.isAscii(): Boolean = all { it.code < 128 }

    /**
     * FNV-1a 24 位 — SPEC.md §7.4。
     *
     * 用它而不是语言内置哈希，是因为内置哈希要么每进程加盐（批号不稳定），
     * 要么在不同语言间结果不同（两端批号会漂移）。
     *
     * `hash *= FNV_PRIME` 溢出 Int 是**刻意**的：JVM 的 Int 回绕语义正好
     * 等价于 iOS 端的 `UInt32 &*`，两端批号因此逐位相同。
     */
    private fun fnv1a24(s: String): Int {
        // 0x811C9DC5 是 FNV offset basis。作为字面量它超出 Int 正数范围，
        // 会被 Kotlin 推成 Long，所以显式 toInt() 把它钉回 32 位。
        var hash = FNV_OFFSET_BASIS
        for (b in s.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toInt() and 0xFF)
            hash *= FNV_PRIME
        }
        return hash and 0xFFFFFF
    }

    private const val FNV_OFFSET_BASIS = 0x811C9DC5.toInt()
    private const val FNV_PRIME = 0x01000193
}
