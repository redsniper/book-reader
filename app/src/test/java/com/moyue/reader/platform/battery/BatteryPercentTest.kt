package com.moyue.reader.platform.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 电量 level/scale 换算的纯逻辑测试。
 *
 * 这里不依赖 Android：百分比换算只需要两个整数，边界（0%、100%、无效 scale、
 * 超出 scale 的脏数据）必须确定。广播注册/推送/注销由
 * [AndroidBatteryStatusProviderTest] 在 Robolectric 下覆盖。
 */
class BatteryPercentTest {

    @Test
    fun `converts normal level and scale`() {
        assertEquals(85, batteryPercent(level = 85, scale = 100))
        assertEquals(0, batteryPercent(level = 0, scale = 100))
        assertEquals(100, batteryPercent(level = 100, scale = 100))
    }

    @Test
    fun `keeps non-100 scale proportional and rounds`() {
        // 不能写成整数除法：2/3 会被截成 66。
        assertEquals(67, batteryPercent(level = 2, scale = 3))
        assertEquals(33, batteryPercent(level = 1, scale = 3))
        assertEquals(25, batteryPercent(level = 50, scale = 200))
    }

    @Test
    fun `clamps dirty level above scale`() {
        // OEM 脏数据不能把 105% 显示给用户。
        assertEquals(100, batteryPercent(level = 120, scale = 100))
    }

    @Test
    fun `falls back to level when scale is missing`() {
        // 个别设备不返回 EXTRA_SCALE；level 本身在 0..100 时可当作百分比使用。
        assertEquals(42, batteryPercent(level = 42, scale = 0))
        assertEquals(42, batteryPercent(level = 42, scale = -1))
    }

    @Test
    fun `rejects missing or invalid values`() {
        // getIntExtra 的默认值是 -1；没有有效电量时 UI 应保持不显示。
        assertNull(batteryPercent(level = -1, scale = 100))
        assertNull(batteryPercent(level = -1, scale = -1))
        // scale 无效且 level 也不可能是一个百分比。
        assertNull(batteryPercent(level = 101, scale = 0))
    }
}
