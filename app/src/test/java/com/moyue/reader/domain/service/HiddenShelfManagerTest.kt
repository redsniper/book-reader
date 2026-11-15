package com.moyue.reader.domain.service

import com.moyue.reader.FakeDispatcherProvider
import com.moyue.reader.FakeShelfRepository
import com.moyue.reader.domain.crypto.HashedPassword
import com.moyue.reader.domain.crypto.Pbkdf2PasswordHasher
import com.moyue.reader.domain.model.Book
import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.ReadingProgress
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenShelfManagerTest {

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

    private fun manager(
        repository: FakeShelfRepository = FakeShelfRepository(),
        store: FakePasswordStore = FakePasswordStore(),
    ): Triple<HiddenShelfManager, FakeShelfRepository, FakePasswordStore> {
        val hasher = Pbkdf2PasswordHasher(iterations = 1_000)
        return Triple(
            HiddenShelfManager(
                passwordStore = store,
                hasher = hasher,
                shelfRepository = repository,
                dispatchers = FakeDispatcherProvider(UnconfinedTestDispatcher()),
            ),
            repository,
            store,
        )
    }

    @Test
    fun `sets verifies and changes password`() = runTest {
        val (manager, _, store) = manager()

        assertFalse(manager.isPasswordSet())
        assertEquals(PasswordActionResult.Success, manager.setInitialPassword("abcd".toCharArray()))
        assertTrue(manager.isPasswordSet())
        assertTrue(store.stored != null)
        assertTrue(manager.verify("abcd".toCharArray()))
        assertFalse(manager.verify("wrong".toCharArray()))
        assertEquals(PasswordActionResult.AlreadySet, manager.setInitialPassword("efgh".toCharArray()))
        assertEquals(
            PasswordActionResult.WrongOldPassword,
            manager.changePassword("wrong".toCharArray(), "efgh".toCharArray()),
        )
        assertEquals(
            PasswordActionResult.Success,
            manager.changePassword("abcd".toCharArray(), "efgh".toCharArray()),
        )
        assertTrue(manager.verify("efgh".toCharArray()))
        assertFalse(manager.verify("abcd".toCharArray()))
    }

    @Test
    fun `rejects invalid password length`() = runTest {
        val (manager, _, store) = manager()
        assertEquals(PasswordActionResult.InvalidLength, manager.setInitialPassword("123".toCharArray()))
        assertNull(store.stored)
    }

    @Test
    fun `change password requires existing password`() = runTest {
        val (manager, _, _) = manager()
        assertEquals(
            PasswordActionResult.NotSet,
            manager.changePassword("abcd".toCharArray(), "efgh".toCharArray()),
        )
    }

    @Test
    fun `reset deletes only hidden books and clears password`() = runTest {
        val (manager, repository, store) = manager()
        repository.saveImportedBook(book("normal", hidden = false), chapters("normal"), progress())
        repository.saveImportedBook(book("hidden-1", hidden = true), chapters("hidden-1"), progress())
        repository.saveImportedBook(book("hidden-2", hidden = true), chapters("hidden-2"), progress())
        manager.setInitialPassword("abcd".toCharArray())

        val deleted = manager.resetHiddenShelf()

        assertEquals(2, deleted)
        assertEquals(listOf("normal"), repository.books().map { it.id })
        assertNull(store.stored)
        assertFalse(manager.isPasswordSet())
    }

    private fun book(id: String, hidden: Boolean) = Book(
        id = id,
        title = id,
        author = null,
        format = BookFormat.TXT,
        sourcePath = "books/$id/source.txt",
        contentHash = id,
        coverPath = null,
        fileSize = 10,
        hidden = hidden,
        chapterCount = 1,
        totalChars = 10,
        isScannedPdf = false,
        importedAt = 1,
        lastOpenedAt = null,
    )

    private fun chapters(id: String) = listOf(
        Chapter(0, "第一章", 0, 10, "正文正文正文"),
    )

    private fun progress() = ReadingProgress(0, 0, 0f, 1L)
}
