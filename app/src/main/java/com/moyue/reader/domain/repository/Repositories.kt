package com.moyue.reader.domain.repository

import com.moyue.reader.domain.model.Book
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.ReaderSettings
import com.moyue.reader.domain.model.ReadingProgress
import com.moyue.reader.domain.model.ShelfItem
import kotlinx.coroutines.flow.Flow

/**
 * 书架相关仓库。
 *
 * 普通书架与隐藏书架共用同一数据源，通过 hidden 参数隔离：
 * 普通书架查询 hidden=false，隐藏书架查询 hidden=true。
 */
interface ShelfRepository {
    fun observeShelf(hidden: Boolean): Flow<List<ShelfItem>>

    fun observeBook(bookId: String): Flow<Book?>

    suspend fun getBook(bookId: String): Book?

    /** 以文件 SHA-256 查重，包含隐藏书架中的书。 */
    suspend fun findBookByHash(contentHash: String): Book?

    /** 保存导入结果（书籍 + 章节 + 初始进度）为一个事务。 */
    suspend fun saveImportedBook(
        book: Book,
        chapters: List<Chapter>,
        initialProgress: ReadingProgress,
    )

    suspend fun setHidden(bookId: String, hidden: Boolean)

    /** 删除书籍及其章节、进度和私有文件。 */
    suspend fun deleteBook(bookId: String)

    /** 清空隐藏书架，返回删除数量。 */
    suspend fun deleteAllHidden(): Int

    suspend fun updateLastOpened(bookId: String, timestamp: Long)
}

/** 阅读正文与进度仓库。 */
interface ReadingRepository {
    /** 只读取章节元数据，供目录使用。 */
    suspend fun getChapters(bookId: String): List<Chapter>

    /** 读取全书章节（含正文），供分页与渲染使用。 */
    suspend fun getChaptersWithText(bookId: String): List<Chapter>

    suspend fun getProgress(bookId: String): ReadingProgress?

    suspend fun saveProgress(bookId: String, progress: ReadingProgress)
}

/** 阅读设置仓库（DataStore 实现）。 */
interface ReadingSettingsRepository {
    val settings: Flow<ReaderSettings>

    suspend fun update(settings: ReaderSettings)
}
