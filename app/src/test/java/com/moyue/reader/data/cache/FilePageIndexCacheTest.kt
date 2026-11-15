package com.moyue.reader.data.cache

import com.moyue.reader.data.file.BookFileStore
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.FontFamilyOption
import com.moyue.reader.domain.reader.PageIndexSignature
import com.moyue.reader.domain.reader.PageInfo
import com.moyue.reader.domain.reader.PageLayoutSpec
import com.moyue.reader.domain.reader.ReaderPageIndex
import java.io.ByteArrayInputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 页索引文件缓存测试：覆盖往返、签名失效、损坏文件回退、删除级联与容量上限。
 *
 * 这些用例使用真实文件系统（TemporaryFolder）而不是 mock，确保原子写入、
 * 目录布局与异常回退路径和上线代码一致。
 */
class FilePageIndexCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fileStore: BookFileStore
    private lateinit var cache: FilePageIndexCache
    private lateinit var bookId: String

    @Before
    fun setUp() {
        fileStore = BookFileStore(tempFolder.newFolder("files"))
        cache = FilePageIndexCache(fileStore)
        bookId = UUID.randomUUID().toString()
        fileStore.stageSource(bookId, "txt", 1024) {
            ByteArrayInputStream("source".toByteArray())
        }
        fileStore.commit(bookId)
    }

    @Test
    fun `round trips pages and chapter mapping`() {
        val chapters = chapters()
        val signature = signature(chapters)
        val index = index(chapters)

        cache.save(bookId, signature, chapters, index)
        val loaded = cache.load(bookId, signature, chapters)

        assertNotNull(loaded)
        assertEquals(index.pages, loaded!!.pages)
        assertEquals(5, loaded.totalPages)
        assertEquals(0, loaded.pageForChapter(0))
        assertEquals(3, loaded.pageForChapter(1))
        assertEquals(1, loaded.chapterIndexForPage(3))
    }

    @Test
    fun `new cache instance can read persisted file`() {
        val chapters = chapters()
        val signature = signature(chapters)
        cache.save(bookId, signature, chapters, index(chapters))

        val reopened = FilePageIndexCache(fileStore).load(bookId, signature, chapters)

        assertNotNull(reopened)
        assertEquals(5, reopened!!.totalPages)
    }

    @Test
    fun `layout change misses cache`() {
        val chapters = chapters()
        val signature = signature(chapters)
        cache.save(bookId, signature, chapters, index(chapters))

        val changed = signature.copy(layout = signature.layout.copy(fontSizePx = 18f))

        assertNull(cache.load(bookId, changed, chapters))
    }

    @Test
    fun `content or chapter metadata change misses cache`() {
        val chapters = chapters()
        val signature = signature(chapters)
        cache.save(bookId, signature, chapters, index(chapters))

        assertNull(cache.load(bookId, signature.copy(contentHash = "other"), chapters))

        val changedChapters = listOf(
            chapters[0],
            chapters[1].copy(charCount = 31, text = "b".repeat(31)),
        )
        assertNull(cache.load(bookId, signature, changedChapters))
    }

    @Test
    fun `same book different viewport does not collide`() {
        val chapters = chapters()
        val portrait = signature(chapters)
        cache.save(bookId, portrait, chapters, index(chapters))

        val landscape = portrait.copy(layout = portrait.layout.copy(widthPx = 400, heightPx = 200))

        assertNotNull(cache.load(bookId, portrait, chapters))
        assertNull(cache.load(bookId, landscape, chapters))
    }

    @Test
    fun `corrupt file is discarded and reported as miss`() {
        val chapters = chapters()
        val signature = signature(chapters)
        cache.save(bookId, signature, chapters, index(chapters))
        val file = cacheFiles().single()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        assertNull(cache.load(bookId, signature, chapters))
        assertFalse(file.exists())
    }

    @Test
    fun `truncated file is discarded and reported as miss`() {
        val chapters = chapters()
        val signature = signature(chapters)
        cache.save(bookId, signature, chapters, index(chapters))
        val file = cacheFiles().single()
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))

        assertNull(cache.load(bookId, signature, chapters))
        assertFalse(file.exists())
    }

    @Test
    fun `invalid page coverage is rejected before writing`() {
        val chapters = chapters()
        val invalid = ReaderPageIndex(
            pages = listOf(PageInfo(0, 0, 5, true)),
            chapters = chapters,
        )

        assertThrows(IllegalArgumentException::class.java) {
            cache.save(bookId, signature(chapters), chapters, invalid)
        }
        assertTrue(cacheFiles().isEmpty())
    }

    @Test
    fun `deleting book removes pagination cache`() {
        val chapters = chapters()
        cache.save(bookId, signature(chapters), chapters, index(chapters))
        assertTrue(cacheFiles().isNotEmpty())

        fileStore.deleteBookFiles(bookId)

        assertFalse(fileStore.paginationCacheDir(bookId).exists())
    }

    @Test
    fun `keeps at most four layout variants per book`() {
        val chapters = chapters()
        repeat(6) { variant ->
            val signature = signature(chapters).copy(
                layout = signature(chapters).layout.copy(
                    fontSizePx = 16f + variant,
                    lineHeightPx = 24f + variant,
                ),
            )
            cache.save(bookId, signature, chapters, index(chapters))
        }

        assertTrue(cacheFiles().size <= 4)
    }

    @Test
    fun `empty chapter still round trips placeholder page`() {
        val chapters = listOf(
            Chapter(0, "空章", 0, 0, ""),
            Chapter(1, "正文", 0, 5, "abcde"),
        )
        val pages = listOf(
            PageInfo(0, 0, 0, true),
            PageInfo(1, 0, 5, true),
        )
        val index = ReaderPageIndex(pages, chapters)
        val signature = signature(chapters)

        cache.save(bookId, signature, chapters, index)
        val loaded = cache.load(bookId, signature, chapters)

        assertNotNull(loaded)
        assertEquals(pages, loaded!!.pages)
    }

    private fun cacheFiles(): List<java.io.File> =
        fileStore.paginationCacheDir(bookId)
            .listFiles { file -> file.isFile && file.name.endsWith(".idx") }
            ?.toList()
            .orEmpty()

    private fun signature(chapters: List<Chapter>): PageIndexSignature =
        PageIndexSignature.of(
            contentHash = "content-hash",
            chapters = chapters,
            layout = spec(),
        )

    private fun index(chapters: List<Chapter>): ReaderPageIndex = ReaderPageIndex(
        pages = listOf(
            PageInfo(0, 0, 10, true),
            PageInfo(0, 10, 20, false),
            PageInfo(0, 20, 25, false),
            PageInfo(1, 0, 15, true),
            PageInfo(1, 15, 30, false),
        ),
        chapters = chapters,
    )

    private fun chapters(): List<Chapter> = listOf(
        Chapter(0, "第一章", 0, 25, "a".repeat(25)),
        Chapter(1, "第二章", 25, 30, "b".repeat(30)),
    )

    private fun spec() = PageLayoutSpec(
        widthPx = 200,
        heightPx = 300,
        fontFamily = FontFamilyOption.SERIF,
        fontSizePx = 16f,
        lineHeightPx = 24f,
        chapterTitleHeightPx = 20,
    )
}
