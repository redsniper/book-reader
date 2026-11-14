package com.moyue.reader.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 一次“用其他应用打开”请求。
 *
 * 只保存 Uri 字符串与发送方给的 MIME 提示，刻意不引用 `android.net.Uri`：
 * 队列与消费逻辑因此可以放在纯 Kotlin 的 core 层并被 JVM 单元测试覆盖；
 * 真正的权限校验、文件名/大小读取由 UI 层的 `DocumentResolver` 完成。
 */
data class ExternalOpenRequest(
    val uri: String,
    val mimeHint: String? = null,
)

/**
 * 外部打开请求的进程内队列。
 *
 * 为什么需要队列而不是让 Activity 直接调用导入：
 * - `ACTION_VIEW` 可能在任何页面（书架/设置/阅读器）到达，Activity 没有、也不该有
 *   导入服务与导航的上下文；
 * - 队列让根级 ViewModel 成为唯一消费者，串行导入（避免两个大文件并发解析），
 *   且在页面导航、配置变更期间不丢请求。
 *
 * 生命周期与安全边界：
 * - 仅存在进程内存（`Channel`），不写 Room / DataStore / SavedState，界面状态无法被持久化绕过；
 * - 进程被杀后未消费的请求直接消失，但原始文件仍在，用户可再次“打开”；
 * - `trySend` 不阻塞主线程；队列无上限但每次打开只产生一条，实际不会堆积。
 */
class ExternalOpenQueue {

    private val channel = Channel<ExternalOpenRequest>(Channel.UNLIMITED)

    /** Activity 侧调用；不挂起、不抛异常，避免系统回调里做重活。 */
    fun submit(request: ExternalOpenRequest) {
        channel.trySend(request)
    }

    /** ViewModel 侧消费；单消费者语义，重复收集会分摊请求。 */
    fun requests(): Flow<ExternalOpenRequest> = channel.receiveAsFlow()
}
