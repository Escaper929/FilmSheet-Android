package com.filmsheet.android.verify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File

/**
 * 把 Android 端的渲染结果写到 `/sdcard/Download/fsa/<case>.png`。
 *
 * 用来**肉眼比对**——meanD 只告诉你「差多少」，不告诉你「差在哪」。
 * 看到并排的两张图，几秒就能判断是裁切偏移、旋转方向还是文字位置。
 *
 * 跑法：
 * ```bash
 * ./scripts/gradlew-local.sh connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=...DumpRenders
 * adb pull /sdcard/Download/fsa
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DumpRenders {

    @Test
    fun dumpAllCases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // /sdcard/Download 在 API 29+ 需要 scoped storage 许可，
        // 用 app 专属外部目录最省事，adb pull 一样能取
        val outDir = File(context.getExternalFilesDir(null), "renders").apply { mkdirs() }

        for (case in VerifyCases.NAMES) {
            val bmp = renderCase(case)
            File(outDir, "$case.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            println("dump $case -> ${bmp.width}x${bmp.height}")
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
