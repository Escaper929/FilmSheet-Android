package com.filmsheet.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * 唯一 Activity — 对应 iOS 端的 `FilmSheetApp` + `ContentView`。
 *
 * 单 Activity 是 Compose 的常规做法：所有界面都是 composable，
 * 导航用状态而非 Intent，省掉一整套 Navigation 组件的依赖。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { }
    }
}
