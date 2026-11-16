package com.moyue.reader.platform.pagination

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.moyue.reader.domain.model.FontFamilyOption

/**
 * 分页测量与正文绘制的唯一 `TextPaint` / `StaticLayout` 构建入口。
 *
 * 为什么必须共用一个工厂：
 * - 分页按 [StaticLayout] 的行盒高度把字符区间装进页框，阅读页再把同一套
 *   `StaticLayout` 参数画到 Canvas 上；只要两边的字体、字号、行距、断行策略有任何
 *   一处不同，页尾就会多出或少掉一行。
 * - 历史实现里分页用 `setLineSpacing`、正文用 Compose `Text` 的
 *   `LineHeightStyleSpan`：后者按 `ceil(lineHeightPx)` 重排每行高度并裁剪首/末行，
 *   与前者整数行底装箱存在逐行取整差异。2.0× 大行距满页时误差累积，最后一行会被
 *   页框裁掉（真机已复现）。把两边收敛到本工厂后，页面独立布局高度恒为
 *   `N × 自然行高 + (N-1) × extra`，等于分页装箱高度减去最后一个 extra，绘制内容
 *   不会越出页框。
 *
 * 约束：
 * - 修改 Typeface 映射、`includePad`、行距计算、断行策略或连字符策略都会改变页边界，
 *   必须递增 [com.moyue.reader.domain.reader.PAGE_INDEX_ENGINE_VERSION]，否则已落盘的
 *   页索引缓存会与新排版不一致。
 * - 本类只做纯 Android 文本测量，不持有 Context、不访问文件，可在分页后台线程使用，
 *   也可在 UI 线程为当前可见页构建布局。
 */
object PageTextLayoutFactory {

    /**
     * 创建与分页测量完全一致的正文画笔。
     *
     * [color] 只影响绘制，不参与行高/断行计算；分页时使用默认黑色即可。
     */
    fun createTextPaint(
        fontFamily: FontFamilyOption,
        fontSizePx: Float,
        color: Int = android.graphics.Color.BLACK,
    ): TextPaint {
        val typeface = when (fontFamily) {
            FontFamilyOption.DEFAULT -> Typeface.DEFAULT
            FontFamilyOption.SERIF -> Typeface.SERIF
            FontFamilyOption.SANS -> Typeface.SANS_SERIF
            FontFamilyOption.MONOSPACE -> Typeface.MONOSPACE
        }
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            this.typeface = typeface
            this.color = color
        }
    }

    /**
     * 按分页参数构建一段文本的 [StaticLayout]。
     *
     * 行距处理与 Compose 的 `lineHeight` 语义对齐：目标行高减去字体自然行高得到
     * `lineSpacingExtra`，由 StaticLayout 统一加到行盒里。Compose 的 lineHeight 是行盒
     * 总高，StaticLayout 的 `setLineSpacing` 则是在自然行高上追加 extra，因此两者必须
     * 用同一个目标值换算，不能各算各的。
     */
    // StaticLayout.Builder#setBreakStrategy 使用 Layout.BREAK_STRATEGY_*；
    // Lint 的 WrongConstant 规则误将其与 API 29 的 LineBreaker 常量比较，这里显式抑制。
    @SuppressLint("WrongConstant")
    fun buildLayout(
        text: String,
        paint: TextPaint,
        widthPx: Int,
        lineHeightPx: Float,
    ): StaticLayout {
        val width = widthPx.coerceAtLeast(1)
        val metrics = paint.fontMetrics
        val naturalLineHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        val extraSpacing = (lineHeightPx - naturalLineHeight).coerceAtLeast(0f)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(extraSpacing, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }
}
