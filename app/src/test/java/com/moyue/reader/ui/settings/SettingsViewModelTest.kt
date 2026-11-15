package com.moyue.reader.ui.settings

import com.moyue.reader.FakeDispatcherProvider
import com.moyue.reader.FakeSettingsRepository
import com.moyue.reader.FakeShelfRepository
import com.moyue.reader.MainDispatcherRule
import com.moyue.reader.domain.crypto.HashedPassword
import com.moyue.reader.domain.crypto.Pbkdf2PasswordHasher
import com.moyue.reader.domain.model.Book
import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.ReaderSettings
import com.moyue.reader.domain.model.ReadingProgress
import com.moyue.reader.domain.service.HiddenPasswordStore
import com.moyue.reader.domain.service.HiddenShelfManager
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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

    @Test
    fun `updates reading settings`() = runTest {
        val settingsRepository = FakeSettingsRepository()
        val viewModel = SettingsViewModel(
            settingsRepository,
            manager(FakeShelfRepository()),
        )
        advanceUntilIdle()

        val updated = ReaderSettings(fontSizeSp = 24f)
        viewModel.updateSettings(updated)
        advanceUntilIdle()

        assertEquals(24f, settingsRepository.current.fontSizeSp, 0.001f)
        assertEquals(24f, viewModel.uiState.value.settings.fontSizeSp, 0.001f)
    }

    @Test
    fun `sets changes and resets hidden password with hidden books`() = runTest {
        val repository = FakeShelfRepository()
        val hidden = book("隐藏书", hidden = true)
        repository.saveImportedBook(hidden, chapters(), ReadingProgress(0, 0, 0f, 1L))
        val manager = manager(repository)
        val viewModel = SettingsViewModel(FakeSettingsRepository(), manager)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.passwordSet)
        viewModel.setPassword("abcd")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.passwordSet)
        assertTrue(manager.verify("abcd".toCharArray()))

        viewModel.changePassword("wrong", "newpass")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.notice)
        assertTrue(manager.verify("abcd".toCharArray()))

        viewModel.changePassword("abcd", "newpass")
        advanceUntilIdle()
        assertTrue(manager.verify("newpass".toCharArray()))

        viewModel.resetHiddenShelf("not-the-word")
        advanceUntilIdle()
        assertTrue(repository.books().any { it.hidden })

        viewModel.resetHiddenShelf(SettingsViewModel.RESET_CONFIRM_WORD)
        advanceUntilIdle()
        assertTrue(repository.books().none { it.hidden })
        assertFalse(viewModel.uiState.value.passwordSet)
        assertFalse(manager.isPasswordSet())
    }

    private fun manager(repository: FakeShelfRepository): HiddenShelfManager =
        HiddenShelfManager(
            passwordStore = FakePasswordStore(),
            hasher = Pbkdf2PasswordHasher(iterations = 1_000),
            shelfRepository = repository,
            dispatchers = FakeDispatcherProvider(UnconfinedTestDispatcher()),
        )

    private fun book(title: String, hidden: Boolean): Book = Book(
        id = UUID.randomUUID().toString(),
        title = title,
        author = null,
        format = BookFormat.TXT,
        sourcePath = "books/x/source.txt",
        contentHash = title,
        coverPath = null,
        fileSize = 10,
        hidden = hidden,
        chapterCount = 1,
        totalChars = 10,
        isScannedPdf = false,
        importedAt = 1L,
        lastOpenedAt = null,
    )

    private fun chapters(): List<Chapter> = listOf(
        Chapter(0, "第一章", 0, 10, "正文正文正文"),
    )
}
