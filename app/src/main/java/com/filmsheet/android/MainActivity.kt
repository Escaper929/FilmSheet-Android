package com.filmsheet.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.data.ConfigStore
import com.filmsheet.android.ui.SheetScreen

/**
 * 唯一 Activity — 对应 iOS 端的 `FilmSheetApp` + `ContentView`。
 *
 * 单 Activity 是 Compose 的常规做法：所有界面都是 composable，
 * 导航用状态而非 Intent，省掉一整套 Navigation 组件的依赖。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 配色跟着渲染层的灯板正片走：橙色是片边文字的颜色，
            // 让 UI 与产出物看起来是一套东西。
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFFF8C3C))) {
                FilmSheetApp()
            }
        }
    }
}

@Composable
private fun FilmSheetApp() {
    val context = LocalContext.current
    val store = remember { ConfigStore(context) }

    // 配置的唯一真相。启动时读一次，之后每次改动立刻写回偏好文件 ——
    // 用户下次打开 App 看到的还是上次的设置。
    var config by remember { mutableStateOf(store.load()) }

    SheetScreen(
        config = config,
        onConfigChange = { newConfig ->
            config = newConfig
            store.save(newConfig)
        },
        shareLatest = { uri ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeOf(config.outputFormat)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "分享拼版"))
        },
    )
}

/** MIME 类型由输出格式决定 —— JPG 与 PNG 的分享 intent 不一样。 */
internal fun mimeTypeOf(format: SheetConfig.OutputFormat): String =
    if (format == SheetConfig.OutputFormat.PNG) "image/png" else "image/jpeg"
