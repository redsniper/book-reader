package com.moyue.reader.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下拉手势状态机测试。
 *
 * 重点锁住四件事：
 * 1. 只有“书架顶部 + 向下拖动”才累计位移；
 * 2. 阈值 = 整窗高度 1/2，越过阈值只进入 ARMED，必须松手才触发，且同一手势只触发一次；
 * 3. 视觉位移按橡皮筋曲线衰减、单调递增且有上限；
 * 4. 阈值处的跟手位移足够露出顶部提示层，且远小于半屏的手指行程。
 */
class PullDownUnlockDetectorTest {

    @Test
    fun `accumulates at top and arms at threshold without triggering`() {
        val detector = PullDownUnlockDetector(thresholdPx = 100f, maxVisualOffsetPx = 200f)

        assertTrue(detector.onDrag(60f, isAtTop = true))
        assertEquals(PullDownUnlockDetector.Phase.PULLING, detector.phase)
        assertEquals(0.6f, detector.progress, 0.001f)

        assertTrue(detector.onDrag(40f, isAtTop = true))
        assertEquals(PullDownUnlockDetector.Phase.ARMED, detector.phase)
        assertTrue(detector.isArmed)
        assertEquals(1f, detector.progress, 0.001f)
    }

    @Test
    fun `release triggers exactly once and resets`() {
        val detector = PullDownUnlockDetector(thresholdPx = 100f, maxVisualOffsetPx = 200f)
        detector.onDrag(120f, isAtTop = true)

        assertTrue("越过阈值后松手应触发", detector.onRelease())
        assertEquals(PullDownUnlockDetector.Phase.IDLE, detector.phase)
        assertEquals(0f, detector.accumulatedPx, 0.001f)
        assertFalse("同一次手势不能重复触发", detector.onRelease())
    }

    @Test
    fun `release below threshold does not trigger`() {
        val detector = PullDownUnlockDetector(thresholdPx = 100f, maxVisualOffsetPx = 200f)
        detector.onDrag(99f, isAtTop = true)

        assertFalse(detector.onRelease())
        assertEquals(PullDownUnlockDetector.Phase.IDLE, detector.phase)
        assertEquals(0f, detector.progress, 0.001f)
    }

    @Test
    fun `continues to follow after armed without losing state`() {
        val detector = PullDownUnlockDetector(thresholdPx = 100f, maxVisualOffsetPx = 200f)
        detector.onDrag(100f, isAtTop = true)
        val armedOffset = detector.visualOffsetPx

        assertTrue(detector.onDrag(100f, isAtTop = true))
        assertEquals(PullDownUnlockDetector.Phase.ARMED, detector.phase)
        assertEquals(1f, detector.progress, 0.001f)
        assertTrue("越过阈值后仍应继续跟手", detector.visualOffsetPx > armedOffset)
        assertTrue(detector.onRelease())
    }

    @Test
    fun `not at top resets progress`() {
        val detector = PullDownUnlockDetector(thresholdPx = 100f, maxVisualOffsetPx = 200f)
        detector.onDrag(80f, isAtTop = true)

        assertFalse(detector.onDrag(80f, isAtTop = false))
        assertEquals(0f, detector.accumulatedPx, 0.001f)
        assertEquals(PullDownUnlockDetector.Phase.IDLE, detector.phase)
    }

    @Test
    fun `upward drag resets progress`() {
        val detector = PullDownUnlockDetector(thresholdPx = 100f, maxVisualOffsetPx = 200f)
        detector.onDrag(80f, isAtTop = true)

        assertFalse(detector.onDrag(-20f, isAtTop = true))
        assertEquals(0f, detector.accumulatedPx, 0.001f)
        assertEquals(PullDownUnlockDetector.Phase.IDLE, detector.phase)
    }

    @Test
    fun `progress is clamped to one`() {
        val detector = PullDownUnlockDetector(thresholdPx = 100f, maxVisualOffsetPx = 200f)
        detector.onDrag(500f, isAtTop = true)

        assertEquals(1f, detector.progress, 0.001f)
        assertTrue(detector.isArmed)
    }

    @Test
    fun `visual offset is damped monotonic and bounded`() {
        val detector = PullDownUnlockDetector(thresholdPx = 100f, maxVisualOffsetPx = 200f)
        val offsets = mutableListOf<Float>()
        repeat(10) {
            detector.onDrag(20f, isAtTop = true)
            offsets += detector.visualOffsetPx
        }

        assertTrue("首段位移应小于手指位移", offsets.first() < 20f)
        assertEquals(
            "累计 200px 原始位移应映射到半程渐近线",
            100f,
            detector.visualOffsetPx,
            0.01f,
        )
        assertTrue("位移必须单调递增", offsets.zipWithNext().all { (a, b) -> b > a })
        assertTrue("位移不得超过渐近线", offsets.last() < 200f)
    }

    @Test
    fun `threshold is half of window height`() {
        assertEquals(1f / 2f, PullDownUnlockDetector.THRESHOLD_SCREEN_RATIO, 0.0001f)
        assertEquals(
            "1080x2400 设备阈值应为 1200px",
            1200f,
            PullDownUnlockDetector.thresholdPxForWindowHeight(2400),
            0.01f,
        )
        assertEquals(
            "无效高度必须退化为 0，交给调用方兜底",
            0f,
            PullDownUnlockDetector.thresholdPxForWindowHeight(0),
            0.01f,
        )
    }

    @Test
    fun `arms only after half screen and offset at threshold clears hint`() {
        // 1080x2400 / 420dpi：阈值 1200px，位移上限 120dp = 315px。
        val detector = PullDownUnlockDetector(
            thresholdPx = PullDownUnlockDetector.thresholdPxForWindowHeight(2400),
            maxVisualOffsetPx = 315f,
        )

        detector.onDrag(1199f, isAtTop = true)
        assertEquals(PullDownUnlockDetector.Phase.PULLING, detector.phase)

        detector.onDrag(1f, isAtTop = true)
        assertEquals(PullDownUnlockDetector.Phase.ARMED, detector.phase)
        assertEquals(1f, detector.progress, 0.001f)

        // 阈值处位移约 95dp（249.5px），足够露出 74dp、2.0 倍字体下约 90dp 的提示层。
        assertEquals(249.5f, detector.visualOffsetPx, 0.5f)
        assertTrue("提示层需要至少 90dp 的露出高度", detector.visualOffsetPx / 2.625f > 90f)
        assertTrue("跟手位移必须明显小于手指行程", detector.visualOffsetPx < 1200f / 3f)
    }

    @Test
    fun `reset returns to idle from any phase`() {
        val detector = PullDownUnlockDetector(thresholdPx = 100f, maxVisualOffsetPx = 200f)
        detector.onDrag(150f, isAtTop = true)
        assertTrue(detector.isArmed)

        detector.reset()

        assertEquals(PullDownUnlockDetector.Phase.IDLE, detector.phase)
        assertEquals(0f, detector.progress, 0.001f)
        assertEquals(0f, detector.visualOffsetPx, 0.001f)
    }
}
