package com.filmsheet.android.render

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max
import kotlin.math.min

/**
 * 文本锚点 — 对应 iOS 端 `TextAnchor` 与桌面端 PIL 的 anchor 参数。
 *
 * `TOP_LEFT` 相当于 PIL 的 `"la"`（左边缘 × ascender 线），
 * `MIDDLE` 相当于 `"mm"`（水平居中于 em 盒）。
 */
enum class TextAnchor { TOP_LEFT, MIDDLE }

/**
 * 绘制画布 — 移植自 iOS 端 `Canvas.swift`，替代桌面端的 PIL `ImageDraw`。
 *
 * ## 与另外两端的两处「有意分歧」
 *
 * 1. **不做 4 倍超采样**。桌面端 PIL 的 `ImageDraw` 完全没有抗锯齿，
 *    所以它画在 4 倍画布上再缩回来，靠缩放这一步完成边缘混合。
 *    iOS 的 CoreGraphics 与 Android 的 Skia 都原生抗锯齿，**1 倍绘制即可** ——
 *    更快、更干净，峰值内存还降到约 1/16（桌面端 `thumb_width=1200`
 *    时需要约 2.9 GB）。
 *
 * 2. **不做 y 轴翻转**。iOS 端必须翻转，因为 CoreGraphics 原点在左下角，
 *    而排版数字全部从 Python 的左上原点口径移植过来。
 *    Compose / Skia 本身就是左上原点，**画布空间与布局空间天然一致** ——
 *    照抄 iOS 的翻转反而会把整张拼版上下颠倒。
 *
 * 坐标一律沿用左上原点：x 向右，y 向下，与 [com.filmsheet.android.core.SheetLayout]
 * 的全部数字口径相同。
 *
 * 字体按最终字号直接栅格化，不经超采样，所以字形是针对真实像素网格
 * 生成的 —— 这一点与 iOS 端用 CoreText 的理由相同。
 */
