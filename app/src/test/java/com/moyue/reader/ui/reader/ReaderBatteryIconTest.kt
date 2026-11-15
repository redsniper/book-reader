package com.moyue.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 电池图标填充比例的纯逻辑测试。
 *
 * Canvas 的绘制像素由 Compose 测量，不在 JVM 测试里断言；这里只锁定
 * “百分比 → 填充比例”的契约：0% 不填充、100% 填满、非法值不溢出。
 */
class ReaderBatteryIconTest {

    @Test
    fun `fill fraction is proportional to percent`() {
        assertEquals(0f, batteryFillFraction(0), 0.0001f)
        assertEquals(0.47f, batteryFillFraction(47), 0.0001f)
        assertEquals(0.5f, batteryFillFraction(50), 0.0001f)
        assertEquals(1f, batteryFillFraction(100), 0.0001f)
    }

    @Test
    fun `fill fraction clamps out of range values`() {
        // 正常情况下 provider 已保证 0..100；这里防御未来调用方传入脏值，
        // 避免填充色块画到电池轮廓之外。
        assertEquals(0f, batteryFillFraction(-5), 0.0001f)
        assertEquals(1f, batteryFillFraction(120), 0.0001f)
    }
}
