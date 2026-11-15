package com.moyue.reader.platform.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.moyue.reader.domain.system.BatteryStatusProvider
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 基于系统 `ACTION_BATTERY_CHANGED` 广播的电量实现。
 *
 * 关键约束：
 * - `ACTION_BATTERY_CHANGED` 是 **sticky** 广播：`registerReceiver` 的返回值就是当前电量，
 *   必须在注册后立即解析并推送；系统不会为 sticky 值再回调一次 `onReceive`。
 * - 该广播只能动态注册，且属于受保护广播，无需任何权限；应用保持零权限、纯离线。
 * - 电量变化通常只按 1% 触发，但充电时电压/温度变化也可能触发广播；
 *   在流上做整数去重，避免同一百分比反复重组 UI。
 * - 使用 Application Context 注册，避免持有 Activity；订阅结束（`awaitClose`）立即注销，
 *   防止阅读页退出后仍收到广播泄漏。
 */
class AndroidBatteryStatusProvider(context: Context) : BatteryStatusProvider {

    // 只持有 Application Context：注册/注销的生命周期由订阅方控制，不能泄漏页面。
    private val appContext = context.applicationContext

    override val batteryPercent: Flow<Int> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                batteryPercentFrom(intent)?.let { trySend(it) }
            }
        }
        // sticky 返回值与后续 onReceive 共用同一套解析逻辑，避免两条路径算出不同结果。
        // RECEIVER_NOT_EXPORTED：只接收系统广播，不允许其他应用伪造电量；
        // ContextCompat 会为 Android 13 以下自动降级处理注册标志。
        val sticky = ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        batteryPercentFrom(sticky)?.let { trySend(it) }

        awaitClose { appContext.unregisterReceiver(receiver) }
    }
        // 只关心整数百分比变化；conflate 保证快速连续变化时 UI 只消费最新值，不排队抖动。
        .distinctUntilChanged()
        .conflate()
}

/** 从 sticky 广播 Intent 中读取并解析电量；Intent 为空或字段非法时返回 null。 */
internal fun batteryPercentFrom(intent: Intent?): Int? {
    if (intent == null) return null
    return batteryPercent(
        level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
    )
}

/**
 * 把系统给出的 `level/scale` 换算为 0..100 的整数百分比。
 *
 * 为什么不直接用 level：OEM 不保证 scale 恒为 100，必须按比例换算；
 * 用四舍五入而不是整数除法，避免 2/3 被截成 66。
 * - level < 0 表示系统没有给出有效值（`getIntExtra` 的默认值），丢弃；
 * - scale <= 0 属于异常数据，若 level 本身在 0..100 则按百分比兜底，否则丢弃；
 * - 超出 scale 的脏数据截断到 100，避免 UI 出现 `105%`。
 */
internal fun batteryPercent(level: Int, scale: Int): Int? {
    if (level < 0) return null
    if (scale <= 0) return level.takeIf { it in 0..100 }
    return (level * 100.0 / scale).roundToInt().coerceIn(0, 100)
}
