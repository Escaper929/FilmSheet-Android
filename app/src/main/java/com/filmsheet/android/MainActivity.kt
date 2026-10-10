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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.ui.SheetController
import com.filmsheet.android.ui.SheetScreen
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val controller = remember { SheetController(context) }

    // 配置提到这一层：保存时要用与渲染时**同一份**配置决定输出格式与质量。
    // 放在 Screen 内部的话，保存那一刻就拿不到了。
    var config by remember { mutableStateOf(SheetConfig()) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }

    SheetScreen(
        config = config,
        onConfigChange = { config = it },
        onSave = { bitmap: ImageBitmap ->
            scope.launch {
                val uri = controller.saveToGallery(bitmap, config)
                savedUri = uri
                if (uri != null) {
                    // 存完直接弹系统分享，省得用户自己去相册翻找
                    val mime = if (config.outputFormat == SheetConfig.OutputFormat.PNG) {
                        "image/png"
                    } else {
                        "image/jpeg"
                    }
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "分享拼版"))
                }
            }
        },
        savedUri = savedUri,
    )
}
