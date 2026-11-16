package com.moyue.reader.platform.pagination

import com.moyue.reader.domain.reader.PageBreaker
import com.moyue.reader.domain.reader.PageLayoutSpec
import com.moyue.reader.domain.reader.PageRange

/**
 * 基于 Android StaticLayout 的章节分页器。
 *
 * 为什么使用 StaticLayout：
 * - 与系统文字排版引擎一致，能正确处理中英文断行、标点避头尾；
 * - 可以直接拿到"行"的边界，按行装箱得到页字符区间；
 * - 纯本地测量，不依赖 Compose 组合，可放到后台线程执行。
 *
 * 与正文绘制的关系：
 * - 测量和绘制必须共用 [PageTextLayoutFactory]，不能一边用 StaticLayout、
 *   一边用 Compose `Text`。两套行高实现（`setLineSpacing` 的行盒 vs
 *   `LineHeightStyleSpan` 的 `ceil(lineHeightPx)` 重排）对每行取整不同，2.0× 大行距
 *   满页时误差累积会把页尾行推出页框并裁掉。
 * - 页面独立布局高度为 `行数 × 自然行高 + (行数-1) × extra`，恰好比本分页器
 *   `getLineBottom` 装箱高度少一个 extra（章节末页两边都省略末行 extra），因此
 *   正文永远不会超出这里算出的页框。
 *
 * 章节首页预留标题高度；页与页之间不会截断行。空章节返回一个空页区间。
 *
 * 注意：任何会改变页边界的修改（含 [PageTextLayoutFactory] 的参数）都必须递增
 * `PAGE_INDEX_ENGINE_VERSION`，否则已落盘的页索引缓存会与新的测量结果不一致。
 */
class AndroidPageBreaker : PageBreaker {

    override fun breakChapter(title: String, text: String, spec: PageLayoutSpec): List<PageRange> {
        if (text.isEmpty()) return listOf(PageRange(0, 0))

        val paint = PageTextLayoutFactory.createTextPaint(
            fontFamily = spec.fontFamily,
            fontSizePx = spec.fontSizePx,
        )
        val layout = PageTextLayoutFactory.buildLayout(
            text = text,
            paint = paint,
            widthPx = spec.widthPx,
            lineHeightPx = spec.lineHeightPx,
        )
        if (layout.lineCount <= 0) return listOf(PageRange(0, text.length))

        val firstPageHeight = (spec.heightPx - spec.chapterTitleHeightPx).toFloat()
            .coerceAtLeast(spec.lineHeightPx)
        val pageHeight = spec.heightPx.toFloat().coerceAtLeast(spec.lineHeightPx)

        val pages = ArrayList<PageRange>(layout.lineCount / 10 + 1)
        var line = 0
        while (line < layout.lineCount) {
            val available = if (pages.isEmpty()) firstPageHeight else pageHeight
            val pageTop = layout.getLineTop(line)
            var endLine = line
            while (endLine < layout.lineCount) {
                val occupied = layout.getLineBottom(endLine) - pageTop
                if (occupied > available) break
                endLine++
            }
            // 单行高度超过整页时也至少装一行，避免死循环。
            if (endLine == line) endLine = line + 1

            val startChar = layout.getLineStart(line)
            val endChar = layout.getLineEnd(endLine - 1)
            if (endChar > startChar) {
                pages.add(PageRange(startChar, endChar))
            }
            line = endLine
        }

        if (pages.isEmpty()) return listOf(PageRange(0, text.length))
        // 极端情况下（尾部只有空行）把剩余字符并入最后一页，保证页区间覆盖全文。
        val last = pages.last()
        if (last.end < text.length) {
            pages[pages.lastIndex] = PageRange(last.start, text.length)
        }
        return pages
    }
}
