package com.moyue.reader.platform.pagination

import android.graphics.Color
import android.text.StaticLayout
import android.text.TextPaint
import com.moyue.reader.domain.model.FontFamilyOption
import com.moyue.reader.domain.reader.PageLayoutSpec
import com.moyue.reader.ui.reader.pageDisplayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 页布局装箱不变量回归（M24）。
 *
 * 修复前：分页用 `StaticLayout` 行底装箱，正文用 Compose `Text` 的
 * `LineHeightStyleSpan`；两套行高逐行取整不同，2.0× 大行距满页时页尾行被页框裁切。
 * 修复后正文由 `PageTextLayoutFactory` 构建同一个 `StaticLayout` 绘制，因此必须满足：
 *
 * 1. 每页独立布局高度 ≤ 该页分页可用高度（章首页扣除标题预留）；
 * 2. 独立布局行数与章节布局在该页字符区间内的行数一致（断行同源）。
 *
 * 这些断言直接锁定了“分页测量 == 正文绘制”的核心前提；任何人把正文改回 Compose
 * Text 或修改工厂行距，都必须先让本测试仍有解释。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageTextLayoutTest {

    private val breaker = AndroidPageBreaker()

    /** 目录、标点、中英混排、空行与代理对都要覆盖，避免只测纯汉字短行。 */
    private val corpus: String = buildString {
        for (index in 1..36) {
            append("回到家，夏心妍就跟柳云霜聊起了天，还问柳云霜晚上有没有空。")
            append("接下来，夏心妍呆在家里等啊等，终于等到柳云霜的车来了。")
            if (index % 4 == 0) {
                append("“霜姐姐，你怎么这么晚才来？”")
            }
            append("她和你一样今年读s大，但她真的真的太烦人了……")
            append('\n')
            if (index % 5 == 0) {
                // 段间空行：页边界可能正好落一个纯换行行。
                append('\n')
            }
            if (index == 7) {
                append("表情符号也必须按整行切页：😀😀😀😀😀😀😀😀😀😀\n")
            }
        }
    }

    private fun spec(
        fontSizePx: Float = 67.5f,
        lineHeightPx: Float = 135f,
        fontFamily: FontFamilyOption = FontFamilyOption.SANS,
    ) = PageLayoutSpec(
        widthPx = 945,
        heightPx = 2096,
        fontFamily = fontFamily,
        fontSizePx = fontSizePx,
        lineHeightPx = lineHeightPx,
        chapterTitleHeightPx = 120,
    )

    private fun paint(spec: PageLayoutSpec): TextPaint =
        PageTextLayoutFactory.createTextPaint(spec.fontFamily, spec.fontSizePx)

    private fun buildLayout(text: String, spec: PageLayoutSpec, paint: TextPaint): StaticLayout =
        PageTextLayoutFactory.buildLayout(
            text = text,
            paint = paint,
            widthPx = spec.widthPx,
            lineHeightPx = spec.lineHeightPx,
        )

    @Test
    fun `every page layout fits the page box and keeps line breaks`() {
        val specs = listOf(
            // 用户真机复现配置：24sp / 2.0× / 无衬线（按 2.8125 密度换算的 px）。
            spec(fontSizePx = 67.5f, lineHeightPx = 135f, fontFamily = FontFamilyOption.SANS),
            // 默认字号、默认行距。
            spec(fontSizePx = 50.6f, lineHeightPx = 91.1f, fontFamily = FontFamilyOption.SERIF),
            // 最大行距 + 最小字号。
            spec(fontSizePx = 39.4f, lineHeightPx = 94.6f, fontFamily = FontFamilyOption.DEFAULT),
            // 最大字号 + 最大行距。
            spec(fontSizePx = 90f, lineHeightPx = 216f, fontFamily = FontFamilyOption.MONOSPACE),
            // 最小行距边界：extra 会被 clamp 到 0，绘制与分页仍需一致。
            spec(fontSizePx = 90f, lineHeightPx = 108f, fontFamily = FontFamilyOption.SANS),
        )

        specs.forEach { layoutSpec ->
            val pagePaint = paint(layoutSpec)
            val chapterLayout = buildLayout(corpus, layoutSpec, pagePaint)
            val pages = breaker.breakChapter("第一章 初见", corpus, layoutSpec)

            assertTrue("应产生多页: $layoutSpec", pages.size > 1)
            assertEquals(0, pages.first().start)
            assertEquals(corpus.length, pages.last().end)
            pages.zipWithNext().forEach { (first, second) ->
                assertEquals("页区间必须首尾相接", first.end, second.start)
            }

            pages.forEachIndexed { pageIndex, range ->
                val isChapterStart = pageIndex == 0
                assertTrue(range.end > range.start)
                val available = layoutSpec.heightPx -
                    if (isChapterStart) layoutSpec.chapterTitleHeightPx else 0

                // 与 ReaderPageText 完全相同的裁剪与构建路径。
                val displayText = pageDisplayText(
                    corpus.substring(range.start.coerceAtLeast(0), range.end.coerceAtMost(corpus.length)),
                )
                val pageLayout = buildLayout(displayText, layoutSpec, pagePaint)
                assertTrue(
                    "页 $pageIndex 布局高度 ${pageLayout.height} 超过可用高度 $available " +
                        "(spec=$layoutSpec, lines=${pageLayout.lineCount})",
                    pageLayout.height <= available,
                )

                // 正文从整行行首开始，且独立布局与章节布局行数一致。
                val firstLine = chapterLayout.getLineForOffset(range.start)
                assertEquals(
                    "页 $pageIndex 未从行首开始",
                    range.start,
                    chapterLayout.getLineStart(firstLine),
                )
                if (displayText.isNotEmpty()) {
                    val endOffset = (range.start + displayText.length).coerceAtMost(corpus.length)
                    val lastLine = chapterLayout.getLineForOffset(endOffset - 1)
                    assertEquals(
                        "页 $pageIndex 独立布局行数与章节布局不一致 (spec=$layoutSpec)",
                        lastLine - firstLine + 1,
                        pageLayout.lineCount,
                    )
                }
            }
        }
    }

    @Test
    fun `display text trims only trailing newlines`() {
        assertEquals("第一段\n\n第二段", pageDisplayText("第一段\n\n第二段\n\n\n"))
        assertEquals("只有正文", pageDisplayText("只有正文"))
        assertEquals("", pageDisplayText("\n\n"))
    }

    @Test
    fun `factory paint keeps size and color`() {
        val painted = PageTextLayoutFactory.createTextPaint(
            fontFamily = FontFamilyOption.SANS,
            fontSizePx = 67.5f,
            color = Color.RED,
        )
        assertEquals(67.5f, painted.textSize, 0f)
        assertEquals(Color.RED, painted.color)
        assertTrue(painted.isAntiAlias)
    }
}
