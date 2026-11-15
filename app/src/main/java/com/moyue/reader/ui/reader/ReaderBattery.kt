package com.moyue.reader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moyue.reader.R
import com.moyue.reader.domain.system.BatteryStatusProvider
import com.moyue.reader.ui.theme.ReaderPalette

/**
 * 阅读器右上角电量：按比例填充的电池图标 + 整数百分比文本。
 *
 * 为什么加图标：底部栏同样用百分比表示阅读进度，单看一个 `85%` 容易被误读成
 * “本书读到 85%”；横向电池图标可以明确这是设备电量。图标内部按百分比填充，
 * 在小尺寸下比纯轮廓更像电池，同时与右侧精确百分比保持一致；没有闪电符号，
 * 不暗示充电状态。
 *
 * 为什么单独一个组件、并且不放进 `ReaderUiState`：
 * - 电量是系统状态，不属于书籍/分页状态；放进 `ReaderUiState` 会让每次 1% 变化
 *   都产生新的状态对象，导致整个阅读页重组；
 * - 与 [ReaderClock] 一样只在组件内部用 lifecycle 感知的 flow 收集，重组范围限制在这行内容；
 * - 电量不持久化、不写诊断日志；回到前台重新订阅时会重新读取系统 sticky 广播，
 *   因此不会显示后台期间的过期值。
 *
 * 视觉上与左上角时间共用 ReaderStatusBand.kt 中的尺寸/透明度常量，保证两端同一基线；
 * 内容没有点击手势，不会拦截下方正文的触摸，也不参与翻页/菜单命中区。
 */
@Composable
internal fun ReaderBattery(
    batteryStatus: BatteryStatusProvider,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
) {
    // initialValue = null：系统尚未给出有效电量时不渲染任何内容（而不是显示占位的 "--%"，
    // 避免用户误以为电量读取失败）。collectAsStateWithLifecycle 在 ON_STOP 时暂停收集，
    // 从而注销平台层的系统广播接收器；回到 ON_START 后重新注册并立即拿到 sticky 电量。
    val percent by batteryStatus.batteryPercent.collectAsStateWithLifecycle(initialValue = null)
    val value = percent ?: return

    // 百分比数字用 String 传入而不是 %d：保证始终是 ASCII 数字，不随 locale 变成
    // 阿拉伯-印度数字等字形，与左上角时间的 24 小时制 ASCII 数字保持一致。
    val text = stringResource(R.string.reader_battery_percent, value.toString())

    // 读屏播报“当前电量 85%”，而不是把图标、数字和百分号拆开朗读；
    // 语义挂在整个 Row 上并清空子节点语义，避免图标与文字重复播报。
    val description = stringResource(R.string.reader_battery_description, value.toString())
    val foreground = palette.text.copy(alpha = STATUS_BAND_TEXT_ALPHA)
    // 图标随系统字体缩放，但不能改变状态带高度：14sp 小于 16sp 行高，
    // 因此 Row 的测量高度仍由 Text 的 lineHeight 决定。
    val iconSize = with(LocalDensity.current) { STATUS_BAND_ICON_SIZE.toDp() }

    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(STATUS_BAND_ICON_GAP),
    ) {
        // 图标是装饰性语义，已由 Row 的 contentDescription 覆盖；Canvas 本身不产生语义。
        ReaderBatteryIcon(
            percent = value,
            color = foreground,
            modifier = Modifier.size(iconSize),
        )
        Text(
            text = text,
            color = foreground,
            fontFamily = FontFamily.SansSerif,
            fontSize = STATUS_BAND_FONT_SIZE,
            lineHeight = STATUS_BAND_LINE_HEIGHT,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
