package com.moyue.reader.data.diagnostics

import com.moyue.reader.core.Clock
import com.moyue.reader.core.DiagnosticLogger
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 基于文件的诊断日志。
 *
 * 设计取舍：
 * - **异步单线程写入**：日志来自 UI、分页协程、导入线程，统一排队到一条后台线程，
 *   不阻塞任何业务线程；队列满时直接丢弃并计数，宁可少记也不拖慢阅读。
 * - **单行结构化**：`时间 级别 事件 key=value`，方便 grep/附件反馈；
 *   所有值转义为单行并截断，避免异常堆栈把文件撑大。
 * - **滚动覆盖**：单文件默认 256 KB，最多保留 2 个文件（约 512 KB）；
 *   写入后立即 flush，进程被杀时最多丢队列里的最后几条。
 * - **隐私红线**：调用方只传设备/排版/分页/导入结果等元数据；本类不做业务判断，
 *   但会拒绝把换行注入到日志行中。
 *
 * 目录不可写时自动降级为静默（不影响阅读），不抛异常到业务层。
 */
class FileDiagnosticLogger(
    private val directory: File,
    private val clock: Clock,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val sessionId: String = java.util.UUID.randomUUID().toString().take(8),
) : DiagnosticLogger {

    private val lock = Any()
    private val lastLogAt = ConcurrentHashMap<String, Long>()
    private val dropped = AtomicInteger(0)

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var enabled = true

    @Volatile
    private var sessionHeaderWritten = false

    @Volatile
    private var closed = false

    /**
     * 单线程执行器：队列满时丢弃最迟的日志并计数。
     *
     * 使用有界队列而不是无界队列，避免极端情况下（如分页抖动刷屏）
     * 把内存和磁盘一起拖垮。
     */
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "moyue-diagnostics").apply { isDaemon = true }
        },
        RejectedExecutionHandler { _, _ -> dropped.incrementAndGet() },
    )

    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    private val currentFile: File
        get() = File(directory, CURRENT_FILE_NAME)

    override fun log(event: String, fields: Map<String, Any?>, minIntervalMs: Long) {
        if (!enabled || closed) return
        val now = clock.nowMillis()
        if (minIntervalMs > 0) {
            val last = lastLogAt[event]
            if (last != null && now - last < minIntervalMs) return
            lastLogAt[event] = now
        }
        val line = buildLine(now, event, fields)
        runCatching {
            executor.execute { writeLine(line) }
        }.onFailure { enabled = false }
    }

    override fun flush() {
        if (!enabled || closed) return
        runCatching { executor.execute { flushLocked() } }
    }

    override fun flushBlocking(timeoutMs: Long) {
        if (!enabled || closed) return
        // 先排空队列，再刷 writer；导出/测试需要在同一时刻拿到完整文件。
        val task = runCatching {
            executor.submit {
                flushLocked()
            }
        }.getOrNull() ?: return
        runCatching { task.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    override fun logDirectoryPath(): String? = directory.absolutePath

    override fun logFiles(): List<File> = synchronized(lock) {
        val files = ArrayList<File>(maxFiles)
        files.add(currentFile)
        for (index in 1 until maxFiles) {
            files.add(File(directory, "$CURRENT_FILE_NAME.$index"))
        }
        files.filter { it.isFile && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
    }

    /** 仅测试与进程收尾使用：关闭 writer 与线程池。 */
    fun close() {
        closed = true
        runCatching { executor.shutdown() }
        runCatching { executor.awaitTermination(300, TimeUnit.MILLISECONDS) }
        synchronized(lock) {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
        }
    }

    // ---------------------------------------------------------------------
    // 写入与滚动
    // ---------------------------------------------------------------------

    private fun writeLine(line: String) {
        if (!enabled) return
        synchronized(lock) {
            try {
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                    enabled = false
                    return
                }
                val out = ensureWriter() ?: return
                writeSessionHeaderIfNeeded(out)
                if (currentFile.length() + line.toByteArray(StandardCharsets.UTF_8).size > maxBytes) {
                    rotate()
                }
                val target = writer ?: return
                target.write(line)
                target.newLine()
                target.flush()
            } catch (_: Exception) {
                // 磁盘满/权限变化时静默关闭，绝不影响阅读主流程。
                enabled = false
                runCatching { writer?.close() }
                writer = null
            }
        }
    }

    private fun ensureWriter(): BufferedWriter? {
        writer?.let { return it }
        val file = currentFile
        val stream = FileOutputStream(file, true)
        return BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8)).also {
            writer = it
        }
    }

    private fun writeSessionHeaderIfNeeded(out: BufferedWriter) {
        if (sessionHeaderWritten) return
        sessionHeaderWritten = true
        val line = buildLine(
            clock.nowMillis(),
            "diagnostics.session",
            mapOf("id" to sessionId, "file" to CURRENT_FILE_NAME),
        )
        out.write("# $line")
        out.newLine()
        out.flush()
    }

    private fun rotate() {
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = null
        // 标准滚动：删除最旧文件，从旧到新依次后移，最后把 current 改为 .1。
        runCatching { File(directory, "$CURRENT_FILE_NAME.${maxFiles - 1}").delete() }
        for (index in maxFiles - 1 downTo 2) {
            runCatching {
                File(directory, "$CURRENT_FILE_NAME.${index - 1}")
                    .renameTo(File(directory, "$CURRENT_FILE_NAME.$index"))
            }
        }
        runCatching { currentFile.renameTo(File(directory, "$CURRENT_FILE_NAME.1")) }
        runCatching { currentFile.delete() }
        sessionHeaderWritten = false
        // 打开新文件并写一行滚动标记，方便确认日志被截断过。
        val fresh = ensureWriter() ?: return
        fresh.write(buildLine(clock.nowMillis(), "diagnostics.rotate", mapOf("max_bytes" to maxBytes)))
        fresh.newLine()
        fresh.flush()
    }

    private fun flushLocked() {
        synchronized(lock) {
            runCatching { writer?.flush() }
        }
    }

    // ---------------------------------------------------------------------
    // 格式化
    // ---------------------------------------------------------------------

    private fun buildLine(now: Long, event: String, fields: Map<String, Any?>): String {
        val timestamp = timeFormatter.format(
            Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()),
        )
        val builder = StringBuilder(96)
        builder.append(timestamp).append(' ').append(sanitizeToken(event, 48))
        fields.forEach { (key, value) ->
            val safeKey = sanitizeToken(key, 32)
            if (safeKey.isEmpty()) return@forEach
            builder.append(' ').append(safeKey).append('=')
            builder.append(formatValue(value))
        }
        return builder.toString()
    }

    private fun formatValue(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        else -> {
            val sanitized = value.toString()
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .take(MAX_VALUE_CHARS)
            "\"" + sanitized.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    /** 事件名/字段名只保留安全字符，避免日志行被伪造或撑爆。 */
    private fun sanitizeToken(raw: String, maxLength: Int): String =
        raw.take(maxLength).map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("")

    companion object {
        const val CURRENT_FILE_NAME = "moyue.log"
        const val DEFAULT_MAX_BYTES = 256L * 1024
        const val DEFAULT_MAX_FILES = 2
        private const val QUEUE_CAPACITY = 256
        private const val MAX_VALUE_CHARS = 160
    }
}
