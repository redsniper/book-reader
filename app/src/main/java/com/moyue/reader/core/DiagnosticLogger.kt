package com.moyue.reader.core

import java.io.File

/**
 * 诊断日志端口。
 *
 * 只记录排障必需的关键事件（设备信息、导入结果、分页度量、阅读进度），
 * **禁止**写入书籍正文、书名、文件路径、密码或任何用户隐私数据。
 *
 * 实现必须满足：
 * - 异步写入，不阻塞 UI/数据库/分页线程；
 * - 容量有上限（滚动覆盖），避免长期运行写满磁盘；
 * - 单条日志可包含任意键值，但值会被转义为单行；
 * - [logFiles] 返回可直接读取的日志文件（最新在前），供导出功能使用。
 */
interface DiagnosticLogger {
    /**
     * 记录一条关键事件。
     *
     * @param minIntervalMs 同一 [event] 的最小记录间隔；用于滑块拖动等高频事件，
     *   0 表示不限制。被节流的事件不会写入磁盘。
     */
    fun log(event: String, fields: Map<String, Any?> = emptyMap(), minIntervalMs: Long = 0)

    /** 请求尽快把队列中的日志刷入磁盘（不阻塞调用线程）。 */
    fun flush()

    /**
     * 等待队列清空并刷盘，供导出/测试使用。
     *
     * 默认实现退化为 [flush]；文件实现会最多等待 [timeoutMs]，
     * 调用方应确保不在主线程调用。
     */
    fun flushBlocking(timeoutMs: Long = 500) {
        flush()
    }

    /** 当前可导出的日志文件，最新在前；没有日志时返回空列表。 */
    fun logFiles(): List<File>

    /** 日志目录（用于设置页展示/adb 拉取）；实现可选择不暴露。 */
    fun logDirectoryPath(): String? = null

    companion object {
        /** 单元测试与不关心日志的场景使用的空实现。 */
        val None: DiagnosticLogger = object : DiagnosticLogger {
            override fun log(event: String, fields: Map<String, Any?>, minIntervalMs: Long) = Unit
            override fun flush() = Unit
            override fun flushBlocking(timeoutMs: Long) = Unit
            override fun logFiles(): List<File> = emptyList()
            override fun logDirectoryPath(): String? = null
        }
    }
}
