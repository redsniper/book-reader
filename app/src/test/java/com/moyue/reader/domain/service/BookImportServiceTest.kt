package com.moyue.reader.domain.service

import com.moyue.reader.FakeDispatcherProvider
import com.moyue.reader.FakeShelfRepository
import com.moyue.reader.MutableClock
import com.moyue.reader.data.file.BookFileStore
import com.moyue.reader.data.parser.TxtParser
import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.parser.BookParser
import com.moyue.reader.domain.parser.BookParserFactory
import com.moyue.reader.domain.parser.ParseRequest
import com.moyue.reader.domain.parser.ParsedBook
import com.moyue.reader.domain.parser.ParsedChapter
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class BookImportServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repository: FakeShelfRepository
    private lateinit var fileStore: BookFileStore
    private lateinit var service: BookImportService
    private lateinit var clock: MutableClock

    @Before
    fun setUp() {
        repository = FakeShelfRepository()
        fileStore = BookFileStore(tempFolder.newFolder("files"))
        clock = MutableClock(12345L)
    }

    private fun createService(vararg extraParsers: BookParser): BookImportService {
        val dispatcher = UnconfinedTestDispatcher()
        return BookImportService(
            repository = repository,
            fileStore = fileStore,
            parserFactory = BookParserFactory(
                listOf(TxtParser(ChapterSplitter())) + extraParsers,
            ),
            dispatchers = FakeDispatcherProvider(dispatcher),
            clock = clock,
        )
    }

    @Test
    fun `imports txt and creates chapters with cumulative offsets`() = runTest {
        service = createService()
        val content = """
            第一章 初见
            山里的风很轻。

            第二章 再会
            他下山了。
        """.trimIndent()

        val result = service.import(
            request(name = "剑来 作者：烽火戏诸侯.txt", content = content),
        )

        assertTrue(result is ImportResult.Imported)
        val imported = result as ImportResult.Imported
        assertEquals("剑来", imported.title)
        val book = repository.getBook(imported.bookId)!!
        assertEquals("烽火戏诸侯", book.author)
        assertEquals(BookFormat.TXT, book.format)
        assertEquals(2, book.chapterCount)
        assertFalse(book.isScannedPdf)
        assertFalse(book.hidden)
        assertEquals(12345L, book.importedAt)
        assertTrue(fileStore.resolve(book.sourcePath).isFile)

        val chapters = repository.chapters(book.id)
        assertEquals(listOf("第一章 初见", "第二章 再会"), chapters.map { it.title })
        assertEquals(0, chapters[0].charStart)
        assertEquals(chapters[0].charCount, chapters[1].charStart)
        assertEquals(content.substringAfter("第一章 初见\n").substringBefore("\n\n第二章").trim(), chapters[0].text)

        val progress = repository.progress(book.id)!!
        assertEquals(0, progress.chapterIndex)
        assertEquals(0f, progress.percent, 0.0001f)
    }

    @Test
    fun `rejects duplicate content including hidden books`() = runTest {
        service = createService()
        val content = "第一章\n内容"
        val first = service.import(request("第一本.txt", content)) as ImportResult.Imported
        repository.setHidden(first.bookId, true)

        val second = service.import(request("第二本.txt", content))

        assertTrue(second is ImportResult.Duplicate)
        assertEquals("第一本", (second as ImportResult.Duplicate).existingTitle)
        assertEquals(1, repository.books().size)
    }

    @Test
    fun `rejects unsupported format without temp files`() = runTest {
        service = createService()
        val result = service.import(request("book.mobi", "content", mime = null))
        assertTrue(result is ImportResult.Failed)
        assertEquals(ImportError.UnsupportedFormat, (result as ImportResult.Failed).error)
        assertFalse(java.io.File(tempFolder.root, "files/books").exists())
    }

    @Test
    fun `rejects known oversized file before opening stream`() = runTest {
        service = createService()
        var opened = false
        val result = service.import(
            ImportRequest(
                displayName = "big.txt",
                mimeType = "text/plain",
                sizeBytes = BookFormat.TXT.sizeLimitBytes + 1,
                openStream = {
                    opened = true
                    ByteArrayInputStream(ByteArray(0))
                },
            ),
        )
        assertTrue(result is ImportResult.Failed)
        assertTrue((result as ImportResult.Failed).error is ImportError.FileTooLarge)
        assertFalse(opened)
    }

    @Test
    fun `cleans temp files when parser fails`() = runTest {
        service = createService()
        val result = service.import(request("empty.txt", "   \n\n  "))
        assertTrue(result is ImportResult.Failed)
        assertEquals(ImportError.NoReadableText, (result as ImportResult.Failed).error)
        val booksDir = java.io.File(tempFolder.root, "files/books")
        assertTrue(!booksDir.exists() || booksDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `imports scanned pdf as page chapters`() = runTest {
        val scannedParser = object : BookParser {
            override fun supports(format: BookFormat): Boolean = format == BookFormat.PDF
            override suspend fun parse(request: ParseRequest): ParsedBook = ParsedBook(
                title = "扫描书",
                author = null,
                format = BookFormat.PDF,
                isScannedPdf = true,
                chapters = (0 until 3).map { page ->
                    ParsedChapter("第 ${page + 1} 页", "", pdfPageIndex = page)
                },
            )
        }
        service = createService(scannedParser)
        val result = service.import(
            request("扫描书.pdf", "%PDF fake", mime = "application/pdf"),
        )
        assertTrue(result is ImportResult.Imported)
        val book = repository.getBook((result as ImportResult.Imported).bookId)!!
        assertTrue(book.isScannedPdf)
        assertEquals(3, book.chapterCount)
        assertEquals(0, book.totalChars)
        assertEquals(3, repository.chapters(book.id).size)
        assertTrue(repository.chapters(book.id).all { it.isPdfPage })
    }

    private fun request(
        name: String,
        content: String,
        mime: String? = "text/plain",
    ): ImportRequest = ImportRequest(
        displayName = name,
        mimeType = mime,
        sizeBytes = content.toByteArray().size.toLong(),
        openStream = { ByteArrayInputStream(content.toByteArray()) },
    )
}
