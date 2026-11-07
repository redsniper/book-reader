package com.moyue.reader.domain.service

/**
 * 隐藏书架解锁会话。
 *
 * 只存在于进程内存中，不写入数据库/DataStore/SavedState：
 * - 配置变更（旋转）时 Activity 重建但进程仍在，会话保持；
 * - 进程被杀后重新启动，会话自动失效，必须重新输入密码。
 *
 * 这是防止通过导航状态恢复绕过密码的关键措施之一。
 */
class HiddenShelfSession {
    @Volatile
    private var unlocked: Boolean = false

    val isUnlocked: Boolean get() = unlocked

    fun unlock() {
        unlocked = true
    }

    fun lock() {
        unlocked = false
    }
}
