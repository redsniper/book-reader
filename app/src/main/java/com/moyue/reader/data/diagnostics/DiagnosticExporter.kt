package com.moyue.reader.data.diagnostics

import android.content.ContentResolver
import android.net.Uri
import com.moyue.reader.core.DiagnosticLogger
import com.moyue.reader.core.DispatcherProvider
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 把诊断日志导出到用户通过 SAF 选择的位置（通常是“下载”目录）。
 *
 * 只做“拼接 + 写出”，不修改日志内容；导出前后各记一条元数据日志。
 * 失败时返回 false，由 UI 提示，不抛出到调用方。
 */
class DiagnosticExporter(
    private val logger: DiagnosticLogger,
    private val dispatchers: DispatcherProvider,
) {

    suspend fun export(resolver: ContentResolver, uri: Uri): Boolean = withContext(dispatchers.io) {
        try {
            // 先等后台写入线程把队列刷完，避免导出的文件缺最后几行。
            logger.flushBlocking(1_000)
            val files = logger.logFiles().sortedBy { it.lastModified() }
            if (files.isEmpty()) return@withContext false

            resolver.openOutputStream(uri)?.use { output ->
                output.write(
                    "# MoYue diagnostics export at ${
                        EXPORT_TIME.format(Instant.now().atZone(ZoneId.systemDefault()))
                    } files=${files.size}\n".toByteArray(Charsets.UTF_8),
                )
                files.forEach { file ->
                    file.inputStream().use { input -> input.copyTo(output) }
                    output.write("\n".toByteArray(Charsets.UTF_8))
                }
                output.flush()
            } ?: return@withContext false

            logger.log("diagnostics.export", mapOf("files" to files.size))
            true
        } catch (e: Exception) {
            // 异常信息可能包含用户选择的路径，只记录类型。
            logger.log(
                "diagnostics.export_error",
                mapOf("error" to (e::class.simpleName ?: "unknown")),
            )
            false
        }
    }

    private companion object {
        val EXPORT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}
