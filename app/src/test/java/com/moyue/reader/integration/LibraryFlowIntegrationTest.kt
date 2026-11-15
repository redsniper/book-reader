package com.moyue.reader.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moyue.reader.FakeDispatcherProvider
import com.moyue.reader.MutableClock
import com.moyue.reader.data.db.AppDatabase
import com.moyue.reader.data.file.BookFileStore
import com.moyue.reader.data.parser.TxtParser
import com.moyue.reader.data.repository.RoomBookRepository
import com.moyue.reader.data.repository.SharedPreferencesHiddenPasswordStore
import com.moyue.reader.domain.crypto.Pbkdf2PasswordHasher
import com.moyue.reader.domain.model.ReadingProgress
import com.moyue.reader.domain.parser.BookParserFactory
import com.moyue.reader.domain.service.BookImportService
import com.moyue.reader.domain.service.ChapterSplitter
import com.moyue.reader.domain.service.HiddenShelfManager
import com.moyue.reader.domain.service.ImportRequest
import com.moyue.reader.domain.service.ImportResult
import com.moyue.reader.domain.service.PasswordActionResult
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 集成测试：真实 Room + 真实私有文件 + 真实 TXT 解析器 + 导入服务 + 隐藏书架。
 *
 * 覆盖用户主流程，而不仅是单个类：
 * 导入 → 书架 → 隐藏/取消隐藏 → 进度保存/恢复 → 密码设置/验证 → 删除文件级联。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryFlowIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var fileStore: BookFileStore
    private lateinit var repository: RoomBookRepository
    private lateinit var importService: BookImportService
    private lateinit var hiddenShelfManager: HiddenShelfManager
    private lateinit var clock: MutableClock
    private val dispatchers = FakeDispatcherProvider(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fileStore = BookFileStore(tempFolder.newFolder("files"))
        clock = MutableClock(1_000L)
        repository = RoomBookRepository(database, fileStore, dispatchers, clock)
        importService = BookImportService(
            repository = repository,
            fileStore = fileStore,
            parserFactory = BookParserFactory(listOf(TxtParser(ChapterSplitter()))),
            dispatchers = dispatchers,
            clock = clock,
        )
        val passwordStore = SharedPreferencesHiddenPasswordStore(
            context.getSharedPreferences(
                "integration_security_${System.nanoTime()}",
                Context.MODE_PRIVATE,
            ),
        )
        hiddenShelfManager = HiddenShelfManager(
            passwordStore = passwordStore,
            hasher = Pbkdf2PasswordHasher(iterations = 1_000),
            shelfRepository = repository,
            dispatchers = dispatchers,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `import hide read save progress and delete`() = runTest {
        val content = """
            第一章 初见
            山里的风很轻。

            第二章 再会
            他下山了。
        """.trimIndent()
        val result = importService.import(
            ImportRequest(
                displayName = "剑来 作者：烽火戏诸侯.txt",
                mimeType = "text/plain",
                sizeBytes = content.toByteArray().size.toLong(),
                openStream = { ByteArrayInputStream(content.toByteArray()) },
            ),
        )
        assertTrue(result is ImportResult.Imported)
        val bookId = (result as ImportResult.Imported).bookId

        val book = repository.getBook(bookId) ?: error("导入后应能读取书籍")
        assertTrue(fileStore.resolve(book.sourcePath).isFile)
        assertEquals("剑来", book.title)
        assertEquals("烽火戏诸侯", book.author)

        val shelf = repository.observeShelf(hidden = false).first()
        assertEquals(1, shelf.size)
        assertEquals(bookId, shelf.first().book.id)
        assertEquals(0f, shelf.first().progress!!.percent, 0.0001f)

        // 隐藏后普通书架为空，隐藏书架可见。
        repository.setHidden(bookId, true)
        assertTrue(repository.observeShelf(false).first().isEmpty())
        assertEquals(bookId, repository.observeShelf(true).first().single().book.id)

        // 取消隐藏恢复普通书架。
        repository.setHidden(bookId, false)
        assertEquals(bookId, repository.observeShelf(false).first().single().book.id)

        // 阅读进度保存/恢复。
        val progress = ReadingProgress(
            chapterIndex = 1,
            charOffsetInChapter = 0,
            percent = 0.55f,
            updatedAt = clock.now,
        )
        repository.saveProgress(bookId, progress)
        assertEquals(progress, repository.getProgress(bookId))
        val shelfWithProgress = repository.observeShelf(false).first().single()
        assertEquals("第二章 再会", shelfWithProgress.currentChapterTitle)
        assertEquals(0.55f, shelfWithProgress.progress!!.percent, 0.0001f)

        // 删除后数据库与私有文件都清理。
        val bookDir = File(fileStore.resolve(book.sourcePath).parentFile!!.absolutePath)
        assertTrue(bookDir.exists())
        repository.deleteBook(bookId)
        assertNull(repository.getBook(bookId))
        assertTrue(repository.observeShelf(false).first().isEmpty())
        assertFalse(bookDir.exists())
    }

    @Test
    fun `hide verify password and reset hidden shelf`() = runTest {
        val ids = (1..2).map { index ->
            val content = "第一章 第 $index 本\n内容 $index"
            val result = importService.import(
                ImportRequest(
                    displayName = "隐藏$index.txt",
                    mimeType = "text/plain",
                    sizeBytes = content.toByteArray().size.toLong(),
                    openStream = { ByteArrayInputStream(content.toByteArray()) },
                ),
            ) as ImportResult.Imported
            repository.setHidden(result.bookId, true)
            result.bookId
        }
        val normal = importService.import(
            ImportRequest(
                displayName = "普通书.txt",
                mimeType = "text/plain",
                sizeBytes = 18,
                openStream = { ByteArrayInputStream("第一章\n普通内容".toByteArray()) },
            ),
        ) as ImportResult.Imported

        assertEquals(2, repository.observeShelf(true).first().size)
        assertEquals(1, repository.observeShelf(false).first().size)

        assertEquals(
            PasswordActionResult.Success,
            hiddenShelfManager.setInitialPassword("abcd".toCharArray()),
        )
        assertTrue(hiddenShelfManager.verify("abcd".toCharArray()))
        assertFalse(hiddenShelfManager.verify("wrong".toCharArray()))

        val deleted = hiddenShelfManager.resetHiddenShelf()
        assertEquals(2, deleted)
        assertTrue(repository.observeShelf(true).first().isEmpty())
        assertEquals(
            normal.bookId,
            repository.observeShelf(false).first().single().book.id,
        )
        assertFalse(hiddenShelfManager.isPasswordSet())
        ids.forEach { id ->
            assertNull(repository.getBook(id))
        }
    }
}
