package com.moyue.reader

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.moyue.reader.ui.external.ExternalOpenIntentReader
import com.moyue.reader.ui.navigation.MoYueNavHost
import com.moyue.reader.ui.theme.MoYueTheme

/**
 * 单 Activity 宿主。
 *
 * 应用采用 Compose 单 Activity 架构：所有页面由 NavHost 承载，
 * Activity 只负责窗口沉浸式配置与根主题。
 */
class MainActivity : ComponentActivity() {

    private val container
        get() = (application as MoYueApplication).container

    private val diagnostics
        get() = container.diagnostics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logLaunch(savedInstanceState != null)
        // 冷启动的“用其他应用打开”。savedInstanceState 非空说明是旋转/分屏或进程恢复后的
        // 意图重投递：此时导入早已入队或完成，再处理一次会重复导入，必须跳过。
        if (savedInstanceState == null) enqueueExternalOpen(intent)
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

    /**
     * `launchMode=singleTop`：应用已在运行时（书架/设置/阅读器）再收到“用其他应用打开”，
     * 系统复用本 Activity 并回调此方法。没有它，新 Intent 会被静默丢弃。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 同步给 getIntent()，便于后续排查；不影响已入队的请求。
        setIntent(intent)
        enqueueExternalOpen(intent)
    }

    /**
     * 把 ACTION_VIEW Intent 交给根级队列。
     *
     * 这里只做“是否像一本书”的判定（action/scheme），不读取文件：
     * 读取必须发生在 ViewModel 的 IO 上下文，且 `content://` 的临时读权限
     * 需要在回到 UI 线程之前尽快用掉。
     */
    private fun enqueueExternalOpen(intent: Intent?) {
        val request = ExternalOpenIntentReader.read(intent) ?: return
        container.externalOpenQueue.submit(request)
    }

    /**
     * 记录一次关键的启动/重建事件。
     *
     * 只写设备与屏幕/字体缩放等排障所需的元数据，不写任何用户内容；
     * 同一进程只写一次 app.start，旋转/分屏重建写 app.recreate。
     */
    private fun logLaunch(isRecreate: Boolean) {
        val metrics = resources.displayMetrics
        val config = resources.configuration
        val fields = mutableMapOf<String, Any?>(
            "version" to BuildConfig.VERSION_NAME,
            "build" to BuildConfig.VERSION_CODE,
            "debug" to BuildConfig.DEBUG,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE,
            "api" to Build.VERSION.SDK_INT,
            "w" to metrics.widthPixels,
            "h" to metrics.heightPixels,
            "density" to metrics.density,
            "font_scale" to config.fontScale,
            "locale" to if (config.locales.isEmpty) null else config.locales[0].toLanguageTag(),
        )
        if (isRecreate) {
            fields["config_change"] = isChangingConfigurations
            diagnostics.log("app.recreate", fields)
        } else {
            fields["logs"] = diagnostics.logDirectoryPath()
            diagnostics.log("app.start", fields)
        }
    }

    /**
     * 应用离开前台即锁定隐藏书架。
     *
     * 必须在 `super.onStop()` 之后锁定：Lifecycle 会把 ON_STOP 同步分发给
     * ReaderScreen，先让它保存当前阅读进度；若提前锁定，ReaderViewModel 会
     * 先把状态切成锁定错误页，导致进度快照丢失。
     *
     * 配置变更（旋转、分屏尺寸变化）也会回调 onStop，但进程与用户会话仍在，
     * 按设计保持解锁；`isChangingConfigurations` 用于区分这两种情况。
     */
    override fun onStop() {
        super.onStop()
        diagnostics.log(
            "app.background",
            mapOf("config_change" to isChangingConfigurations),
        )
        diagnostics.flush()
        if (!isChangingConfigurations) {
            container.hiddenShelfSession.lock()
            diagnostics.log("hidden.lock")
            diagnostics.flush()
        }
    }
}
