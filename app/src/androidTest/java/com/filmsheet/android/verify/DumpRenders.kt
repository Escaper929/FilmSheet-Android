package com.filmsheet.android.verify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.render.ImageOps
import com.filmsheet.android.render.Renderer120
import com.filmsheet.android.render.Renderer135
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * 把 Android 端的渲染结果**编码进 logcat**。
 *
 * 两个用途：
 *
 * 1. **肉眼比对** —— meanD 只说「差多少」，不说「差在哪」。并排看两张图，
 *    几秒就能判断是裁切偏移、旋转方向还是文字位置。
 * 2. **跨端交叉验证** —— 喂给 iOS 端的 `compare.py`，就能在 PC 上分析
 *    两端的差异，而不必在 Mac 上跑模拟器。
 *
 * ## 为什么走 logcat 而不是落盘
 *
 * 写文件当然更直接，但 instrumented 测试跑完**被测应用会被卸载**，
 * 它的 externalFilesDir 随之消失 —— adb pull 扑空。
 * （实测就是这样：`find /sdcard -type d -name renders` 什么都不剩。）
 *
 * logcat 不受那个生命周期影响。代价是走 Base64，所以只在这一个
 * 「按需诊断」的测试里用，正式的对照测试 `PixelParityTests`
 * 直接在设备内比对像素，不传任何图。
 *
 * 跑法： `./scripts/dump-renders.sh`
 */
@RunWith(AndroidJUnit4::class)
class DumpRenders {

    @Test
    fun dumpAllCases() {
        for (case in VerifyCases.NAMES) {
            val bmp = renderCase(case)
            val bytes = ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            // 分段打印：logcat 单行有长度上限，整段一行会被截断成废图。
            val chunk = 3000
            println("@@B64START@@ $case ${bmp.width}x${bmp.height}")
            b64.chunked(chunk).forEach { println("@@B64@@$it") }
            println("@@B64END@@ $case")
        }
    }

    private fun renderCase(case: String): Bitmap {
        val config = VerifyCases.configFor(case)
        val raw = loadInputs(case)
        assertTrue("用例 $case 没有可用输入帧", raw.isNotEmpty())

        val processed = raw.map { bmp ->
            when (config.filmFormat) {
                SheetConfig.FilmFormat.F135 -> ImageOps.process135(
                    bmp, config.thumbWidth, config.processingMode,
                    config.forceLandscape, config.subFormat,
                )
                SheetConfig.FilmFormat.F120 -> ImageOps.process120(
                    bmp, config.subFormat, config.thumbWidth,
                    config.processingMode, config.forceLandscape,
                )
            }
        }

        val images = processed.map { it.asImageBitmap() }
        return when (config.filmFormat) {
            SheetConfig.FilmFormat.F135 ->
                Renderer135(config, images).render().asAndroidBitmap()
            SheetConfig.FilmFormat.F120 ->
                Renderer120(config, images).render().asAndroidBitmap()
        }
    }

    private fun loadInputs(case: String): List<Bitmap> {
        val names = listOf(
            "frame01_landscape", "frame02_portrait", "frame03_square",
            "frame04_ultrawide", "frame05_landscape", "frame06_portrait",
        )
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val all = names.mapNotNull { name ->
            runCatching {
                assets.open("inputs/$name.png").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        return if (VerifyCases.usesOnlyFirstFrame(case)) all.take(1) else all
    }
}
