package com.moyue.reader.domain.system

import kotlinx.coroutines.flow.Flow

/**
 * 阅读器顶部状态带所需的系统电量来源。
 *
 * 为什么抽成接口：
 * - UI 只关心“0..100 的整数百分比”，不应接触 Android 的 `Intent`、广播注册或
 *   `BatteryManager`，否则 Compose 组件会与平台 API 绑死、无法用纯 JVM 测试；
 * - 电量是平台能力，具体实现放在 platform 层（[com.moyue.reader.platform.battery.AndroidBatteryStatusProvider]），
 *   由 `AppContainer` 注入，符合“UI → domain ← platform”的依赖方向；
 * - 测试可以注入固定值或受控的 `MutableStateFlow`，无需真实电池硬件。
 *
 * 契约：
 * - 冷流，可重复订阅。每次订阅应尽快给出当前电量（系统支持时），随后随电量变化推送；
 * - 只推送 `0..100` 的整数百分比；同一百分比不重复推送（由实现去重）；
 * - 订阅取消后必须停止监听，不得遗留 `BroadcastReceiver` 或阻塞线程；
 * - 设备没有电池、或系统无法给出有效电量时允许不发射任何值，UI 此时不显示电量文本。
 */
interface BatteryStatusProvider {

    /**
     * 当前电量百分比的观察流。
     *
     * 阅读页在组合期间订阅；应用进入后台时由 lifecycle 暂停订阅，
     * 回到前台后重新订阅并立即读取系统 sticky 广播，因此不会显示过期的旧电量。
     */
    val batteryPercent: Flow<Int>
}
