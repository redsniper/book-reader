package com.moyue.reader.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: BookDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.bookDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `inserts and observes visible shelf with current chapter`() = runTest {
        val id = UUID.randomUUID().toString()
        dao.insertBook(bookEntity(id, hidden = false, importedAt = 100))
        dao.insertChapters(listOf(chapterEntity(id, 0, "第一章"), chapterEntity(id, 1, "第二章")))
        dao.insertChapterContents(listOf(ChapterContentEntity(id, 0, "正文")))
        dao.upsertProgress(ReadingProgressEntity(id, 1, 0, 0.5f, 200))

        val items = dao.observeShelf(hidden = false).first()

        assertEquals(1, items.size)
        val item = items.first()
        assertEquals(id, item.id)
        assertEquals("第二章", item.currentChapterTitle)
        assertEquals(0.5f, item.progressPercent!!, 0.0001f)
        assertEquals(1, item.progressChapterIndex)
    }

    @Test
    fun `hides books from normal shelf`() = runTest {
        val visibleId = UUID.randomUUID().toString()
        val hiddenId = UUID.randomUUID().toString()
        dao.insertBook(bookEntity(visibleId, hidden = false, importedAt = 100))
        dao.insertBook(bookEntity(hiddenId, hidden = true, importedAt = 100))

        val visible = dao.observeShelf(hidden = false).first()
        val hidden = dao.observeShelf(hidden = true).first()

        assertEquals(listOf(visibleId), visible.map { it.id })
        assertEquals(listOf(hiddenId), hidden.map { it.id })
    }

    @Test
    fun `delete book cascades chapters contents and progress`() = runTest {
        val id = UUID.randomUUID().toString()
        dao.insertBook(bookEntity(id, hidden = false, importedAt = 100))
        dao.insertChapters(listOf(chapterEntity(id, 0, "第一章")))
        dao.insertChapterContents(listOf(ChapterContentEntity(id, 0, "正文")))
        dao.upsertProgress(ReadingProgressEntity(id, 0, 0, 0f, 1))

        val deleted = dao.deleteBook(id)

        assertEquals(1, deleted)
        assertTrue(dao.getChapters(id).isEmpty())
        assertTrue(dao.getChaptersWithText(id).isEmpty())
        assertNull(dao.getProgress(id))
    }

    @Test
    fun `unique content hash prevents duplicate rows`() = runTest {
        val hash = "same-hash"
        dao.insertBook(bookEntity(UUID.randomUUID().toString(), hash = hash))
        val duplicate = runCatching {
            dao.insertBook(bookEntity(UUID.randomUUID().toString(), hash = hash))
        }
        assertTrue(duplicate.isFailure)
        assertNotNull(dao.findByHash(hash))
    }

    @Test
    fun `updates last opened and hidden flags`() = runTest {
        val id = UUID.randomUUID().toString()
        dao.insertBook(bookEntity(id, hidden = false, importedAt = 100))

        assertEquals(1, dao.updateLastOpened(id, 999))
        assertEquals(999L, dao.getBook(id)!!.lastOpenedAt)

        assertEquals(1, dao.setHidden(id, true))
        assertTrue(dao.getBook(id)!!.hidden)
        assertTrue(dao.observeShelf(false).first().isEmpty())
    }

    @Test
    fun `delete all hidden leaves normal books`() = runTest {
        dao.insertBook(bookEntity(UUID.randomUUID().toString(), hidden = true))
        dao.insertBook(bookEntity(UUID.randomUUID().toString(), hidden = true))
        dao.insertBook(bookEntity(UUID.randomUUID().toString(), hidden = false))

        val deleted = dao.deleteAllHidden()

        assertEquals(2, deleted)
        assertEquals(1, dao.observeShelf(false).first().size)
        assertTrue(dao.observeShelf(true).first().isEmpty())
    }

    @Test
    fun `reads chapter text joined for reader`() = runTest {
        val id = UUID.randomUUID().toString()
        dao.insertBook(bookEntity(id, hidden = false))
        dao.insertChapters(listOf(chapterEntity(id, 0, "第一章"), chapterEntity(id, 1, "第二章")))
        dao.insertChapterContents(listOf(ChapterContentEntity(id, 0, "第一段")))

        val chapters = dao.getChaptersWithText(id)

        assertEquals(2, chapters.size)
        assertEquals("第一段", chapters[0].text)
        assertEquals("", chapters[1].text.orEmpty())
    }

    private fun bookEntity(
        id: String,
        hidden: Boolean = false,
        importedAt: Long = 0,
        hash: String = UUID.randomUUID().toString(),
    ) = BookEntity(
        id = id,
        title = "测试书",
        author = "作者",
        format = "TXT",
        sourcePath = "books/$id/source.txt",
        coverPath = null,
        contentHash = hash,
        fileSize = 100,
        hidden = hidden,
        chapterCount = 2,
        totalChars = 100,
        isScannedPdf = false,
        importedAt = importedAt,
        lastOpenedAt = null,
    )

    private fun chapterEntity(bookId: String, index: Int, title: String) = ChapterEntity(
        bookId = bookId,
        chapterIndex = index,
        title = title,
        charStart = index * 50,
        charCount = 50,
        pdfPageIndex = null,
    )
}
