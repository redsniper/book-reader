package com.moyue.reader.domain.reader

import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.FontFamilyOption
import com.moyue.reader.domain.model.ReadingAnchor
import com.moyue.reader.domain.service.ChapterSplitter
import kotlinx.coroutines.yield

/** 一页在章节正文中的 UTF-16 字符区间，[start, end)。 */
data class PageRange(val start: Int, val end: Int)

/**
 * 分页测量所需的全部尺寸参数（单位均为像素）。
 *
 * [chapterTitleHeightPx] 是章节首页为标题预留的高度；其余页使用完整 [heightPx]。
 */
data class PageLayoutSpec(
    val widthPx: Int,
    val heightPx: Int,
    val fontFamily: FontFamilyOption,
    val fontSizePx: Float,
    val lineHeightPx: Float,
    val chapterTitleHeightPx: Int,
)

/**
 * 章节正文分页器接口。
 *
 * 正式实现使用 Android StaticLayout 测量文字；测试可注入确定性的假实现。
 */
interface PageBreaker {
    fun breakChapter(title: String, text: String, spec: PageLayoutSpec): List<PageRange>
}

/**
 * 全书页索引中的一页。
 *
 * 文本格式：start/end 为章节内字符区间；
 * 扫描版 PDF：text 为空，pdfPageIndex 指向原始 PDF 页。
 */
data class PageInfo(
    val chapterIndex: Int,
    val start: Int,
    val end: Int,
    val isChapterStart: Boolean,
    val pdfPageIndex: Int? = null,
) {
    val pageKey: String get() = "$chapterIndex:$start"
}

/**
 * 全书页索引。
 *
 * 提供：
 * - 字符锚点 → 页码（排版变化后恢复进度）；
 * - 章节 → 首页码（目录跳转）；
 * - 页码 → 进度百分比；
 * - 页码 → 字符锚点（保存进度）。
 */
