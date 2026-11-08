package com.moyue.reader.domain.service

import com.moyue.reader.core.Clock
import com.moyue.reader.core.DiagnosticLogger
import com.moyue.reader.core.DispatcherProvider
import com.moyue.reader.data.file.BookFileStore
import com.moyue.reader.data.file.FileTooLargeException
import com.moyue.reader.domain.model.Book
import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.ReadingProgress
import com.moyue.reader.domain.parser.BookParseException
import com.moyue.reader.domain.parser.BookParserFactory
import com.moyue.reader.domain.parser.ParseRequest
import com.moyue.reader.domain.parser.ParsedBook
import com.moyue.reader.domain.repository.ShelfRepository
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** 一次导入请求。openStream 由 UI 层从 SAF Uri 打开，领域层不依赖 Android。 */
data class ImportRequest(
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val openStream: () -> InputStream,
)

sealed interface ImportResult {
    data class Imported(val bookId: String, val title: String) : ImportResult
    data class Duplicate(val existingTitle: String) : ImportResult
    data class Failed(val error: ImportError, val detail: String? = null) : ImportResult
}

sealed interface ImportError {
    data object UnsupportedFormat : ImportError
    data class FileTooLarge(val limitBytes: Long) : ImportError
    data object Encrypted : ImportError
    data object Corrupted : ImportError
    data object NoReadableText : ImportError
    data object Io : ImportError
    data object Unknown : ImportError
}

/**
 * 书籍导入用例。
 *
 * 完整流水线：
 * 1. 识别格式、校验大小；
 * 2. 复制到暂存目录并计算 SHA-256；
 * 3. 查重（含隐藏书架）；
 * 4. 调用对应 BookParser 解析；
 * 5. 写入封面、提交目录、事务落库；
 * 6. 任意步骤失败均清理暂存/已提交文件，杜绝脏数据。
 */
