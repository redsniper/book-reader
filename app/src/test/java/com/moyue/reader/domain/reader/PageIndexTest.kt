package com.moyue.reader.domain.reader

import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.FontFamilyOption
import com.moyue.reader.domain.model.ReadingAnchor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageIndexTest {

    private fun chapters(): List<Chapter> {
        var cursor = 0
        return listOf(
            Chapter(0, "第一章", cursor, 25, "a".repeat(25)).also { cursor += 25 },
            Chapter(1, "第二章", cursor, 30, "b".repeat(30)).also { cursor += 30 },
        )
    }

    private fun index(): ReaderPageIndex {
        val chapters = chapters()
        val pages = listOf(
            PageInfo(0, 0, 10, true),
            PageInfo(0, 10, 20, false),
            PageInfo(0, 20, 25, false),
            PageInfo(1, 0, 15, true),
            PageInfo(1, 15, 30, false),
        )
        return ReaderPageIndex(pages, chapters)
    }

    @Test
    fun `maps chapter to first page`() {
        val index = index()
        assertEquals(0, index.pageForChapter(0))
        assertEquals(3, index.pageForChapter(1))
        assertEquals(3, index.pageForChapter(99))
    }

    @Test
    fun `maps page to chapter`() {
        val index = index()
        assertEquals(0, index.chapterIndexForPage(2))
        assertEquals(1, index.chapterIndexForPage(3))
        assertEquals(1, index.chapterIndexForPage(999))
    }

    @Test
    fun `finds page for anchor`() {
        val index = index()
        assertEquals(0, index.pageForAnchor(ReadingAnchor(0, 0)))
        assertEquals(1, index.pageForAnchor(ReadingAnchor(0, 10)))
        assertEquals(2, index.pageForAnchor(ReadingAnchor(0, 24)))
        assertEquals(3, index.pageForAnchor(ReadingAnchor(1, 0)))
        assertEquals(4, index.pageForAnchor(ReadingAnchor(1, 15)))
        assertEquals(4, index.pageForAnchor(ReadingAnchor(1, 999)))
    }

    @Test
    fun `anchor for page retains char offset`() {
        val index = index()
        assertEquals(ReadingAnchor(0, 0), index.anchorForPage(0))
        assertEquals(ReadingAnchor(0, 10), index.anchorForPage(1))
        assertEquals(ReadingAnchor(1, 15), index.anchorForPage(4))
    }

    @Test
    fun `progress is based on total chars and last page is 100 percent`() {
        val index = index()
        assertEquals(0, index.percentInt(0))
        assertTrue(index.percentInt(2) in 1..99)
        assertEquals(100, index.percentInt(4))
        assertEquals(1f, index.progressPercent(4), 0.0001f)
    }

    @Test
    fun `scanned pdf pages map one to one`() = runTest {
        val chapters = (0 until 3).map { page ->
            Chapter(page, "第 ${page + 1} 页", 0, 0, "", pdfPageIndex = page)
        }
        val index = PageIndexBuilder(FakeBreaker()).build(chapters, spec())
        assertEquals(3, index.totalPages)
        assertEquals(1, index.pageForAnchor(ReadingAnchor(1, 0, pdfPageIndex = 1)))
        assertEquals(33, index.percentInt(0))
        assertEquals(66, index.percentInt(1))
        assertEquals(100, index.percentInt(2)) // 最后一页即 100%
    }

    @Test
    fun `builds pages and progress callback`() = runTest {
        val chapters = chapters()
        val progress = mutableListOf<Float>()
        val index = PageIndexBuilder(FakeBreaker(charsPerPage = 10)).build(
            chapters = chapters,
            spec = spec(),
            onProgress = { progress.add(it) },
        )
        assertEquals(3 + 3, index.totalPages)
        assertEquals(listOf(0.5f, 1f), progress)
        // 页区间首尾相接、不重叠。
        chapters.indices.forEach { chapterIndex ->
            val pages = index.pages.filter { it.chapterIndex == chapterIndex }
            assertTrue(pages.isNotEmpty())
            assertEquals(0, pages.first().start)
            assertEquals(chapters[chapterIndex].charCount, pages.last().end)
            pages.zipWithNext().forEach { (first, second) ->
                assertEquals(first.end, second.start)
            }
            assertTrue(pages.first().isChapterStart)
        }
    }

    @Test
    fun `empty text chapter still yields one page`() = runTest {
        val chapters = listOf(Chapter(0, "空章", 0, 0, ""))
        val index = PageIndexBuilder(FakeBreaker()).build(chapters, spec())
        assertEquals(1, index.totalPages)
        assertEquals(0, index.pages.first().start)
        assertEquals(0, index.pages.first().end)
    }

    private fun spec() = PageLayoutSpec(
        widthPx = 100,
        heightPx = 100,
        fontFamily = FontFamilyOption.SERIF,
        fontSizePx = 16f,
        lineHeightPx = 24f,
        chapterTitleHeightPx = 20,
    )

    private class FakeBreaker(private val charsPerPage: Int = 10) : PageBreaker {
        override fun breakChapter(title: String, text: String, spec: PageLayoutSpec): List<PageRange> {
            val pages = mutableListOf<PageRange>()
            var start = 0
            while (start < text.length) {
                val end = (start + charsPerPage).coerceAtMost(text.length)
                pages.add(PageRange(start, end))
                start = end
            }
            return if (pages.isEmpty()) listOf(PageRange(0, 0)) else pages
        }
    }
}
