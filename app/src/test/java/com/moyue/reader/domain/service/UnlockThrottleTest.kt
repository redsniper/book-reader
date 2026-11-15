package com.moyue.reader.domain.service

import com.moyue.reader.MutableClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockThrottleTest {

    @Test
    fun `blocks after five failures for thirty seconds`() {
        val clock = MutableClock(0)
        val throttle = UnlockThrottle(clock = clock)

        repeat(4) { assertFalse(throttle.isBlocked()); throttle.recordFailure() }
        assertFalse(throttle.isBlocked())
        throttle.recordFailure()
        assertTrue(throttle.isBlocked())
        assertEquals(30_000L, throttle.remainingCooldownMillis())

        clock.now += 10_000
        assertEquals(20_000L, throttle.remainingCooldownMillis())
        assertTrue(throttle.isBlocked())

        clock.now += 20_001
        assertFalse(throttle.isBlocked())
    }

    @Test
    fun `success clears failures`() {
        val clock = MutableClock(0)
        val throttle = UnlockThrottle(clock = clock)
        repeat(4) { throttle.recordFailure() }
        throttle.recordSuccess()
        repeat(4) { throttle.recordFailure() }
        assertFalse(throttle.isBlocked())
    }

    @Test
    fun `failure during cooldown does not extend it`() {
        val clock = MutableClock(0)
        val throttle = UnlockThrottle(clock = clock)
        repeat(5) { throttle.recordFailure() }
        val remaining = throttle.remainingCooldownMillis()
        clock.now += 1_000
        throttle.recordFailure()
        assertEquals(remaining - 1_000, throttle.remainingCooldownMillis())
    }
}
