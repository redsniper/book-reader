package com.moyue.reader.platform.pagination

import com.moyue.reader.domain.model.FontFamilyOption
import com.moyue.reader.domain.reader.PageLayoutSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidPageBreakerTest {

    private val breaker = AndroidPageBreaker()

    private fun spec(fontSize: Float = 16f) = PageLayoutSpec(
        widthPx = 360,
        heightPx = 400,
        fontFamily = FontFamilyOption.SERIF,
        fontSizePx = fontSize,
        lineHeightPx = fontSize * 1.8f,
        chapterTitleHeightPx = 60,
    )

    @Test
    fun `pages cover the whole chapter without gaps`() {
        val text = (1..200).joinToString("\n") {
            "这是第 ${it} 行测试文字，用来验证中文分页的行边界与覆盖率。"
        }

        val pages = breaker.breakChapter("第一章", text, spec())

        assertTrue("应分为多页", pages.size > 1)
        assertEquals(0, pages.first().start)
        assertEquals(text.length, pages.last().end)
        pages.zipWithNext().forEach { (first, second) ->
            assertEquals("页区间必须首尾相接", first.end, second.start)
        }
        assertTrue(pages.all { it.end > it.start })
    }

    @Test
    fun `larger font produces at least as many pages`() {
        val text = (1..100).joinToString("\n") { "行 $it，字号测试。" }
        val small = breaker.breakChapter("第一章", text, spec(fontSize = 14f))
        val large = breaker.breakChapter("第一章", text, spec(fontSize = 26f))
        assertTrue(large.size >= small.size)
    }

    @Test
    fun `empty text yields a single empty page`() {
        val pages = breaker.breakChapter("空章", "", spec())
        assertEquals(1, pages.size)
        assertEquals(0, pages[0].start)
        assertEquals(0, pages[0].end)
    }

    @Test
    fun `page does not split a surrogate pair`() {
        val text = "😀".repeat(1_000)
        val pages = breaker.breakChapter("表情", text, spec(fontSize = 20f))
        pages.forEach { page ->
            if (page.end < text.length) {
                val ending = text[page.end - 1]
                val next = text[page.end]
                assertTrue(
                    "页边界不应落在 UTF-16 代理对中间",
                    !(Character.isHighSurrogate(ending) && Character.isLowSurrogate(next)),
                )
            }
        }
    }
}
