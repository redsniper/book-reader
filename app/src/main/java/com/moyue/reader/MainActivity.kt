package com.moyue.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.moyue.reader.ui.navigation.MoYueNavHost
import com.moyue.reader.ui.theme.MoYueTheme

/**
 * 单 Activity 宿主。
 *
 * 应用采用 Compose 单 Activity 架构：所有页面由 NavHost 承载，
 * Activity 只负责窗口沉浸式配置与根主题。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15（targetSdk 35）强制 edge-to-edge，显式启用并由各页面处理 insets。
        enableEdgeToEdge()
        setContent {
            MoYueTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MoYueNavHost()
                }
            }
        }
    }
}
