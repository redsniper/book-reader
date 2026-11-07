package com.moyue.reader.data.parser

import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.parser.BookParseException
import com.moyue.reader.domain.parser.BookParser
import com.moyue.reader.domain.parser.ParseRequest
import com.moyue.reader.domain.parser.ParsedBook
import com.moyue.reader.domain.parser.ParsedChapter
import com.moyue.reader.domain.parser.PdfTextExtractor
import com.moyue.reader.domain.service.ChapterSplitter
import com.moyue.reader.domain.service.TextNormalizer
import kotlin.math.max

/**
 * PDF 解析器。
 *
 * - 有文字层：提取全文并按与 TXT 相同的规则分章，文字参与重排/字体设置；
 * - 无文字层（扫描版）：判定后一章一页，text 为空，阅读器改用 PdfRenderer 显示原页。
 *
 * 判定阈值：全文有效字符 < max(50, 页数 × 10) 视为扫描版。
 */
class PdfParser(
    private val textExtractor: PdfTextExtractor,
    private val chapterSplitter: ChapterSplitter,
) : BookParser {

    override fun supports(format: BookFormat): Boolean = format == BookFormat.PDF

    override suspend fun parse(request: ParseRequest): ParsedBook {
        val extraction = textExtractor.extract(request.source)
        if (extraction.pageCount <= 0) {
            throw BookParseException.Corrupted("PDF 没有页面")
        }

        val pages = extraction.pageTexts
        val totalText = pages.joinToString("\n\n").trim()
        val threshold = max(MIN_TEXT_CHARS, extraction.pageCount * CHARS_PER_PAGE_THRESHOLD)
        if (totalText.length < threshold) {
            // 扫描版：一章一页，保留原始页序。
            val chapters = (0 until extraction.pageCount).map { pageIndex ->
                ParsedChapter(
                    title = "第 ${pageIndex + 1} 页",
                    text = "",
                    pdfPageIndex = pageIndex,
                )
            }
            return ParsedBook(
                title = extraction.title?.trim()?.takeIf { it.isNotEmpty() },
                author = extraction.author?.trim()?.takeIf { it.isNotEmpty() },
                format = BookFormat.PDF,
                isScannedPdf = true,
                chapters = chapters,
            )
        }

        val normalized = TextNormalizer.normalize(totalText)
        if (normalized.isBlank()) {
            throw BookParseException.NoReadableText("PDF 中未找到可读文字")
        }
        val chapters = chapterSplitter.split(normalized).map { split ->
            ParsedChapter(title = split.title, text = split.text)
        }
        if (chapters.isEmpty()) {
            throw BookParseException.NoReadableText("PDF 中未找到可读文字")
        }
        return ParsedBook(
            title = extraction.title?.trim()?.takeIf { it.isNotEmpty() },
            author = extraction.author?.trim()?.takeIf { it.isNotEmpty() },
            format = BookFormat.PDF,
            isScannedPdf = false,
            chapters = chapters,
        )
    }

    companion object {
        const val MIN_TEXT_CHARS = 50
        const val CHARS_PER_PAGE_THRESHOLD = 10
    }
}
