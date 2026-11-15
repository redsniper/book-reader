package com.moyue.reader.platform.battery

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `AndroidBatteryStatusProvider` 的 Robolectric 集成测试。
 *
 * 验证三件事：系统广播能推进流、同一整数百分比被去重、取消订阅后接收器注销。
 * 真实的 sticky 广播由 Android 系统在 `registerReceiver` 时返回，Robolectric 不模拟
 * sticky 时间戳；因此“注册即得当前电量”的生产路径由实现中的
 * `batteryPercentFrom(sticky)` 保证，并通过 Intent 解析用例覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBatteryStatusProviderTest {

    private fun batteryIntent(level: Int, scale: Int): Intent =
        Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, level)
            putExtra(BatteryManager.EXTRA_SCALE, scale)
        }

    @Test
    fun `parses percent from battery intent extras`() {
        assertEquals(85, batteryPercentFrom(batteryIntent(level = 85, scale = 100)))
        assertEquals(67, batteryPercentFrom(batteryIntent(level = 2, scale = 3)))
        // 缺少 extras 时不能把 -1 当成有效电量。
        assertEquals(null, batteryPercentFrom(Intent(Intent.ACTION_BATTERY_CHANGED)))
    }

    @Test
    fun `broadcast pushes percent and deduplicates equal values`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = AndroidBatteryStatusProvider(context)
        val values = mutableListOf<Int>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.batteryPercent.collect { values += it }
        }
        context.sendBroadcast(batteryIntent(level = 42, scale = 100))
        // 充电时电压/温度变化也会触发广播，同一百分比不应重复推送。
        context.sendBroadcast(batteryIntent(level = 42, scale = 100))
        context.sendBroadcast(batteryIntent(level = 43, scale = 100))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(42, 43), values)
        job.cancel()
    }

    @Test
    fun `cancelling collection unregisters receiver`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val shadow = shadowOf(application)
        val before = shadow.registeredReceivers.size

        val provider = AndroidBatteryStatusProvider(application)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            provider.batteryPercent.collect { }
        }
        assertEquals(before + 1, shadow.registeredReceivers.size)

        job.cancel()
        // awaitClose 在取消时执行注销，不能把接收器留给已经退出的阅读页。
        assertEquals(before, shadow.registeredReceivers.size)
    }
}
