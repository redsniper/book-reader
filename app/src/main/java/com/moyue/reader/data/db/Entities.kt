package com.moyue.reader.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 书籍元数据表。正文不在此表，避免书架查询加载大文本。 */
@Entity(
    tableName = "books",
    indices = [
        Index(value = ["content_hash"], unique = true),
        Index(value = ["hidden", "last_opened_at"]),
    ],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val format: String,
    @ColumnInfo(name = "source_path") val sourcePath: String,
    @ColumnInfo(name = "cover_path") val coverPath: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    val hidden: Boolean,
    @ColumnInfo(name = "chapter_count") val chapterCount: Int,
    @ColumnInfo(name = "total_chars") val totalChars: Int,
    @ColumnInfo(name = "is_scanned_pdf") val isScannedPdf: Boolean,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long?,
)

/** 章节目录表：只存元信息，支持快速构建目录与进度换算。 */
@Entity(
    tableName = "chapters",
    primaryKeys = ["book_id", "chapter_index"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["book_id"])],
)
data class ChapterEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    val title: String,
    @ColumnInfo(name = "char_start") val charStart: Int,
    @ColumnInfo(name = "char_count") val charCount: Int,
    @ColumnInfo(name = "pdf_page_index") val pdfPageIndex: Int?,
)

/** 章节正文表：阅读时按章加载；扫描版 PDF 没有正文行。 */
@Entity(
    tableName = "chapter_contents",
    primaryKeys = ["book_id", "chapter_index"],
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["book_id", "chapter_index"],
            childColumns = ["book_id", "chapter_index"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChapterContentEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    val text: String,
)

/** 每本书一条阅读进度。 */
@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadingProgressEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "char_offset") val charOffset: Int,
    val percent: Float,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * 书架投影：书籍 + 进度 + 当前章节标题。
 *
 * 使用扁平投影而不是嵌套 @Embedded，避免 LEFT JOIN 空值映射问题。
 */
data class ShelfBookRow(
    val id: String,
    val title: String,
    val author: String?,
    val format: String,
    @ColumnInfo(name = "source_path") val sourcePath: String,
    @ColumnInfo(name = "cover_path") val coverPath: String?,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    val hidden: Boolean,
    @ColumnInfo(name = "chapter_count") val chapterCount: Int,
    @ColumnInfo(name = "total_chars") val totalChars: Int,
    @ColumnInfo(name = "is_scanned_pdf") val isScannedPdf: Boolean,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long?,
    @ColumnInfo(name = "progress_chapter_index") val progressChapterIndex: Int?,
    @ColumnInfo(name = "progress_char_offset") val progressCharOffset: Int?,
    @ColumnInfo(name = "progress_percent") val progressPercent: Float?,
    @ColumnInfo(name = "progress_updated_at") val progressUpdatedAt: Long?,
    @ColumnInfo(name = "current_chapter_title") val currentChapterTitle: String?,
)

/** 章节 + 正文的联表投影。 */
data class ChapterWithText(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    val title: String,
    @ColumnInfo(name = "char_start") val charStart: Int,
    @ColumnInfo(name = "char_count") val charCount: Int,
    @ColumnInfo(name = "pdf_page_index") val pdfPageIndex: Int?,
    val text: String?,
)
