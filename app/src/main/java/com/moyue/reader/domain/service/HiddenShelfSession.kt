package com.moyue.reader.domain.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 隐藏书架解锁会话。
 *
 * 会话只存在于进程内存中，不写入数据库/DataStore/SavedState：
 * - 配置变更（旋转、分屏尺寸变化）时 Activity 重建但进程仍在，会话保持；
 * - 应用进入后台（Home、最近任务、锁屏）时立即锁定，避免返回时直接暴露隐藏内容；
 * - 进程被杀后重新启动，会话自动失效，必须重新输入密码。
 *
 * 以 [StateFlow] 暴露锁定事件：阅读器与书架 ViewModel 在后台锁定后必须立刻
 * 丢弃隐藏内容状态，不能等页面重新可见才检查，否则后台任务可能在锁定后
 * 完成排版并把正文重新发布到 UI。
 *
 * 这是防止通过后台恢复或导航状态恢复绕过密码的关键措施之一。
 */
class HiddenShelfSession {
    private val _unlocked = MutableStateFlow(false)

    /** 会话是否解锁；收集该 Flow 可及时响应后台锁定。 */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    val isUnlocked: Boolean get() = _unlocked.value

    fun unlock() {
        _unlocked.value = true
    }

    fun lock() {
        _unlocked.value = false
    }
}
