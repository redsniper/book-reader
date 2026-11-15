package com.moyue.reader.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSplitterTest {

    private val splitter = ChapterSplitter()

    @Test
    fun `splits chinese headings and keeps preface`() {
        val text = """
            剑来
            作者：烽火戏诸侯

            第一章 初见
            山里的风很轻。

            第二章 再会
            他下山了。
        """.trimIndent()

        val chapters = splitter.split(text)

        assertEquals(listOf("前言", "第一章 初见", "第二章 再会"), chapters.map { it.title })
        assertTrue(chapters[0].text.contains("剑来"))
        assertEquals("山里的风很轻。", chapters[1].text)
        assertEquals("他下山了。", chapters[2].text)
    }

    @Test
    fun `recognizes special english numbered and markdown headings`() {
        val text = """
            楔子
            旧事。

            Chapter 1: The Road
            A road.

            2、少年的选择
            B choice.

            ## 山中岁月
            C time.
        """.trimIndent()

        val chapters = splitter.split(text)

        assertEquals(
            listOf("楔子", "Chapter 1: The Road", "2、少年的选择", "山中岁月"),
            chapters.map { it.title },
        )
    }

    @Test
    fun `does not split heading-like text inside a sentence`() {
        val text = """
            第一章 初见
            他说这是第一章 的内容，不该被切开。
            第二天他又说第二章 也一样。
        """.trimIndent()

        val chapters = splitter.split(text)

        assertEquals(1, chapters.size)
        assertEquals("第一章 初见", chapters[0].title)
    }

    @Test
    fun `falls back to chunks when there is no heading`() {
        val paragraph = "这是一个很长的段落，用来验证无标题文本的回退分节。"
        val text = (1..1_000).joinToString("\n\n") { "$paragraph 第${it}段。" }

        val chapters = splitter.split(text)

        assertTrue("应产生多个回退章节", chapters.size > 1)
        assertTrue(chapters.all { it.title.startsWith("第 ") && it.title.endsWith(" 节") })
        assertTrue(chapters.all { it.text.isNotBlank() })
        assertTrue(chapters.all { it.text.length <= ChapterSplitter.DEFAULT_CHUNK_SIZE + 10 })
    }

    @Test
    fun `falls back to one chapter for short untitled text`() {
        val chapters = splitter.split("只有一段话，没有章节标题。")
        assertEquals(1, chapters.size)
        assertEquals("正文", chapters[0].title)
        assertEquals("只有一段话，没有章节标题。", chapters[0].text)
    }

    @Test
    fun `skips empty chapters and handles heading-only text`() {
        val text = """
            第一章 只有标题

            第二章 也没有正文
        """.trimIndent()

        val chapters = splitter.split(text)

        // 所有章节都为空时回退为整篇正文，而不是返回空列表。
        assertTrue(chapters.isNotEmpty())
    }

    @Test
    fun `limits chapters by merging tail`() {
        val limited = ChapterSplitter(maxChapters = 3)
        val text = (1..5).joinToString("\n\n") { index ->
            "第${index}章 标题$index\n内容$index"
        }

        val chapters = limited.split(text)

        assertEquals(3, chapters.size)
        assertEquals("续篇", chapters.last().title)
        assertTrue(chapters.last().text.contains("内容5"))
    }

    @Test
    fun `returns empty list for blank input`() {
        assertTrue(splitter.split("   \n\n  ").isEmpty())
    }
}
