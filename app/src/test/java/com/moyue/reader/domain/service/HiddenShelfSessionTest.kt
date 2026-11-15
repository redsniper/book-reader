package com.moyue.reader.domain.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 隐藏会话只保存进程内布尔状态，但必须能同步通知订阅者锁定事件。 */
class HiddenShelfSessionTest {

    @Test
    fun `starts locked and exposes unlock updates`() {
        val session = HiddenShelfSession()
        assertFalse(session.isUnlocked)
        assertFalse(session.unlocked.value)

        session.unlock()
        assertTrue(session.isUnlocked)
        assertTrue(session.unlocked.value)
    }

    @Test
    fun `lock revokes a previously unlocked session`() {
        val session = HiddenShelfSession()
        session.unlock()
        session.lock()

        assertFalse(session.isUnlocked)
        assertFalse(session.unlocked.value)
    }
}
