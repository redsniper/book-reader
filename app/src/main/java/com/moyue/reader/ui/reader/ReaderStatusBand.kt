package com.moyue.reader.ui.reader

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 沉浸阅读顶部状态带的共享尺寸与颜色常量。
 *
 * 左上角时间与右上角电量必须使用完全相同的行高、字号和透明度，才能保证：
 * - 两端文字在同一基线上，视觉上属于同一条状态带；
 * - [ReaderScreen] 只需要为“一行状态文本”预留一次页顶空间，电量不会额外减少正文高度；
 * - 系统字体放大时，两端随同一个 sp 常量同步增高，不会一端压到正文。
 *
 * 这些常量同时参与分页视口计算：`ReaderScreen` 用同一个 `Density` 把
 * [STATUS_BAND_LINE_HEIGHT] 解析为像素，再换算成正文页顶留白。分页器与渲染
 * 必须同源，任何改动都要保持 `ReaderClock` / `ReaderBattery` 的 `Text` 参数一致。
 */

/** 状态带上方的留白。 */
internal val STATUS_BAND_TOP_PADDING = 3.dp

/**
 * 状态行高（sp）。
 *
 * 必须与 [ReaderClock]、[ReaderBattery] 中 Text 的 lineHeight 相同。
 * 用 sp 而非固定 dp，是为了在系统字体放大时让状态带一起变高，避免压到正文首行。
 */
internal val STATUS_BAND_LINE_HEIGHT = 16.sp

/** 状态行与正文页之间的呼吸间距。 */
internal val STATUS_BAND_GAP = 3.dp

/** 状态文字字号。 */
internal val STATUS_BAND_FONT_SIZE = 12.sp

/**
 * 电量图标的边长（sp）。
 *
 * 图标必须与文字一样跟随系统字体缩放：固定 dp 在 2.0× 字体下会缩成一个小点，
 * 与放大的百分比文字不成比例。14sp 略高于 12sp 字号，让 24×24 设计网格里的
 * 横向电池与按比例填充在 1× 下仍可辨识；同时小于 16sp 行高，不撑高状态带。
 */
internal val STATUS_BAND_ICON_SIZE = 14.sp

/** 电池图标与百分比文字之间的间距。 */
internal val STATUS_BAND_ICON_GAP = 3.dp

/**
 * 状态文字的不透明度。
 *
 * 不用 `palette.secondary`：羊皮纸主题下 secondary 与背景对比度约 4.2:1，
 * 低于小字号 WCAG AA 的 4.5:1；在正文色上降低 alpha 既能弱化存在感，
 * 四种主题下也仍满足 AA。时间与电量必须使用同一 alpha，避免一端过亮抢视线。
 */
internal const val STATUS_BAND_TEXT_ALPHA = 0.72f
