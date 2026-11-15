package com.moyue.reader

import com.moyue.reader.core.Clock
import com.moyue.reader.core.DispatcherProvider
import com.moyue.reader.domain.model.Book
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.ReadingProgress
import com.moyue.reader.domain.model.ShelfItem
import com.moyue.reader.domain.repository.ReadingRepository
import com.moyue.reader.domain.repository.ShelfRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** 测试用调度器：将 IO/Default/Main 全部指向同一个测试调度器。 */
class FakeDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val main: CoroutineDispatcher get() = dispatcher
}

/** 可手动推进的测试时钟。 */
class MutableClock(var now: Long = 1_000L) : Clock {
    override fun nowMillis(): Long = now
}

/**
 * 内存书架仓库，用于导入服务与隐藏书架的单元测试。
 * 行为与 RoomBookRepository 保持一致（过滤、排序、级联删除语义）。
 */
class FakeShelfRepository : ShelfRepository, ReadingRepository {
    private val booksFlow = MutableStateFlow<List<Book>>(emptyList())
    private val chaptersByBook = mutableMapOf<String, List<Chapter>>()
    private val progressByBook = mutableMapOf<String, ReadingProgress>()

    override fun observeShelf(hidden: Boolean): Flow<List<ShelfItem>> = booksFlow.map { books ->
        books.filter { it.hidden == hidden }
            .sortedByDescending { it.lastOpenedAt ?: it.importedAt }
            .map { book ->
                val progress = progressByBook[book.id]
                ShelfItem(
                    book = book,
                    progress = progress,
                    currentChapterTitle = progress?.let { p ->
                        chaptersByBook[book.id]?.getOrNull(p.chapterIndex)?.title
                    },
                )
            }
    }

    override fun observeBook(bookId: String): Flow<Book?> =
        booksFlow.map { books -> books.firstOrNull { it.id == bookId } }

    override suspend fun getBook(bookId: String): Book? =
        booksFlow.value.firstOrNull { it.id == bookId }

    override suspend fun findBookByHash(contentHash: String): Book? =
        booksFlow.value.firstOrNull { it.contentHash == contentHash }

    override suspend fun saveImportedBook(
        book: Book,
        chapters: List<Chapter>,
        initialProgress: ReadingProgress,
    ) {
        booksFlow.value = booksFlow.value + book
        chaptersByBook[book.id] = chapters
        progressByBook[book.id] = initialProgress
    }

    override suspend fun setHidden(bookId: String, hidden: Boolean) {
        booksFlow.value = booksFlow.value.map {
            if (it.id == bookId) it.copy(hidden = hidden) else it
        }
    }

    override suspend fun deleteBook(bookId: String) {
        booksFlow.value = booksFlow.value.filterNot { it.id == bookId }
        chaptersByBook.remove(bookId)
        progressByBook.remove(bookId)
    }

    override suspend fun deleteAllHidden(): Int {
        val hiddenIds = booksFlow.value.filter { it.hidden }.map { it.id }.toSet()
        booksFlow.value = booksFlow.value.filterNot { it.id in hiddenIds }
        hiddenIds.forEach {
            chaptersByBook.remove(it)
            progressByBook.remove(it)
        }
        return hiddenIds.size
    }

    override suspend fun updateLastOpened(bookId: String, timestamp: Long) {
        booksFlow.value = booksFlow.value.map {
            if (it.id == bookId) it.copy(lastOpenedAt = timestamp) else it
        }
    }

    override suspend fun getChapters(bookId: String): List<Chapter> =
        chaptersByBook[bookId].orEmpty()

    override suspend fun getChaptersWithText(bookId: String): List<Chapter> =
        chaptersByBook[bookId].orEmpty()

    override suspend fun getProgress(bookId: String): ReadingProgress? = progressByBook[bookId]

    override suspend fun saveProgress(bookId: String, progress: ReadingProgress) {
        progressByBook[bookId] = progress
        booksFlow.value = booksFlow.value.map {
            if (it.id == bookId) it.copy(lastOpenedAt = progress.updatedAt) else it
        }
    }

    fun books(): List<Book> = booksFlow.value

    fun chapters(bookId: String): List<Chapter> = chaptersByBook[bookId].orEmpty()

    fun progress(bookId: String): ReadingProgress? = progressByBook[bookId]
}
