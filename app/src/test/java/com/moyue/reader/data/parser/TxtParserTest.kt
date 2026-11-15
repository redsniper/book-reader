package com.moyue.reader.data.parser

import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.parser.BookParseException
import com.moyue.reader.domain.parser.ParseRequest
import com.moyue.reader.domain.service.ChapterSplitter
import java.nio.charset.Charset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TxtParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val parser = TxtParser(ChapterSplitter())

    @Test
    fun `parses utf8 txt into chapters`() = runTest {
        val file = tempFolder.newFile("novel.txt")
        file.writeText(
            """
            第一章 初见
            山里的风很轻。

            第二章 再会
            他下山了。
            """.trimIndent(),
        )

        val parsed = parser.parse(ParseRequest(file, "novel.txt", BookFormat.TXT))

        assertEquals(BookFormat.TXT, parsed.format)
        assertEquals(2, parsed.chapters.size)
        assertEquals("第一章 初见", parsed.chapters[0].title)
        assertEquals("山里的风很轻。", parsed.chapters[0].text)
    }

    @Test
    fun `parses gb18030 txt`() = runTest {
        val file = tempFolder.newFile("gbk.txt")
        file.writeBytes("第一章 初见\n你好，世界。".toByteArray(Charset.forName("GB18030")))

        val parsed = parser.parse(ParseRequest(file, "gbk.txt", BookFormat.TXT))

        assertEquals(1, parsed.chapters.size)
        assertEquals("第一章 初见", parsed.chapters[0].title)
        assertEquals("你好，世界。", parsed.chapters[0].text)
    }

    @Test
    fun `empty txt fails with no readable text`() = runTest {
        val file = tempFolder.newFile("empty.txt")
        file.writeText("   \n\n ")

        val error = runCatching {
            parser.parse(ParseRequest(file, "empty.txt", BookFormat.TXT))
        }.exceptionOrNull()
        assertTrue(error is BookParseException.NoReadableText)
    }
}
