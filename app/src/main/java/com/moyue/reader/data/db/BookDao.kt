package com.moyue.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 书籍数据库访问对象。
 *
 * 设计要点：
 * - 普通/隐藏书架共用查询，通过 hidden 参数过滤，避免遗漏隐藏标记；
 * - 书架使用 LEFT JOIN 一次取到进度与当前章节标题，避免 N+1；
 * - 删除书籍依赖外键级联清理章节、正文、进度。
 */
@Dao
interface BookDao {

    @Query(
        """
        SELECT b.id, b.title, b.author, b.format, b.source_path, b.cover_path, b.content_hash,
               b.file_size, b.hidden, b.chapter_count, b.total_chars, b.is_scanned_pdf,
               b.imported_at, b.last_opened_at,
               p.chapter_index AS progress_chapter_index,
               p.char_offset AS progress_char_offset,
               p.percent AS progress_percent,
               p.updated_at AS progress_updated_at,
               c.title AS current_chapter_title
        FROM books b
        LEFT JOIN reading_progress p ON p.book_id = b.id
        LEFT JOIN chapters c ON c.book_id = b.id AND c.chapter_index = p.chapter_index
        WHERE b.hidden = :hidden
        ORDER BY COALESCE(p.updated_at, b.last_opened_at, b.imported_at) DESC
        """,
    )
    fun observeShelf(hidden: Boolean): Flow<List<ShelfBookRow>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun observeBook(bookId: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBook(bookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE content_hash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): BookEntity?

    @Query("SELECT * FROM books WHERE hidden = 1 ORDER BY imported_at DESC")
    suspend fun getHiddenBooks(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChapterContents(contents: List<ChapterContentEntity>)

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY chapter_index ASC")
    suspend fun getChapters(bookId: String): List<ChapterEntity>

    @Query(
        """
        SELECT c.book_id, c.chapter_index, c.title, c.char_start, c.char_count,
               c.pdf_page_index, ct.text AS text
        FROM chapters c
        LEFT JOIN chapter_contents ct
          ON ct.book_id = c.book_id AND ct.chapter_index = c.chapter_index
        WHERE c.book_id = :bookId
        ORDER BY c.chapter_index ASC
        """,
    )
    suspend fun getChaptersWithText(bookId: String): List<ChapterWithText>

    @Query("SELECT * FROM reading_progress WHERE book_id = :bookId")
    suspend fun getProgress(bookId: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: ReadingProgressEntity)

    @Query("UPDATE books SET last_opened_at = :timestamp WHERE id = :bookId")
    suspend fun updateLastOpened(bookId: String, timestamp: Long): Int

    @Query("UPDATE books SET hidden = :hidden WHERE id = :bookId")
    suspend fun setHidden(bookId: String, hidden: Boolean): Int

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: String): Int

    @Query("DELETE FROM books WHERE hidden = 1")
    suspend fun deleteAllHidden(): Int
}
