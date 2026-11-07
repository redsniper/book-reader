package com.moyue.reader.domain.model

/**
 * 书架/阅读器使用的书籍聚合。
 *
 * sourcePath、coverPath 均为相对于应用 filesDir 的路径，例如
 * "books/{uuid}/source.txt"。这样数据库不依赖机器的绝对路径。
 */
data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val format: BookFormat,
    val sourcePath: String,
    val contentHash: String,
    val coverPath: String?,
    val fileSize: Long,
    val hidden: Boolean,
    val chapterCount: Int,
    val totalChars: Int,
    val isScannedPdf: Boolean,
    val importedAt: Long,
    val lastOpenedAt: Long?,
)

/**
 * 章节。
 *
 * 文本格式：text 为章节正文，charStart/charCount 用于全书进度计算；
 * 扫描版 PDF：text 为空，pdfPageIndex 指向原始 PDF 页（从 0 开始）。
 */
data class Chapter(
    val index: Int,
    val title: String,
    val charStart: Int,
    val charCount: Int,
    val text: String,
    val pdfPageIndex: Int? = null,
) {
    /** 扫描版 PDF 的章节没有可重排文字。 */
    val isPdfPage: Boolean get() = pdfPageIndex != null
}

/** 阅读进度：以章节内字符锚点保存，排版变化后仍可定位。 */
data class ReadingProgress(
    val chapterIndex: Int,
    val charOffsetInChapter: Int,
    val percent: Float,
    val updatedAt: Long,
) {
    companion object {
        fun initial(now: Long): ReadingProgress = ReadingProgress(
            chapterIndex = 0,
            charOffsetInChapter = 0,
            percent = 0f,
            updatedAt = now,
        )
    }
}

/** 阅读位置锚点，不包含百分比与时间，便于分页重排时复用。 */
data class ReadingAnchor(
    val chapterIndex: Int,
    val charOffsetInChapter: Int,
    val pdfPageIndex: Int? = null,
)

/** 书架列表项：书籍 + 进度 + 当前章节标题（由查询直接 JOIN 得到，避免 N+1）。 */
data class ShelfItem(
    val book: Book,
    val progress: ReadingProgress?,
    val currentChapterTitle: String?,
)
