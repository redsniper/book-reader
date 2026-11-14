package com.moyue.reader.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.moyue.reader.domain.service.ImportRequest
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 把 SAF Uri 解析成领域导入请求；UI 层负责 Android API，ViewModel 只依赖此接口。 */
interface DocumentResolver {
    suspend fun resolve(uri: Uri): ImportRequest
}

/**
 * 通过 ContentResolver 读取文件名、大小与 MIME。
 *
 * SAF 返回的 Uri 不保证有文件名/大小，缺失时用路径与 null 兜底；
 * 真正的读取仍由 openStream 在导入服务中完成。
 */
class AndroidDocumentResolver(
    private val context: Context,
) : DocumentResolver {

    override suspend fun resolve(uri: Uri): ImportRequest = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        val raw = cursor.getLong(sizeIndex)
                        if (raw >= 0) size = raw
                    }
                }
            }
        }

        val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "unknown"
        ImportRequest(
            displayName = displayName?.takeIf { it.isNotBlank() } ?: fallbackName,
            mimeType = mimeType,
            sizeBytes = size,
            openStream = {
                resolver.openInputStream(uri)
                    ?: throw IOException("无法打开文件：$uri")
            },
        )
    }
}
