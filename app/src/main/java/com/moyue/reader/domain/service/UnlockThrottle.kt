package com.moyue.reader.domain.service

import com.moyue.reader.core.Clock

/**
 * 隐藏书架解锁失败节流。
 *
 * 连续 5 次失败后冷却 30 秒。节流状态只保存在内存中，进程重启会重置；
 * 这符合"防误触/防肩窥"的轻量定位，真正的密码安全由 PBKDF2 慢哈希保证。
 */
class UnlockThrottle(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val clock: Clock,
) {
    private var consecutiveFailures: Int = 0
    private var blockedUntilMillis: Long = 0L

    fun isBlocked(): Boolean = remainingCooldownMillis() > 0

    fun remainingCooldownMillis(): Long {
        val remaining = blockedUntilMillis - clock.nowMillis()
        return if (remaining > 0) remaining else 0L
    }

    /** 记录一次失败；若触发冷却则返回剩余冷却毫秒数（可能为 0）。 */
    fun recordFailure(): Long {
        if (isBlocked()) return remainingCooldownMillis()
        consecutiveFailures += 1
        if (consecutiveFailures >= maxFailures) {
            consecutiveFailures = 0
            blockedUntilMillis = clock.nowMillis() + cooldownMillis
        }
        return remainingCooldownMillis()
    }

    fun recordSuccess() {
        consecutiveFailures = 0
        blockedUntilMillis = 0L
    }

    fun reset() {
        recordSuccess()
    }

    companion object {
        const val DEFAULT_MAX_FAILURES = 5
        const val DEFAULT_COOLDOWN_MILLIS = 30_000L
    }
}
