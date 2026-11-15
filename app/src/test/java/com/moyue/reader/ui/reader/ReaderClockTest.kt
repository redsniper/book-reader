package com.moyue.reader.ui.reader

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 左上角时间格式与刷新节奏的纯逻辑测试。
 *
 * 这里不渲染 Compose：时间文本 = 时区 + 时间戳的确定性映射，刷新节奏 = 距离整分钟的毫秒数；
 * 视觉位置、字体缩放与点击穿透由黑盒 BB-25 在设备上验收。
 */
class ReaderClockTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun epochAt(
        hour: Int,
        minute: Int,
        second: Int = 0,
        nanoOfSecond: Int = 0,
    ): Long = ZonedDateTime.of(2026, 11, 15, hour, minute, second, nanoOfSecond, zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun `formats 24 hour clock text`() {
        // 23:11 必须是 24 小时制；若误用 12 小时制会得到 11:11。
        assertEquals("23:11", formatClockTime(epochAt(23, 11), zone))
    }

    @Test
    fun `pads single digit hour and minute`() {
        // 固定两位时分，避免“9:5”这类宽度跳动。
        assertEquals("09:05", formatClockTime(epochAt(9, 5), zone))
    }

    @Test
    fun `formats midnight as 00 00`() {
        assertEquals("00:00", formatClockTime(epochAt(0, 0), zone))
    }

    @Test
    fun `delay aligns to next minute`() {
        assertEquals(60_000L, millisUntilNextMinute(epochAt(23, 11, 0, 0)))
        assertEquals(29_500L, millisUntilNextMinute(epochAt(23, 11, 30, 500_000_000)))
        assertEquals(1L, millisUntilNextMinute(epochAt(23, 11, 59, 999_000_000)))
    }

    @Test
    fun `delay is positive exactly on minute boundary`() {
        // 整分钟时如果返回 0，刷新循环会忙等待；必须等待完整一分钟。
        assertEquals(60_000L, millisUntilNextMinute(epochAt(12, 0, 0, 0)))
    }
}
