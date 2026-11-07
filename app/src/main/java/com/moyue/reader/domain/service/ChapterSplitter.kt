package com.moyue.reader.domain.service

import kotlin.math.min

/**
 * 文字内容分章器。
 *
 * 策略：
 * 1. 扫描行，识别中文章节标题、特殊篇名、英文章节、编号小节、Markdown 标题；
 * 2. 首个标题前的内容归入"前言"；
 * 3. 若完全没有标题，则按约 [fallbackChunkSize] 字符在段落/句子边界回退分节；
 * 4. 空章节丢弃，章节数上限 [maxChapters]，防止异常文件撑爆目录。
 *
 * 该组件不依赖 Android，可纯 JVM 单元测试。
 */
class ChapterSplitter(
    private val fallbackChunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val maxChapters: Int = MAX_CHAPTERS,
) {

    data class SplitChapter(val title: String, val text: String)

    fun split(input: String): List<SplitChapter> {
        val text = input.trim()
        if (text.isEmpty()) return emptyList()

        val lines = text.lines()
        val headingLines = mutableListOf<Int>()
        var previousLineBlank = true
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (isChapterHeading(line, previousLineBlank)) {
                headingLines.add(index)
            }
            previousLineBlank = line.isEmpty()
        }

        if (headingLines.isEmpty()) {
            return fallbackChunk(text)
        }

        val chapters = mutableListOf<SplitChapter>()

        // 第一个标题前的内容：作为"前言"（常见于书名、作者、简介）。
        val firstHeading = headingLines.first()
        if (firstHeading > 0) {
            val preface = lines.subList(0, firstHeading).joinToString("\n").trim()
            if (preface.isNotEmpty()) {
                chapters.add(SplitChapter("前言", preface))
            }
        }

        headingLines.forEachIndexed { position, headingLine ->
            val title = cleanHeading(lines[headingLine])
            val bodyStart = headingLine + 1
            val bodyEnd = if (position + 1 < headingLines.size) headingLines[position + 1] else lines.size
            val body = lines.subList(bodyStart, bodyEnd).joinToString("\n").trim()
            // 只有标题没有正文的空章节跳过，避免目录出现大量空页。
            if (body.isNotEmpty()) {
                chapters.add(SplitChapter(title, body))
            }
        }

        if (chapters.isEmpty()) return fallbackChunk(text)
        return limitChapters(chapters)
    }

    private fun isChapterHeading(line: String, previousLineBlank: Boolean): Boolean {
        if (line.isEmpty() || line.length > MAX_HEADING_LENGTH) return false
        val stripped = line.removePrefix("\u3000").removePrefix("　").trim()
        if (stripped.isEmpty() || stripped.length > MAX_HEADING_LENGTH) return false

        if (CHINESE_HEADING.matches(stripped)) return true
        if (SPECIAL_HEADING.matches(stripped)) return true
        if (ENGLISH_HEADING.matches(stripped)) return true
        if (MARKDOWN_HEADING.matches(stripped)) return true
        // 纯数字编号误判风险较高，要求前一行是空行且整行较短。
        if (previousLineBlank && NUMBERED_HEADING.matches(stripped)) return true
        return false
    }

    private fun cleanHeading(line: String): String {
        return line.trim()
            .removePrefix("\u3000")
            .trim()
            .replace(Regex("^#{1,3}\\s+"), "")
            .trim()
            .take(MAX_HEADING_LENGTH)
            .ifBlank { "未命名章节" }
    }

    /**
     * 无标题时回退分节。
     *
     * 优先按空行分段；没有空行时按单行段落处理。超长段落按句末标点切分，
     * 保证不会截断在 UTF-16 代理对中间。
     */
    private fun fallbackChunk(text: String): List<SplitChapter> {
        val paragraphs = splitParagraphs(text)
        val pieces = mutableListOf<String>()
        paragraphs.forEach { paragraph ->
            if (paragraph.length <= LONG_PARAGRAPH_THRESHOLD) {
                pieces.add(paragraph)
            } else {
                pieces.addAll(splitLongParagraph(paragraph))
            }
        }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (piece.isBlank()) continue
            val extra = if (current.isEmpty()) piece.length else piece.length + 2
            if (current.isNotEmpty() && current.length + extra > fallbackChunkSize) {
                chunks.add(current.toString().trim())
                current.clear()
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(piece.trim())
        }
        if (current.isNotEmpty()) chunks.add(current.toString().trim())

        if (chunks.isEmpty()) return emptyList()
        if (chunks.size == 1) return listOf(SplitChapter("正文", chunks.first()))

        return chunks.mapIndexed { index, chunk ->
            SplitChapter("第 ${index + 1} 节", chunk)
        }.let(::limitChapters)
    }

    private fun splitParagraphs(text: String): List<String> {
        if (text.contains("\n\n")) {
            return text.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        }
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 在句末标点附近切分超长段落，避免一页/一章出现巨无霸段落。 */
    private fun splitLongParagraph(paragraph: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (start < paragraph.length) {
            var end = min(start + fallbackChunkSize, paragraph.length)
            if (end < paragraph.length) {
                val sentenceEnd = findLastSentenceBoundary(paragraph, start, end)
                if (sentenceEnd > start + fallbackChunkSize / 3) {
                    end = sentenceEnd
                }
                // 不要把高代理项和低代理项拆开。
                if (end < paragraph.length && end > start &&
                    Character.isHighSurrogate(paragraph[end - 1]) &&
                    Character.isLowSurrogate(paragraph[end])
                ) {
                    end += 1
                }
            }
            result.add(paragraph.substring(start, end).trim())
            start = end
        }
        return result.filter { it.isNotEmpty() }
    }

    private fun findLastSentenceBoundary(text: String, start: Int, end: Int): Int {
        for (i in end - 1 downTo start) {
            val c = text[i]
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == ';' || c == '；') {
                return i + 1
            }
        }
        return end
    }

    private fun limitChapters(chapters: List<SplitChapter>): List<SplitChapter> {
        if (chapters.size <= maxChapters) return chapters
        // 超出上限时将尾部章节合并，避免目录/分页占用无限资源。
        val head = chapters.take(maxChapters - 1).toMutableList()
        val tailText = chapters.drop(maxChapters - 1).joinToString("\n\n") { it.text }
        head.add(SplitChapter("续篇", tailText))
        return head
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 6_000
        const val MAX_CHAPTERS = 5_000
        const val MAX_HEADING_LENGTH = 48
        private const val LONG_PARAGRAPH_THRESHOLD = 12_000

        // 第一章 / 第12回 / 第一百零八节
        private val CHINESE_HEADING = Regex(
            "^第[0-9零〇一二三四五六七八九十百千万两]+[章回节卷集部篇](?:[\\s　:：、.．·\\-—].{0,40})?$",
        )
        // 序章 / 楔子 / 番外 一
        private val SPECIAL_HEADING = Regex(
            "^(?:序章|序言|楔子|引子|前言|后记|尾声|终章|番外(?:[\\s　:：、.．·\\-—].{0,20})?|附录(?:[\\s　:：、.．·\\-—].{0,20})?)$",
        )
        // Chapter 1 / CHAPTER IV: The End
        private val ENGLISH_HEADING = Regex(
            "^chapter\\s+(?:[0-9]+|[ivxlcdm]+)(?:\\s*[:.\\-—]\\s*.{0,40})?$",
            RegexOption.IGNORE_CASE,
        )
        // 1、小标题（要求前一行空行，降低正文误判）
        private val NUMBERED_HEADING = Regex("^[0-9]{1,4}[、.．]\\s*\\S.{0,30}$")
        // # 标题 / ## 标题
        private val MARKDOWN_HEADING = Regex("^#{1,3}\\s+\\S.{0,40}$")
    }
}
