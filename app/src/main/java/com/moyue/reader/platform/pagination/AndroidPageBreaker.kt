package com.moyue.reader.platform.pagination

import android.annotation.SuppressLint

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.moyue.reader.domain.model.FontFamilyOption
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
 * 章节首页预留标题高度；页与页之间不会截断行。空章节返回一个空页区间。
 *
 * 注意：任何会改变页边界的修改都必须递增 `PAGE_INDEX_ENGINE_VERSION`，
 * 否则已落盘的页索引缓存会与新的测量结果不一致。
 */
class AndroidPageBreaker : PageBreaker {

    override fun breakChapter(title: String, text: String, spec: PageLayoutSpec): List<PageRange> {
        if (text.isEmpty()) return listOf(PageRange(0, 0))

        val paint = createTextPaint(spec)
        val width = spec.widthPx.coerceAtLeast(1)
        val layout = buildLayout(text, paint, width, spec.lineHeightPx)
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

    private fun createTextPaint(spec: PageLayoutSpec): TextPaint {
        val typeface = when (spec.fontFamily) {
            FontFamilyOption.DEFAULT -> Typeface.DEFAULT
            FontFamilyOption.SERIF -> Typeface.SERIF
            FontFamilyOption.SANS -> Typeface.SANS_SERIF
            FontFamilyOption.MONOSPACE -> Typeface.MONOSPACE
        }
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spec.fontSizePx
            this.typeface = typeface
            color = android.graphics.Color.BLACK
        }
    }

    // StaticLayout.Builder#setBreakStrategy 使用 Layout.BREAK_STRATEGY_*；
    // Lint 的 WrongConstant 规则误将其与 API 29 的 LineBreaker 常量比较，这里显式抑制。
    @SuppressLint("WrongConstant")
    private fun buildLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        targetLineHeightPx: Float,
    ): StaticLayout {
        val metrics = paint.fontMetrics
        val naturalLineHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        // Compose 的 lineHeight 是行盒总高；StaticLayout 在此基础上增加 extra 间距。
        val extraSpacing = (targetLineHeightPx - naturalLineHeight).coerceAtLeast(0f)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(extraSpacing, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }
}
