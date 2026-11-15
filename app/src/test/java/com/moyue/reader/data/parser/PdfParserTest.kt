package com.moyue.reader.data.parser

import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.parser.BookParseException
import com.moyue.reader.domain.parser.ParseRequest
import com.moyue.reader.domain.parser.PdfTextExtraction
import com.moyue.reader.domain.parser.PdfTextExtractor
import com.moyue.reader.domain.service.ChapterSplitter
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `text pdf is reflowed and chapterized`() = runTest {
        val extractor = FakeExtractor(
            PdfTextExtraction(
                pageCount = 2,
                pageTexts = listOf(
                    "第一章 初见\n山里的风很轻，少年背着剑，沿着石阶一步一步向山下走去。他听见远处有钟声，也听见身后有人喊他的名字。",
                    "第二章 再会\n他下山了，回头看了一眼。山门在雾里，像一幅被雨水打湿的旧画。",
                ),
                title = "测试 PDF",
                author = "PDF 作者",
            ),
        )
        val parser = PdfParser(extractor, ChapterSplitter())
        val file = tempFolder.newFile("text.pdf")
        file.writeText("%PDF fake")

        val parsed = parser.parse(ParseRequest(file, "text.pdf", BookFormat.PDF))

        assertFalse(parsed.isScannedPdf)
        assertEquals("测试 PDF", parsed.title)
        assertEquals("PDF 作者", parsed.author)
        assertEquals(listOf("第一章 初见", "第二章 再会"), parsed.chapters.map { it.title })
        assertTrue(parsed.chapters.all { it.text.isNotBlank() })
    }

    @Test
    fun `scanned pdf becomes one chapter per page`() = runTest {
        val extractor = FakeExtractor(
            PdfTextExtraction(
                pageCount = 4,
                pageTexts = listOf("", " ", "\n", ""),
                title = null,
                author = null,
            ),
        )
        val parser = PdfParser(extractor, ChapterSplitter())
        val file = tempFolder.newFile("scan.pdf")
        file.writeText("%PDF fake")

        val parsed = parser.parse(ParseRequest(file, "scan.pdf", BookFormat.PDF))

        assertTrue(parsed.isScannedPdf)
        assertEquals(4, parsed.chapters.size)
        assertEquals((0 until 4).toList(), parsed.chapters.map { it.pdfPageIndex })
        assertTrue(parsed.chapters.all { it.text.isEmpty() })
        assertEquals("第 1 页", parsed.chapters.first().title)
    }

    @Test
    fun `low text ratio is treated as scanned`() = runTest {
        val extractor = FakeExtractor(
            PdfTextExtraction(
                pageCount = 20,
                pageTexts = List(20) { if (it == 0) "短" else "" },
                title = null,
                author = null,
            ),
        )
        val parser = PdfParser(extractor, ChapterSplitter())
        val file = tempFolder.newFile("low.pdf")
        file.writeText("%PDF fake")

        val parsed = parser.parse(ParseRequest(file, "low.pdf", BookFormat.PDF))

        assertTrue(parsed.isScannedPdf)
        assertEquals(20, parsed.chapters.size)
    }

    @Test
    fun `pdf without pages fails`() = runTest {
        val parser = PdfParser(
            FakeExtractor(PdfTextExtraction(0, emptyList(), null, null)),
            ChapterSplitter(),
        )
        val file = tempFolder.newFile("empty.pdf")
        file.writeText("%PDF fake")

        val error = runCatching {
            parser.parse(ParseRequest(file, "empty.pdf", BookFormat.PDF))
        }.exceptionOrNull()
        assertTrue(error is BookParseException.Corrupted)
    }

    private class FakeExtractor(
        private val result: PdfTextExtraction,
    ) : PdfTextExtractor {
        override fun extract(file: File): PdfTextExtraction = result
    }
}