class BookImportService(
    private val repository: ShelfRepository,
    private val fileStore: BookFileStore,
    private val parserFactory: BookParserFactory,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
    /** 诊断日志：只记录格式/大小/耗时/结果枚举，不记录书名、路径或正文。 */
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.None,
) {

    suspend fun import(request: ImportRequest): ImportResult = withContext(dispatchers.io) {
        val startedAt = clock.nowMillis()
        val detectedFormat = BookFormat.fromFileName(sanitizeDisplayName(request.displayName))
            ?: BookFormat.fromMimeType(request.mimeType)
        diagnostics.log(
            "import.start",
            mapOf(
                "format" to (detectedFormat?.name ?: "unsupported"),
                "size" to (request.sizeBytes ?: -1),
            ),
        )
        val result = executeImport(request)
        val fields = mutableMapOf<String, Any?>(
            "format" to (detectedFormat?.name ?: "unsupported"),
            "size" to (request.sizeBytes ?: -1),
            "ms" to (clock.nowMillis() - startedAt),
        )
        when (result) {
            is ImportResult.Imported -> {
                fields["result"] = "imported"
                fields["book"] = result.bookId
            }

            is ImportResult.Duplicate -> fields["result"] = "duplicate"

            is ImportResult.Failed -> {
                fields["result"] = "failed"
                fields["error"] = result.error::class.simpleName ?: "Unknown"
            }
        }
        diagnostics.log("import.done", fields)
        result
    }

    private suspend fun executeImport(request: ImportRequest): ImportResult = withContext(dispatchers.io) {
        val displayName = sanitizeDisplayName(request.displayName)
        val format = BookFormat.fromFileName(displayName)
            ?: BookFormat.fromMimeType(request.mimeType)
            ?: return@withContext ImportResult.Failed(ImportError.UnsupportedFormat)

        if (request.sizeBytes != null && request.sizeBytes > format.sizeLimitBytes) {
            return@withContext ImportResult.Failed(ImportError.FileTooLarge(format.sizeLimitBytes))
        }

        val bookId = fileStore.newBookId()
        var committed = false
        var saved = false
        try {
            val staged = try {
                fileStore.stageSource(
                    bookId = bookId,
                    extension = format.extension,
                    maxBytes = format.sizeLimitBytes,
                    openStream = request.openStream,
                )
            } catch (e: FileTooLargeException) {
                return@withContext ImportResult.Failed(ImportError.FileTooLarge(e.limitBytes))
            } catch (e: IOException) {
                return@withContext ImportResult.Failed(ImportError.Io, e.message)
            } catch (e: SecurityException) {
                return@withContext ImportResult.Failed(ImportError.Io, e.message)
            }

            repository.findBookByHash(staged.sha256)?.let { existing ->
                return@withContext ImportResult.Duplicate(existing.title)
            }

            val parsed = try {
                parserFactory.parserFor(format)
                    .parse(ParseRequest(staged.tempFile, displayName, format))
            } catch (e: BookParseException) {
                return@withContext ImportResult.Failed(e.toImportError(), e.message)
            } catch (e: IOException) {
                return@withContext ImportResult.Failed(ImportError.Io, e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext ImportResult.Failed(ImportError.Corrupted, e.message)
            }

            val chapters = buildChapters(parsed)
            if (chapters.isEmpty() || (!parsed.isScannedPdf && chapters.sumOf { it.charCount } <= 0)) {
                return@withContext ImportResult.Failed(ImportError.NoReadableText)
            }

            val coverPath = fileStore.stageCover(bookId, parsed.coverBytes)
            val now = clock.nowMillis()
            val title = deriveTitle(displayName).ifBlank {
                parsed.title?.trim().orEmpty().ifBlank { "未命名书籍" }
            }
            val book = Book(
                id = bookId,
                title = title.take(200),
                author = parsed.author?.trim()?.takeIf { it.isNotEmpty() } ?: inferAuthor(displayName),
                format = format,
                sourcePath = staged.relativePath,
                contentHash = staged.sha256,
                coverPath = coverPath,
                fileSize = staged.sizeBytes,
                hidden = false,
                chapterCount = chapters.size,
                totalChars = chapters.sumOf { it.charCount },
                isScannedPdf = parsed.isScannedPdf,
                importedAt = now,
                lastOpenedAt = null,
            )

            fileStore.commit(bookId)
            committed = true

            try {
                repository.saveImportedBook(
                    book = book,
                    chapters = chapters,
                    initialProgress = ReadingProgress.initial(now),
                )
                saved = true
            } catch (e: Exception) {
                return@withContext ImportResult.Failed(ImportError.Io, e.message)
            }

            diagnostics.log(
                "import.book",
                mapOf(
                    "book" to bookId,
                    "format" to format.name,
                    "chapters" to chapters.size,
                    "chars" to book.totalChars,
                    "bytes" to book.fileSize,
                    "scanned" to parsed.isScannedPdf,
                ),
            )
            ImportResult.Imported(bookId = bookId, title = book.title)
        } finally {
            if (!saved) {
                if (committed) {
                    runCatching { fileStore.deleteBookFiles(bookId) }
                } else {
                    runCatching { fileStore.discard(bookId) }
                }
            }
        }
    }

    /**
     * 把解析结果转换为领域章节并计算累计字符偏移。
     *
     * 文本格式过滤空白章节；扫描版 PDF 保留每一页（text 为空，pdfPageIndex 有效）。
     */
    private fun buildChapters(parsed: ParsedBook): List<Chapter> {
        val sourceChapters = if (parsed.isScannedPdf) {
            parsed.chapters.mapIndexed { index, chapter ->
                Triple(
                    chapter.title.ifBlank { "第 ${ (chapter.pdfPageIndex ?: index) + 1 } 页" },
                    "",
                    chapter.pdfPageIndex ?: index,
                )
            }
        } else {
            parsed.chapters.mapNotNull { chapter ->
                val text = chapter.text.trim()
                if (text.isEmpty()) {
                    null
                } else {
                    Triple(chapter.title.trim(), text, chapter.pdfPageIndex)
                }
            }
        }

        var charCursor = 0
        return sourceChapters.mapIndexed { index, (rawTitle, text, pdfPageIndex) ->
            val title = rawTitle.ifBlank { if (parsed.isScannedPdf) "第 ${index + 1} 页" else "第 ${index + 1} 章" }
            val chapter = Chapter(
                index = index,
                title = title.take(120),
                charStart = charCursor,
                charCount = text.length,
                text = text,
                pdfPageIndex = pdfPageIndex,
            )
            charCursor += text.length
            chapter
        }
    }

    private fun sanitizeDisplayName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "unknown" }

    /** 去掉扩展名、书名号与"作者：xxx"后缀，作为书架的显示书名。 */
    private fun deriveTitle(displayName: String): String {
        val raw = displayName.substringBeforeLast('.').trim()
        val withoutAuthor = raw.replace(AUTHOR_SUFFIX_PATTERN, "").trim()
        val withoutBrackets = withoutAuthor.removeSurrounding("《", "》").trim()
        return withoutBrackets.ifBlank { raw }
    }

    private fun inferAuthor(displayName: String): String? {
        val name = displayName.substringBeforeLast('.')
        AUTHOR_PATTERN.find(name)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it.take(80) }
        return null
    }

    private fun BookParseException.toImportError(): ImportError = when (this) {
        is BookParseException.Encrypted -> ImportError.Encrypted
        is BookParseException.NoReadableText -> ImportError.NoReadableText
        is BookParseException.TooLarge -> ImportError.FileTooLarge(limitBytes)
        is BookParseException.Corrupted -> ImportError.Corrupted
        is BookParseException.Io -> ImportError.Io
        is BookParseException.UnsupportedFormat -> ImportError.UnsupportedFormat
    }

    companion object {
        private val AUTHOR_PATTERN = Regex("作者[：:]\\s*([^\\s（(\\[\\]]{1,80})")
        private val AUTHOR_SUFFIX_PATTERN = Regex("\\s*作者[：:].*$")
    }
}
