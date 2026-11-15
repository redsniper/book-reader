package com.moyue.reader.ui.shelf

import android.net.Uri
import com.moyue.reader.FakeDispatcherProvider
import com.moyue.reader.FakeShelfRepository
import com.moyue.reader.MainDispatcherRule
import com.moyue.reader.MutableClock
import com.moyue.reader.data.file.BookFileStore
import com.moyue.reader.domain.crypto.HashedPassword
import com.moyue.reader.domain.crypto.Pbkdf2PasswordHasher
import com.moyue.reader.domain.model.Book
import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.ReadingProgress
import com.moyue.reader.domain.parser.BookParserFactory
import com.moyue.reader.domain.service.BookImportService
import com.moyue.reader.domain.service.HiddenPasswordStore
import com.moyue.reader.domain.service.HiddenShelfManager
import com.moyue.reader.domain.service.HiddenShelfSession
import com.moyue.reader.domain.service.ImportRequest
import com.moyue.reader.domain.service.PasswordActionResult
import com.moyue.reader.ui.common.DocumentResolver
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repository: FakeShelfRepository
    private lateinit var passwordStore: FakePasswordStore
    private lateinit var manager: HiddenShelfManager
    private lateinit var session: HiddenShelfSession
    private lateinit var viewModel: ShelfViewModel
    private lateinit var clock: MutableClock

    private class FakePasswordStore : HiddenPasswordStore {
        var stored: HashedPassword? = null
        override fun load(): HashedPassword? = stored
        override fun save(password: HashedPassword) {
            stored = password
        }

        override fun clear() {
            stored = null
        }
    }

    @Before
    fun setUp() {
        val dispatchers = FakeDispatcherProvider(UnconfinedTestDispatcher())
        repository = FakeShelfRepository()
        passwordStore = FakePasswordStore()
        session = HiddenShelfSession()
        clock = MutableClock(10_000L)
        manager = HiddenShelfManager(
            passwordStore = passwordStore,
            hasher = Pbkdf2PasswordHasher(iterations = 1_000),
            shelfRepository = repository,
            dispatchers = dispatchers,
        )
        val importService = BookImportService(
            repository = repository,
            fileStore = BookFileStore(tempFolder.newFolder("files")),
            parserFactory = BookParserFactory(emptyList()),
            dispatchers = dispatchers,
            clock = clock,
        )
        viewModel = ShelfViewModel(
            repository = repository,
            importService = importService,
            hiddenShelfManager = manager,
            hiddenShelfSession = session,
            documentResolver = object : DocumentResolver {
                override suspend fun resolve(uri: Uri): ImportRequest =
                    error("本测试不触发导入")
            },
            clock = clock,
        )
    }

    @Test
    fun `observes books hides and unlocks hidden shelf`() = runTest {
        val first = book("第一本")
        val second = book("第二本")
        repository.saveImportedBook(first, chapters(), progress())
        repository.saveImportedBook(second, chapters(), progress())
        assertEquals(
            PasswordActionResult.Success,
            manager.setInitialPassword("abcd".toCharArray()),
        )

        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.books.size)
        viewModel.refreshPasswordState()
        assertTrue(viewModel.uiState.value.passwordSet)

        viewModel.toggleHidden(first)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.books.size)
        assertEquals(second.id, viewModel.uiState.value.books.single().book.id)
        assertEquals(1, repository.books().count { it.hidden })

        viewModel.onPullDownTriggered()
        assertTrue(viewModel.uiState.value.unlockDialogVisible)
        viewModel.confirmUnlock("abcd")
        advanceUntilIdle()

        assertEquals(ShelfMode.HIDDEN, viewModel.uiState.value.mode)
        assertTrue(session.isUnlocked)
        assertEquals(1, viewModel.uiState.value.books.size)
        assertEquals(first.id, viewModel.uiState.value.books.single().book.id)

        viewModel.exitHiddenShelf()
        advanceUntilIdle()
        assertFalse(session.isUnlocked)
        assertEquals(ShelfMode.NORMAL, viewModel.uiState.value.mode)
        assertEquals(1, viewModel.uiState.value.books.size)
    }

    @Test
    fun `wrong password keeps dialog and locks`() = runTest {
        manager.setInitialPassword("abcd".toCharArray())
        advanceUntilIdle()
        viewModel.onPullDownTriggered()
        viewModel.confirmUnlock("wrong")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.unlockDialogVisible)
        assertNotNull(viewModel.uiState.value.unlockError)
        assertFalse(session.isUnlocked)
        assertEquals(ShelfMode.NORMAL, viewModel.uiState.value.mode)
    }

    @Test
    fun `background lock exits hidden shelf and shows normal books`() = runTest {
        val first = book("第一本")
        val second = book("第二本")
        repository.saveImportedBook(first, chapters(), progress())
        repository.saveImportedBook(second, chapters(), progress())
        manager.setInitialPassword("abcd".toCharArray())
        advanceUntilIdle()

        viewModel.toggleHidden(first)
        advanceUntilIdle()
        viewModel.onPullDownTriggered()
        viewModel.confirmUnlock("abcd")
        advanceUntilIdle()
        assertEquals(ShelfMode.HIDDEN, viewModel.uiState.value.mode)

        // 模拟 MainActivity.onStop 的锁定：隐藏书架立即退回普通书架并给出持久提示。
        session.lock()
        advanceUntilIdle()

        assertEquals(ShelfMode.NORMAL, viewModel.uiState.value.mode)
        assertFalse(session.isUnlocked)
        assertEquals(1, viewModel.uiState.value.books.size)
        assertEquals(second.id, viewModel.uiState.value.books.single().book.id)
        assertTrue(viewModel.uiState.value.hiddenLockedNotice)
        assertNull(viewModel.uiState.value.message)

        viewModel.dismissHiddenLockedNotice()
        assertFalse(viewModel.uiState.value.hiddenLockedNotice)
    }

    @Test
    fun `prompts for password setup when missing`() = runTest {
        viewModel.onPullDownTriggered()
        assertTrue(viewModel.uiState.value.setPasswordPromptVisible)
        assertFalse(viewModel.uiState.value.unlockDialogVisible)
        viewModel.dismissSetPasswordPrompt()
        assertFalse(viewModel.uiState.value.setPasswordPromptVisible)
    }

    @Test
    fun `deletes book and clears editor`() = runTest {
        val book = book("待删")
        repository.saveImportedBook(book, chapters(), progress())
        advanceUntilIdle()

        viewModel.openEditor(book)
        viewModel.requestDelete(book)
        viewModel.deleteBook(book)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.books.isEmpty())
        assertNull(viewModel.uiState.value.editingBook)
        assertNull(viewModel.uiState.value.deleteConfirmBook)
        assertNotNull(viewModel.uiState.value.message)
        assertTrue(repository.books().isEmpty())
    }

    private fun book(title: String): Book = Book(
        id = UUID.randomUUID().toString(),
        title = title,
        author = null,
        format = BookFormat.TXT,
        sourcePath = "books/x/source.txt",
        contentHash = title,
        coverPath = null,
        fileSize = 100,
        hidden = false,
        chapterCount = 1,
        totalChars = 10,
        isScannedPdf = false,
        importedAt = 1L,
        lastOpenedAt = null,
    )

    private fun chapters(): List<Chapter> = listOf(
        Chapter(0, "第一章", 0, 10, "正文正文正文"),
    )

    private fun progress(): ReadingProgress = ReadingProgress(0, 0, 0f, 1L)
}