class SheetCanvas(
    val width: Int,
    val height: Int,
    background: Color,
) {
    val bitmap: Bitmap = Bitmap.createBitmap(
        max(1, width), max(1, height), Bitmap.Config.ARGB_8888,
    )

    private val canvas = android.graphics.Canvas(bitmap)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    init {
        fillPaint.color = background.toArgbInt()
        canvas.drawColor(background.toArgbInt())
    }

    // MARK: - 字体

    private val fontCache = mutableMapOf<Int, Typeface>()

    /**
     * 字体缓存。
     *
     * 桌面端从磁盘加载 PingFang/STHeiti；Android 端系统自带中文与西文字体，
     * 直接用 [Typeface.DEFAULT] 即可 —— 缺字时系统会自行回退，
     * 而片边文字里出现中文本来就是预期行为（品牌映射表里有中文键）。
     */
    fun font(sizePx: Int): Typeface = fontCache.getOrPut(sizePx) {
        if (sizePx <= 0) Typeface.DEFAULT else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    /** 文本像素宽 — 缓存，因为信息块要反复量同一批字符串。 */
    private val widthCache = mutableMapOf<String, Int>()

    fun textWidth(text: String, font: Typeface, sizePx: Int): Int {
        if (text.isEmpty()) return 0
        val key = "$text@$sizePx"
        widthCache[key]?.let { return it }
        textPaint.typeface = font
        textPaint.textSize = sizePx.toFloat()
        val w = textPaint.measureText(text).toInt()
        widthCache[key] = w
        return w
    }

    // MARK: - 形状

    fun fill(x: Int, y: Int, w: Int, h: Int, color: Color) {
        fillPaint.color = color.toArgbInt()
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), fillPaint)
    }

    /**
     * 描边。
     *
     * 向内缩半个线宽 —— Android 的 `drawRect` 描边以边界为中心，
     * 不缩的话会有一半落在矩形外面。
     */
    fun stroke(x: Int, y: Int, w: Int, h: Int, color: Color, lineWidth: Int) {
        if (lineWidth <= 0) return
        strokePaint.color = color.toArgbInt()
        strokePaint.strokeWidth = lineWidth.toFloat()
        val half = lineWidth / 2f
        canvas.drawRect(
            x + half, y + half, x + w - half, y + h - half, strokePaint,
        )
    }

    /**
     * 圆角矩形。
     *
     * 桌面端 PIL 的 `rounded_rectangle(radius=)` 说的是**圆角圆的半径**，
     * Android 的 `drawRoundRect` 参数相同，所以无需换算。但仍要夹住上限：
     * 半径大于边长一半时 Skia 会画成椭圆，与 PIL 的行为不同。
     */
    fun fillRounded(x: Int, y: Int, w: Int, h: Int, radius: Int, color: Color) {
        if (w <= 0 || h <= 0) return
        fillPaint.color = color.toArgbInt()
        // 半径要同时转成 Float，且夹住上限 —— 半径大于边长一半时
        // Skia 会画成椭圆，与桌面端 PIL 的行为不同。
        val r = min(radius.toFloat(), minOf(w, h) / 2f)
        canvas.drawRoundRect(
            x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), r, r, fillPaint,
        )
    }

    fun fillEllipse(x: Int, y: Int, w: Int, h: Int, color: Color) {
        fillPaint.color = color.toArgbInt()
        canvas.drawOval(
            x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), fillPaint,
        )
    }

    /** 多边形填充，少于三个点直接返回 —— 与 iOS 端的守卫一致。 */
    fun fillPolygon(pts: List<Pair<Float, Float>>, color: Color) {
        if (pts.size < 3) return
        fillPaint.color = color.toArgbInt()
        path.reset()
        path.moveTo(pts[0].first, pts[0].second)
        for (i in 1 until pts.size) path.lineTo(pts[i].first, pts[i].second)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    // MARK: - 文本

    /**
     * 画文本。
     *
     * [TOP_LEFT] 的 y 落在 **ascent 线**上（不是基线），这样
     * `absY = topMargin + topPad + row × lineHeight` 这种从桌面端移植来的
     * 公式才能对得上 —— 桌面端 PIL 的 `"la"` 锚点语义如此。
     */
    fun drawText(
        text: String,
        x: Int,
        y: Int,
        font: Typeface,
        sizePx: Int,
        color: Color,
        anchor: TextAnchor = TextAnchor.TOP_LEFT,
    ) {
        if (text.isEmpty() || sizePx <= 0) return
        textPaint.typeface = font
        textPaint.textSize = sizePx.toFloat()
        textPaint.color = color.toArgbInt()

        var drawX = x.toFloat()
        val baseline: Float = when (anchor) {
            TextAnchor.TOP_LEFT -> {
                val fm = textPaint.fontMetrics
                // PIL 的 "la"：y 落在 ascender 线上，基线在其下方。
                y - fm.ascent
            }
            TextAnchor.MIDDLE -> {
                val w = textWidth(text, font, sizePx)
                drawX -= w / 2f
                val fm = textPaint.fontMetrics
                // PIL 的 "m"：在 ascender..descender 跨度上居中。
                y - (fm.ascent + fm.descent) / 2f
            }
        }
        canvas.drawText(text, drawX, baseline, textPaint)
    }

    // MARK: - 图像合成

    /** 把位图缩放绘制到指定矩形。 */
    fun drawImage(src: ImageBitmap, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val bmp = src.asAndroidBitmap()
        val dst = Rect(x, y, x + w, y + h)
        // `filterBitmap` 是缩放质量的关键：关掉它，缩略图会呈现锯齿。
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bmp, null, dst, p)
    }

    /** 整数左上角贴图 — 对应桌面端 `canvas.paste(img, (x, y))`。 */
    fun paste(src: ImageBitmap, x: Int, y: Int) {
        drawImage(src, x, y, src.width, src.height)
    }

    // MARK: - 输出

    fun toImageBitmap(): ImageBitmap = bitmap.asImageBitmap()
}

/** Compose [Color] → 0xAARRGGBB 整数的直接转换。 */
internal fun Color.toArgbInt(): Int =
    android.graphics.Color.argb(
        (alpha * 255f + 0.5f).toInt(),
        (red * 255f + 0.5f).toInt(),
        (green * 255f + 0.5f).toInt(),
        (blue * 255f + 0.5f).toInt(),
    )
