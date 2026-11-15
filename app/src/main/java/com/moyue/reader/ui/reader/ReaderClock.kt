package com.moyue.reader.ui.reader

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.reader.R
import com.moyue.reader.core.Clock
import com.moyue.reader.ui.theme.ReaderPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 阅读器左上角时间浮层。
 *
 * 为什么单独做一个文件/组件：
 * - 沉浸阅读会隐藏系统状态栏，用户需要在不呼出菜单的情况下看到时间；
 * - 时间每分钟变化一次，只应让这一行 Text 重组。如果把时间放进
 *   [ReaderUiState]，每次整分钟都会让阅读页状态对象变化，导致整页重组；
 *   放在本地状态里可以把重组限制在时钟文本。
 * - 时间不持久化、不写日志、不参与阅读进度，进程重启后直接重新读取系统时间。
 *
 * 时间带尺寸常量同时被 [ReaderScreen] 用于计算分页视口：时间行高必须与
 * 渲染时 `Text` 的 lineHeight 完全一致，否则分页器与正文会使用不同的可用高度。
 */

/** 时间带上方的留白。 */
internal val CLOCK_TOP_PADDING = 3.dp

/**
 * 时间行高（sp）。
 *
 * 必须与 [ReaderClock] 中 Text 的 lineHeight 相同。用 sp 而非固定 dp，
 * 是为了在系统字体放大时让时间带一起变高，避免压到正文首行。
 */
internal val CLOCK_LINE_HEIGHT = 16.sp

/** 时间行与正文页之间的呼吸间距。 */
internal val CLOCK_GAP = 3.dp

/** 时间字号。 */
internal val CLOCK_FONT_SIZE = 12.sp

/**
 * 时间文字的不透明度。
 *
 * 不用 `palette.secondary`：羊皮纸主题下 secondary 与背景对比度约 4.2:1，
 * 低于小字号 WCAG AA 的 4.5:1；在正文色上降低 alpha 既能弱化存在感，
 * 四种主题下也仍满足 AA。
 */
private const val CLOCK_TEXT_ALPHA = 0.72f

/** 固定 24 小时制与 ASCII 数字，不受系统 12/24 小时偏好或 locale 数字字形影响。 */
private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * 把时间戳格式化为 24 小时制 `HH:mm`（如 `23:11`）。
 *
 * 显式传入时区而不是在内部读取系统默认值，便于单元测试确定性断言；
 * 调用方每次刷新时重新传入 `ZoneId.systemDefault()`，可响应用户改时区。
 */
internal fun formatClockTime(epochMillis: Long, zoneId: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(CLOCK_FORMAT)

/**
 * 距离下一个整分钟的毫秒数，取值 `(0, 60_000]`。
 *
 * 使用“对齐系统整分钟”而不是固定 `delay(60_000)`：后者从首次组合时刻起
 * 会累积漂移，时间跳变最多晚一整分钟。恰好位于整分钟时返回 60_000，
 * 避免 0 延迟造成忙循环。
 */
internal fun millisUntilNextMinute(epochMillis: Long): Long =
    MILLIS_PER_MINUTE - Math.floorMod(epochMillis, MILLIS_PER_MINUTE)

/**
 * 左上角时间文本。
 *
 * 刷新策略：先立即显示当前时间，再睡到下一个整分钟刷新。协程绑定组合，
 * 阅读页退出即取消；应用进入后台时即使 timer 被系统冻结，回到前台后
 * 下一轮也会按当前系统时间重算，不会长期停留在旧值。
 *
 * 时钟本身没有点击手势，也不会拦截下方正文的触摸；时间带位于正文点击区之外，
 * 点击它不会翻页或切换菜单，点击正文区域仍按原逻辑操作。
 */
@Composable
internal fun ReaderClock(
    clock: Clock,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
) {
    // 每轮重新读取系统时区：用户中途修改时区时，最迟下一次整分钟刷新即可纠正。
    // 读取默认时区是轻量操作，不需要额外注册 BroadcastReceiver。
    // 用局部 snapshot state + LaunchedEffect：刷新循环包含 while/delay，
    // 语义清晰，也不会触发 Lint 对 produceState 赋值路径的误判。
    val textState = remember {
        mutableStateOf(formatClockTime(clock.nowMillis(), ZoneId.systemDefault()))
    }
    LaunchedEffect(clock) {
        while (true) {
            val now = clock.nowMillis()
            textState.value = formatClockTime(now, ZoneId.systemDefault())
            delay(millisUntilNextMinute(now))
        }
    }
    val text = textState.value

    // 读屏读出“当前时间 23:11”，而不是逐位朗读冒号与数字；
    // clearAndSetSemantics 同时清掉 Text 自身的 text 语义，避免重复播报。
    val description = stringResource(R.string.reader_clock_description, text)
    Text(
        text = text,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        color = palette.text.copy(alpha = CLOCK_TEXT_ALPHA),
        fontFamily = FontFamily.SansSerif,
        fontSize = CLOCK_FONT_SIZE,
        lineHeight = CLOCK_LINE_HEIGHT,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}
