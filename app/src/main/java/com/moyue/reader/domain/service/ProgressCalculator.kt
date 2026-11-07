package com.moyue.reader.domain.service

import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.ReadingAnchor
import kotlin.math.roundToInt

/**
 * 阅读进度换算。
 *
 * 文本格式使用"全书累计字符位置 / 总字符数"，扫描版 PDF 使用页序。
 * 与具体字号、行距、屏幕尺寸无关，因此排版变化后进度不会跳变。
 */
class ProgressCalculator {

    fun percent(anchor: ReadingAnchor, chapters: List<Chapter>): Float {
        if (chapters.isEmpty()) return 0f
        val chapterIndex = anchor.chapterIndex.coerceIn(0, chapters.lastIndex)

        if (chapters.all { it.charCount == 0 }) {
            return ((chapterIndex + 1).toFloat() / chapters.size).coerceIn(0f, 1f)
        }

        val totalChars = chapters.sumOf { it.charCount }
        if (totalChars <= 0) return 0f
        val chapter = chapters[chapterIndex]
        val offset = anchor.charOffsetInChapter.coerceIn(0, chapter.charCount)
        val absolute = chapter.charStart + offset
        return (absolute.toFloat() / totalChars).coerceIn(0f, 1f)
    }

    /** 百分比 → 最接近的字符锚点，用于章节结构变化后的兜底恢复。 */
    fun anchorForPercent(percent: Float, chapters: List<Chapter>): ReadingAnchor {
        if (chapters.isEmpty()) return ReadingAnchor(0, 0)
        val safePercent = percent.coerceIn(0f, 1f)

        if (chapters.all { it.charCount == 0 }) {
            val index = (safePercent * chapters.size).toInt().coerceIn(0, chapters.lastIndex)
            return ReadingAnchor(
                chapterIndex = index,
                charOffsetInChapter = 0,
                pdfPageIndex = chapters[index].pdfPageIndex,
            )
        }

        val totalChars = chapters.sumOf { it.charCount }
        if (totalChars <= 0) return ReadingAnchor(0, 0, chapters.firstOrNull()?.pdfPageIndex)
        val target = (safePercent * totalChars).roundToInt().coerceIn(0, totalChars - 1)
        val chapterIndex = chapters.indexOfLast { it.charStart <= target }.coerceAtLeast(0)
        val chapter = chapters[chapterIndex]
        return ReadingAnchor(
            chapterIndex = chapterIndex,
            charOffsetInChapter = (target - chapter.charStart).coerceIn(0, chapter.charCount),
            pdfPageIndex = chapter.pdfPageIndex,
        )
    }
}
