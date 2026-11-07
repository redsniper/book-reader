package com.moyue.reader.domain.parser

import com.moyue.reader.domain.model.BookFormat
import java.io.File

/** 解析输入。source 已复制到应用私有临时目录，解析器只读该文件。 */
data class ParseRequest(
    val source: File,
    val originalName: String,
    val format: BookFormat,
)

/** 解析产出的章节（尚未写入数据库，字符偏移由导入服务计算）。 */
data class ParsedChapter(
    val title: String,
    val text: String,
    val pdfPageIndex: Int? = null,
)

/** 解析器统一产出契约。coverBytes 由导入服务写入私有目录。 */
data class ParsedBook(
    val title: String?,
    val author: String?,
    val format: BookFormat,
    val isScannedPdf: Boolean = false,
    val coverBytes: ByteArray? = null,
    val chapters: List<ParsedChapter>,
)

/**
 * 电子书解析策略。新增格式时实现此接口并注册到 [BookParserFactory]。
 *
 * 契约：
 * 1. 返回的章节按阅读顺序排列且至少一章；
 * 2. 标题不得为空白；
 * 3. 扫描版 PDF 的章节 text 为空并带 pdfPageIndex；
 * 4. 无法解析时抛出 [BookParseException] 的子类，不返回半成品。
 */
interface BookParser {
    fun supports(format: BookFormat): Boolean

    suspend fun parse(request: ParseRequest): ParsedBook
}

/** 解析器工厂：按格式选择策略，导入服务不感知具体实现。 */
class BookParserFactory(parsers: List<BookParser>) {
    private val parsers: List<BookParser> = parsers.toList()

    fun parserFor(format: BookFormat): BookParser =
        parsers.firstOrNull { it.supports(format) }
            ?: throw BookParseException.UnsupportedFormat(format)

    /** 供测试/诊断使用，返回已注册的格式。 */
    fun supportedFormats(): Set<BookFormat> =
        parsers.flatMap { parser -> BookFormat.entries.filter(parser::supports) }.toSet()
}

/** 解析失败的领域异常集合。UI 将类型映射为本地化文案。 */
sealed class BookParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class UnsupportedFormat(val format: BookFormat) :
        BookParseException("Unsupported format: ${format.extension}")

    class Corrupted(detail: String = "文件损坏或格式不正确", cause: Throwable? = null) :
        BookParseException(detail, cause)

    class Encrypted(detail: String = "电子书受加密/DRM 保护") :
        BookParseException(detail)

    class TooLarge(val limitBytes: Long) :
        BookParseException("File exceeds limit: $limitBytes")

    class NoReadableText(detail: String = "未找到可读文字") :
        BookParseException(detail)

    class Io(cause: Throwable) :
        BookParseException("读取文件失败", cause)
}
