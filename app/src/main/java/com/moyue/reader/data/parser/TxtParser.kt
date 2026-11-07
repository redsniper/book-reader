package com.moyue.reader.data.parser

import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.parser.BookParseException
import com.moyue.reader.domain.parser.BookParser
import com.moyue.reader.domain.parser.ParseRequest
import com.moyue.reader.domain.parser.ParsedBook
import com.moyue.reader.domain.parser.ParsedChapter
import com.moyue.reader.domain.service.ChapterSplitter
import com.moyue.reader.domain.service.TextNormalizer
import java.io.File

/**
 * TXT 解析器：编码探测 → 规范化 → 自动分章。
 *
 * 标题/作者由导入服务结合文件名推断，解析器只负责内容，保持单一职责。
 */
class TxtParser(
    private val chapterSplitter: ChapterSplitter,
) : BookParser {

    override fun supports(format: BookFormat): Boolean = format == BookFormat.TXT

    override suspend fun parse(request: ParseRequest): ParsedBook {
        val file = request.source
        if (!file.isFile) throw BookParseException.Corrupted("TXT 文件不存在")
        if (file.length() > BookFormat.TXT.sizeLimitBytes) {
            throw BookParseException.TooLarge(BookFormat.TXT.sizeLimitBytes)
        }

        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            throw BookParseException.Io(e)
        }
        val decoded = TextDecoder.decode(bytes)
        val normalized = TextNormalizer.normalize(decoded.text)
        if (normalized.isBlank()) {
            throw BookParseException.NoReadableText("TXT 文件没有可读文字")
        }

        val chapters = chapterSplitter.split(normalized)
            .map { ParsedChapter(title = it.title, text = it.text) }
        if (chapters.isEmpty()) {
            throw BookParseException.NoReadableText("TXT 文件没有可读文字")
        }
        return ParsedBook(
            title = null,
            author = null,
            format = BookFormat.TXT,
            chapters = chapters,
        )
    }
}
