package com.moyue.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * 横向电池图标：轮廓固定，内部填充宽度与电量百分比严格成比例。
 *
 * 为什么不用静态的“满格电池”矢量图：
 * - 47% 时显示满格电池会和右侧文字互相矛盾；
 * - 只有轮廓时，小尺寸下又容易看成胶囊、开关或字母 O；
 * - 按比例填充能同时解决“这是电池”和“电量大概多少”两个问题，
 *   精确数值仍由右侧文字负责。
 *
 * 为什么用 Canvas 而不是 vector drawable：
 * 填充宽度必须随电量变化。Canvas 可以复用一套 24×24 的设计坐标，
 * 避免为 101 种百分比各准备一个资源，也不需要引入额外依赖。
 * 所有尺寸都按 [Modifier] 的实际像素尺寸换算，系统字体放大时随图标整体缩放。
 *
 * 图标只是装饰性视觉元素：读屏语义由 [ReaderBattery] 的 Row 统一提供，
 * 因此 Canvas 本身不设置 contentDescription。
 */
@Composable
internal fun ReaderBatteryIcon(
    percent: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        // 以下数字沿用 24×24 的设计网格，再按实际尺寸缩放。
        // 主体：x=3.5..19，y=7..17，圆角 1.8；描边 2。
        val bodyLeft = width * 3.5f / 24f
        val bodyTop = height * 7f / 24f
        val bodyWidth = width * 15.5f / 24f
        val bodyHeight = height * 10f / 24f
        val strokeWidth = width * 2f / 24f
        val cornerRadius = width * 1.8f / 24f

        drawRoundRect(
            color = color,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(cornerRadius),
            style = Stroke(width = strokeWidth),
        )

        // 右侧正极：短竖线，与主体右边缘相接。
        drawLine(
            color = color,
            start = Offset(width * 21f / 24f, height * 10.5f / 24f),
            end = Offset(width * 21f / 24f, height * 13.5f / 24f),
            strokeWidth = width * 2.4f / 24f,
            cap = StrokeCap.Round,
        )

        // 内部填充：四周内缩 1.6 个单位，留出与描边之间的空隙，
        // 避免填充盖住电池边框；宽度严格等于百分比，不做离散档位。
        val inset = width * 1.6f / 24f
        val innerLeft = bodyLeft + inset
        val innerTop = bodyTop + inset
        val innerHeight = bodyHeight - inset * 2
        val maxInnerWidth = bodyWidth - inset * 2
        val fillWidth = maxInnerWidth * batteryFillFraction(percent)
        if (fillWidth > 0f) {
            drawRoundRect(
                color = color,
                topLeft = Offset(innerLeft, innerTop),
                size = Size(fillWidth, innerHeight),
                cornerRadius = CornerRadius(width * 0.6f / 24f),
            )
        }
    }
}

/**
 * 把 0..100 的电量换算为 0f..1f 的填充比例。
 *
 * 单独抽成纯函数是为了单元测试边界：0% 不画填充，100% 填满内胆，
 * 非法值（理论上不会出现）也不能画出溢出边框的色块。
 */
internal fun batteryFillFraction(percent: Int): Float =
    percent.coerceIn(0, 100) / 100f
