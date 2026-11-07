package com.moyue.reader.data.repository

import androidx.room.withTransaction
import com.moyue.reader.core.Clock
import com.moyue.reader.core.DispatcherProvider
import com.moyue.reader.data.db.AppDatabase
import com.moyue.reader.data.db.BookEntity
import com.moyue.reader.data.db.ChapterContentEntity
import com.moyue.reader.data.db.ChapterEntity
import com.moyue.reader.data.db.ReadingProgressEntity
import com.moyue.reader.data.db.toDomain
import com.moyue.reader.data.file.BookFileStore
import com.moyue.reader.domain.model.Book
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.ReadingProgress
import com.moyue.reader.domain.model.ShelfItem
import com.moyue.reader.domain.repository.ReadingRepository
import com.moyue.reader.domain.repository.ShelfRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room + 私有文件的仓库实现。
 *
 * 删除书籍时先删数据库（级联清理元数据/正文/进度），再删磁盘文件；
 * 文件删除失败不回滚数据库，避免用户看到"删除失败但书已消失"的矛盾状态，
 * 残留文件可由后续版本做孤儿清理。
 */
class RoomBookRepository(
    private val database: AppDatabase,
    private val fileStore: BookFileStore,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
) : ShelfRepository, ReadingRepository {

    private val dao get() = database.bookDao()

    override fun observeShelf(hidden: Boolean): Flow<List<ShelfItem>> =
        dao.observeShelf(hidden)
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(dispatchers.io)

    override fun observeBook(bookId: String): Flow<Book?> =
        dao.observeBook(bookId)
            .map { it?.toDomain() }
            .flowOn(dispatchers.io)

    override suspend fun getBook(bookId: String): Book? = withContext(dispatchers.io) {
        dao.getBook(bookId)?.toDomain()
    }

    override suspend fun findBookByHash(contentHash: String): Book? = withContext(dispatchers.io) {
        dao.findByHash(contentHash)?.toDomain()
    }

    override suspend fun saveImportedBook(
        book: Book,
        chapters: List<Chapter>,
        initialProgress: ReadingProgress,
    ) = withContext(dispatchers.io) {
        database.withTransaction {
            dao.insertBook(book.toEntity())
            dao.insertChapters(chapters.map { it.toEntity(book.id) })
            val contents = chapters
                .filter { !it.isPdfPage && it.text.isNotEmpty() }
                .map { ChapterContentEntity(book.id, it.index, it.text) }
            if (contents.isNotEmpty()) {
                dao.insertChapterContents(contents)
            }
            dao.upsertProgress(
                ReadingProgressEntity(
                    bookId = book.id,
                    chapterIndex = initialProgress.chapterIndex,
                    charOffset = initialProgress.charOffsetInChapter,
                    percent = initialProgress.percent,
                    updatedAt = initialProgress.updatedAt,
                ),
            )
        }
    }

    override suspend fun setHidden(bookId: String, hidden: Boolean) = withContext(dispatchers.io) {
        dao.setHidden(bookId, hidden)
        Unit
    }

    override suspend fun deleteBook(bookId: String) = withContext(dispatchers.io) {
        dao.deleteBook(bookId)
        runCatching { fileStore.deleteBookFiles(bookId) }
        Unit
    }

    override suspend fun deleteAllHidden(): Int = withContext(dispatchers.io) {
        val hiddenBooks = dao.getHiddenBooks()
        val deleted = dao.deleteAllHidden()
        hiddenBooks.forEach { book ->
            runCatching { fileStore.deleteBookFiles(book.id) }
        }
        deleted
    }

    override suspend fun updateLastOpened(bookId: String, timestamp: Long) =
        withContext(dispatchers.io) {
            dao.updateLastOpened(bookId, timestamp)
            Unit
        }

    override suspend fun getChapters(bookId: String): List<Chapter> = withContext(dispatchers.io) {
        dao.getChapters(bookId).map { entity ->
            Chapter(
                index = entity.chapterIndex,
                title = entity.title,
                charStart = entity.charStart,
                charCount = entity.charCount,
                text = "",
                pdfPageIndex = entity.pdfPageIndex,
            )
        }
    }

    override suspend fun getChaptersWithText(bookId: String): List<Chapter> =
        withContext(dispatchers.io) {
            dao.getChaptersWithText(bookId).map { it.toDomain() }
        }

    override suspend fun getProgress(bookId: String): ReadingProgress? =
        withContext(dispatchers.io) {
            dao.getProgress(bookId)?.toDomain()
        }

    override suspend fun saveProgress(bookId: String, progress: ReadingProgress) =
        withContext(dispatchers.io) {
            dao.upsertProgress(
                ReadingProgressEntity(
                    bookId = bookId,
                    chapterIndex = progress.chapterIndex,
                    charOffset = progress.charOffsetInChapter,
                    percent = progress.percent,
                    updatedAt = progress.updatedAt,
                ),
            )
        }

    private fun Book.toEntity(): BookEntity = BookEntity(
        id = id,
        title = title,
        author = author,
        format = format.name,
        sourcePath = sourcePath,
        coverPath = coverPath,
        contentHash = contentHash,
        fileSize = fileSize,
        hidden = hidden,
        chapterCount = chapterCount,
        totalChars = totalChars,
        isScannedPdf = isScannedPdf,
        importedAt = importedAt,
        lastOpenedAt = lastOpenedAt,
    )

    private fun Chapter.toEntity(bookId: String): ChapterEntity = ChapterEntity(
        bookId = bookId,
        chapterIndex = index,
        title = title,
        charStart = charStart,
        charCount = charCount,
        pdfPageIndex = pdfPageIndex,
    )

    /** 当前时间由注入的 Clock 提供；保留给将来的 lastOpened 自动更新。 */
    fun now(): Long = clock.nowMillis()
}
