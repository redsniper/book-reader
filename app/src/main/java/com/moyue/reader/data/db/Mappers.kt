package com.moyue.reader.data.db

import com.moyue.reader.domain.model.Book
import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.ReadingProgress
import com.moyue.reader.domain.model.ShelfItem

/** 实体与领域模型之间的映射，集中放置避免各仓库重复转换逻辑。 */

fun BookEntity.toDomain(): Book = Book(
    id = id,
    title = title,
    author = author,
    format = runCatching { BookFormat.valueOf(format) }.getOrDefault(BookFormat.TXT),
    sourcePath = sourcePath,
    contentHash = contentHash,
    coverPath = coverPath,
    fileSize = fileSize,
    hidden = hidden,
    chapterCount = chapterCount,
    totalChars = totalChars,
    isScannedPdf = isScannedPdf,
    importedAt = importedAt,
    lastOpenedAt = lastOpenedAt,
)

fun ReadingProgressEntity.toDomain(): ReadingProgress = ReadingProgress(
    chapterIndex = chapterIndex,
    charOffsetInChapter = charOffset,
    percent = percent,
    updatedAt = updatedAt,
)

fun ShelfBookRow.toDomain(): ShelfItem = ShelfItem(
    book = Book(
        id = id,
        title = title,
        author = author,
        format = runCatching { BookFormat.valueOf(format) }.getOrDefault(BookFormat.TXT),
        sourcePath = sourcePath,
        contentHash = contentHash,
        coverPath = coverPath,
        fileSize = fileSize,
        hidden = hidden,
        chapterCount = chapterCount,
        totalChars = totalChars,
        isScannedPdf = isScannedPdf,
        importedAt = importedAt,
        lastOpenedAt = lastOpenedAt,
    ),
    progress = progressChapterIndex?.let { chapterIndex ->
        ReadingProgress(
            chapterIndex = chapterIndex,
            charOffsetInChapter = progressCharOffset ?: 0,
            percent = progressPercent ?: 0f,
            updatedAt = progressUpdatedAt ?: importedAt,
        )
    },
    currentChapterTitle = currentChapterTitle,
)

fun ChapterWithText.toDomain(): Chapter = Chapter(
    index = chapterIndex,
    title = title,
    charStart = charStart,
    charCount = charCount,
    text = text.orEmpty(),
    pdfPageIndex = pdfPageIndex,
)
