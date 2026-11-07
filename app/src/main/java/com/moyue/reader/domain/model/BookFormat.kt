package com.moyue.reader.domain.model

import kotlin.math.roundToInt

/** 支持的电子书格式。扩展名与常见 MIME 用于导入识别。 */
enum class BookFormat(
    val extension: String,
    val mimeTypes: Set<String>,
    val sizeLimitBytes: Long,
) {
    TXT(
        extension = "txt",
        mimeTypes = setOf("text/plain", "application/txt"),
        sizeLimitBytes = 40L * 1024 * 1024,
    ),
    EPUB(
        extension = "epub",
        mimeTypes = setOf("application/epub+zip", "application/zip"),
        sizeLimitBytes = 40L * 1024 * 1024,
    ),
    PDF(
        extension = "pdf",
        mimeTypes = setOf("application/pdf"),
        sizeLimitBytes = 300L * 1024 * 1024,
    ),
    ;

    companion object {
        /** 以文件扩展名识别格式，大小写不敏感。 */
        fun fromFileName(fileName: String): BookFormat? {
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            return entries.firstOrNull { it.extension.equals(extension, ignoreCase = true) }
        }

        /** 扩展名缺失时以 MIME 兜底识别。 */
        fun fromMimeType(mimeType: String?): BookFormat? {
            if (mimeType.isNullOrBlank()) return null
            return entries.firstOrNull { format -> format.mimeTypes.any { it.equals(mimeType, true) } }
        }
    }
}
