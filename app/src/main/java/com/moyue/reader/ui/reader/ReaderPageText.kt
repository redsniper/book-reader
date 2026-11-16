package com.moyue.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import com.moyue.reader.domain.model.FontFamilyOption
import com.moyue.reader.platform.pagination.PageTextLayoutFactory

/**
 * 阅读页正文渲染器。
 *
 * 为什么不直接用 Compose `Text`：
 * - 分页器用 `StaticLayout.setLineSpacing` 计算每页能装多少行，正文再用 Compose `Text`
 *   时，`LineHeightStyleSpan` 会按 `ceil(lineHeightPx)` 重新分配行高并裁剪首/末行；
 *   两套实现逐行取整不同，2.0× 大行距满页时误差累积，页尾行会被页框裁掉。
 * - 这里用 `PageTextLayoutFactory` 构建与分页**完全相同参数**的 [android.text.StaticLayout]，
 *   再画到 Canvas 上。页面独立布局高度为 `行数 × 自然行高 + (行数-1) × extra`，恒等于
 *   分页装箱高度减去最后一个 extra（章节末页两边都省略末行 extra），因此不会越出页框。
 * - 只重建当前组合的可见页布局，不保留整章布局，内存与原来的 Compose Text 同量级。
 *
 * 语义：Canvas 本身不产生文本语义，这里显式设置 `text`，让读屏仍能逐页朗读正文。
 * 点击翻页/呼出菜单由外层页面 Box 处理，Canvas 不拦截手势。
 */
@Composable
internal fun ReaderPageText(
    pageText: String,
    fontFamily: FontFamilyOption,
    fontSizePx: Float,
    lineHeightPx: Float,
    widthPx: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // 页面区间会包含行尾换行符；独立布局若保留会多出一个不可见空行，先裁掉。
    val displayText = remember(pageText) { pageDisplayText(pageText) }
    val layout = remember(displayText, fontFamily, fontSizePx, lineHeightPx, widthPx, color) {
        if (displayText.isEmpty()) {
            null
        } else {
            val paint = PageTextLayoutFactory.createTextPaint(
                fontFamily = fontFamily,
                fontSizePx = fontSizePx,
                color = color.toArgb(),
            )
            PageTextLayoutFactory.buildLayout(
                text = displayText,
                paint = paint,
                widthPx = widthPx,
                lineHeightPx = lineHeightPx,
            )
        }
    }
    val semanticsText = remember(displayText) {
        if (displayText.isEmpty()) null else AnnotatedString(displayText)
    }

    Canvas(
        modifier = modifier.semantics {
            semanticsText?.let { text = it }
        },
    ) {
        val current = layout ?: return@Canvas
        drawIntoCanvas { canvas ->
            current.draw(canvas.nativeCanvas)
        }
    }
}

/**
 * 取页面文本用于独立布局的内容。
 *
 * 分页区间的终点是 `StaticLayout.getLineEnd()`，会包含行尾的 `\n`。把同一段字符
 * 单独排版时，末尾换行会再产生一个空行：它没有可见墨迹，却会让布局高度多出一行，
 * 使“页面布局高度 ≤ 页框高度”的判定失真。只裁末尾换行，不动行内空行。
 */
internal fun pageDisplayText(pageText: String): String = pageText.trimEnd('\n')