class ReaderPageIndex(
    val pages: List<PageInfo>,
    val chapters: List<Chapter>,
) {
    private val chapterFirstPage: IntArray = IntArray(chapters.size) { -1 }
    private val chapterPageCounts: IntArray = IntArray(chapters.size)

    init {
        pages.forEachIndexed { pageIndex, page ->
            if (page.chapterIndex in chapters.indices) {
                if (chapterFirstPage[page.chapterIndex] < 0) {
                    chapterFirstPage[page.chapterIndex] = pageIndex
                }
                chapterPageCounts[page.chapterIndex]++
            }
        }
    }

    val totalPages: Int get() = pages.size
    val isEmpty: Boolean get() = pages.isEmpty()

    fun pageForChapter(chapterIndex: Int): Int {
        if (pages.isEmpty()) return 0
        val safeIndex = chapterIndex.coerceIn(0, chapters.lastIndex)
        val first = chapterFirstPage[safeIndex]
        return if (first >= 0) first else pages.lastIndex
    }

    fun pageCountForChapter(chapterIndex: Int): Int {
        if (chapterIndex !in chapters.indices) return 0
        return chapterPageCounts[chapterIndex]
    }

    fun chapterIndexForPage(page: Int): Int {
        if (pages.isEmpty()) return 0
        val safePage = page.coerceIn(0, pages.lastIndex)
        return pages[safePage].chapterIndex
    }

    /**
     * 根据阅读锚点定位页码。
     *
     * 文本格式使用二分查找找到第一个 end > offset 的页；
     * 扫描版 PDF 按 pdfPageIndex 定位。
     */
    fun pageForAnchor(anchor: ReadingAnchor): Int {
        if (pages.isEmpty()) return 0

        // 扫描版 PDF：优先按原始页定位。
        if (anchor.pdfPageIndex != null) {
            val byPage = pages.indexOfFirst { it.pdfPageIndex == anchor.pdfPageIndex }
            if (byPage >= 0) return byPage
        }

        val chapterIndex = anchor.chapterIndex.coerceIn(0, chapters.lastIndex)
        val first = chapterFirstPage[chapterIndex]
        if (first < 0) return pages.lastIndex
        val count = chapterPageCounts[chapterIndex]
        if (count <= 0) return first
        if (count == 1) return first

        val offset = anchor.charOffsetInChapter.coerceAtLeast(0)
        var low = first
        var high = first + count - 1
        var answer = high
        while (low <= high) {
            val mid = (low + high) ushr 1
            val page = pages[mid]
            if (page.end > offset) {
                answer = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return answer
    }

    /** 页码 → 阅读锚点。 */
    fun anchorForPage(page: Int): ReadingAnchor {
        if (pages.isEmpty()) {
            return ReadingAnchor(
                chapterIndex = 0,
                charOffsetInChapter = 0,
                pdfPageIndex = chapters.firstOrNull()?.pdfPageIndex,
            )
        }
        val safePage = page.coerceIn(0, pages.lastIndex)
        val info = pages[safePage]
        return ReadingAnchor(
            chapterIndex = info.chapterIndex,
            charOffsetInChapter = info.start,
            pdfPageIndex = info.pdfPageIndex,
        )
    }

    /**
     * 页码 → 进度（0..1）。
     *
     * 最后一页视为 100%；扫描版 PDF 以页序计算；文本以全书字符累计位置计算。
     */
    fun progressPercent(page: Int): Float {
        if (pages.isEmpty()) return 0f
        if (page >= pages.lastIndex) return 1f
        val info = pages[page.coerceIn(0, pages.lastIndex)]
        val chapter = chapters.getOrNull(info.chapterIndex) ?: return 0f

        if (chapters.all { it.charCount == 0 }) {
            // 扫描版：阅读到第 N 页时，进度按 (N / 总页数) 计。
            return ((info.pdfPageIndex ?: info.chapterIndex) + 1).toFloat() / pages.size
        }

        val totalChars = chapters.sumOf { it.charCount }
        if (totalChars <= 0) return 0f
        val absolute = chapter.charStart + info.start.coerceIn(0, chapter.charCount)
        return (absolute.toFloat() / totalChars).coerceIn(0f, 1f)
    }

    /** 页码进度，按四舍五入输出整数百分比（供 UI 使用）。 */
    fun percentInt(page: Int): Int = (progressPercent(page) * 100).toInt().coerceIn(0, 100)
}

/**
 * 全书页索引构建器。
 *
 * 文本格式逐章调用 [PageBreaker]，扫描版 PDF 直接一章一页。
 * 构建过程为挂起函数，并按章让出线程，避免大书长时间占用单一协程。
 *
 * 注意：修改页区间拼接/空章节占位等逻辑时必须递增 `PAGE_INDEX_ENGINE_VERSION`，
 * 使旧缓存失效。
 */
class PageIndexBuilder(private val breaker: PageBreaker) {

    suspend fun build(
        chapters: List<Chapter>,
        spec: PageLayoutSpec,
        onProgress: (Float) -> Unit = {},
    ): ReaderPageIndex {
        if (chapters.isEmpty()) {
            return ReaderPageIndex(emptyList(), emptyList())
        }

        val isScannedPdf = chapters.all { it.isPdfPage }
        if (isScannedPdf) {
            val pages = chapters.mapIndexed { index, chapter ->
                PageInfo(
                    chapterIndex = index,
                    start = 0,
                    end = 0,
                    isChapterStart = true,
                    pdfPageIndex = chapter.pdfPageIndex ?: index,
                )
            }
            onProgress(1f)
            return ReaderPageIndex(pages, chapters)
        }

        val pages = ArrayList<PageInfo>(chapters.size * 2)
        chapters.forEachIndexed { chapterIndex, chapter ->
            val ranges = if (chapter.text.isEmpty()) {
                listOf(PageRange(0, 0))
            } else {
                breaker.breakChapter(chapter.title, chapter.text, spec)
                    .ifEmpty { listOf(PageRange(0, chapter.text.length)) }
            }
            ranges.forEachIndexed { rangeIndex, range ->
                val start = range.start.coerceIn(0, chapter.text.length)
                val end = range.end.coerceIn(start, chapter.text.length)
                pages.add(
                    PageInfo(
                        chapterIndex = chapterIndex,
                        start = start,
                        end = end,
                        isChapterStart = rangeIndex == 0,
                        pdfPageIndex = null,
                    ),
                )
            }
            onProgress((chapterIndex + 1).toFloat() / chapters.size)
            yield()
        }
        return ReaderPageIndex(pages, chapters)
    }
}

/** 空文本书籍至少生成一页，避免阅读器出现零页状态。 */
fun List<ChapterSplitter.SplitChapter>.toChapters(): List<Chapter> {
    var cursor = 0
    return mapIndexed { index, split ->
        val chapter = Chapter(
            index = index,
            title = split.title,
            charStart = cursor,
            charCount = split.text.length,
            text = split.text,
        )
        cursor += split.text.length
        chapter
    }
}
