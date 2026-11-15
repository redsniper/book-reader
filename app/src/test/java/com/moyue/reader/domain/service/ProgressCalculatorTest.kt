package com.moyue.reader.domain.service

import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.ReadingAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressCalculatorTest {

    private val calculator = ProgressCalculator()
    private val chapters = listOf(
        Chapter(0, "一", charStart = 0, charCount = 100, text = ""),
        Chapter(1, "二", charStart = 100, charCount = 100, text = ""),
        Chapter(2, "三", charStart = 200, charCount = 50, text = ""),
    )

    @Test
    fun `percent at chapter boundaries`() {
        assertEquals(0f, calculator.percent(ReadingAnchor(0, 0), chapters), 0.0001f)
        assertEquals(0.4f, calculator.percent(ReadingAnchor(0, 100), chapters), 0.0001f)
        assertEquals(0.6f, calculator.percent(ReadingAnchor(1, 50), chapters), 0.0001f)
        assertEquals(0.94f, calculator.percent(ReadingAnchor(2, 35), chapters), 0.0001f)
    }

    @Test
    fun `percent clamps out of range`() {
        assertEquals(0f, calculator.percent(ReadingAnchor(-1, -10), chapters), 0.0001f)
        assertEquals(1f, calculator.percent(ReadingAnchor(9, 9999), chapters), 0.0001f)
    }

    @Test
    fun `anchor for percent round trips`() {
        listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.99f).forEach { percent ->
            val anchor = calculator.anchorForPercent(percent, chapters)
            assertEquals(percent, calculator.percent(anchor, chapters), 0.006f)
        }
    }

    @Test
    fun `scanned chapters use page progress`() {
        val scanned = (0 until 4).map { Chapter(it, "第${it + 1}页", 0, 0, "", pdfPageIndex = it) }
        assertEquals(0.25f, calculator.percent(ReadingAnchor(0, 0, 0), scanned), 0.0001f)
        assertEquals(1f, calculator.percent(ReadingAnchor(3, 0, 3), scanned), 0.0001f)
        val anchor = calculator.anchorForPercent(0.6f, scanned)
        assertEquals(2, anchor.chapterIndex)
        assertEquals(2, anchor.pdfPageIndex)
    }

    @Test
    fun `empty chapters yield zero`() {
        assertEquals(0f, calculator.percent(ReadingAnchor(0, 0), emptyList()), 0.0001f)
    }
}
