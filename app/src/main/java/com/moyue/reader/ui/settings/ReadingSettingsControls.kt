package com.moyue.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.reader.R
import com.moyue.reader.domain.model.FontFamilyOption
import com.moyue.reader.domain.model.PageMode
import com.moyue.reader.domain.model.ReaderSettings
import com.moyue.reader.domain.model.ReaderTheme
import com.moyue.reader.ui.theme.Cinnabar
import com.moyue.reader.ui.theme.Ink
import com.moyue.reader.ui.theme.InkSoft
import com.moyue.reader.ui.theme.PaperDeep
import com.moyue.reader.ui.theme.displayNameRes
import com.moyue.reader.ui.theme.palette
import com.moyue.reader.ui.theme.toComposeFontFamily
import kotlin.math.roundToInt

/**
 * 阅读排版设置控件，供阅读器底部表单与设置页共用。
 *
 * 交互策略：
 * - 主题、翻页模式、字体族即时应用；
 * - 字号/行距/边距拖动时只更新本地预览，松手后一次性应用，避免连续重排整本书。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsControls(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    showPreview: Boolean = true,
    compact: Boolean = false,
) {
    var draft by remember { mutableStateOf(settings) }
    LaunchedEffect(settings) { draft = settings }

    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)) {
        if (showPreview) {
            ReaderPreview(draft)
        }

        SettingLabel(stringResource(R.string.reader_settings_theme))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReaderTheme.entries.forEach { theme ->
                ThemeSwatch(
                    theme = theme,
                    selected = draft.theme == theme,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val next = draft.copy(theme = theme)
                        draft = next
                        onSettingsChange(next)
                    },
                )
            }
        }

        SettingLabel(stringResource(R.string.reader_settings_page_mode))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PageMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = draft.pageMode == mode,
                    onClick = {
                        val next = draft.copy(pageMode = mode)
                        draft = next
                        onSettingsChange(next)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, PageMode.entries.size),
                ) {
                    Text(
                        stringResource(
                            if (mode == PageMode.HORIZONTAL) {
                                R.string.reader_page_mode_horizontal
                            } else {
                                R.string.reader_page_mode_vertical
                            },
                        ),
                    )
                }
            }
        }

        SettingLabel(stringResource(R.string.reader_settings_font))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FontFamilyOption.entries.forEach { family ->
                FilterChip(
                    selected = draft.fontFamily == family,
                    onClick = {
                        val next = draft.copy(fontFamily = family)
                        draft = next
                        onSettingsChange(next)
                    },
                    label = { Text(stringResource(family.displayNameRes())) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Cinnabar.copy(alpha = 0.12f),
                        selectedLabelColor = Cinnabar,
                    ),
                )
            }
        }

        SliderSetting(
            label = stringResource(R.string.reader_settings_size),
            valueText = stringResource(
                R.string.reader_settings_font_size_value,
                draft.fontSizeSp.roundToInt(),
            ),
            value = draft.fontSizeSp,
            range = ReaderSettings.MIN_FONT_SIZE_SP..ReaderSettings.MAX_FONT_SIZE_SP,
            steps = 17,
            onChange = { draft = draft.copy(fontSizeSp = it) },
            onFinished = { onSettingsChange(draft) },
        )

        SliderSetting(
            label = stringResource(R.string.reader_settings_line_height),
            valueText = stringResource(
                R.string.reader_settings_line_height_value,
                String.format(java.util.Locale.US, "%.1f", draft.lineHeightMultiplier),
            ),
            value = draft.lineHeightMultiplier,
            range = ReaderSettings.MIN_LINE_HEIGHT..ReaderSettings.MAX_LINE_HEIGHT,
            steps = 11,
            onChange = { draft = draft.copy(lineHeightMultiplier = it) },
            onFinished = { onSettingsChange(draft) },
        )

        SliderSetting(
            label = stringResource(R.string.reader_settings_padding),
            valueText = stringResource(
                R.string.reader_settings_padding_value,
                draft.horizontalPaddingDp,
            ),
            value = draft.horizontalPaddingDp.toFloat(),
            range = ReaderSettings.MIN_PADDING_DP.toFloat()..ReaderSettings.MAX_PADDING_DP.toFloat(),
            steps = 11,
            onChange = { draft = draft.copy(horizontalPaddingDp = it.roundToInt()) },
            onFinished = { onSettingsChange(draft) },
        )
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = InkSoft,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun ReaderPreview(settings: ReaderSettings) {
    val palette = settings.theme.palette()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.background)
            .border(1.dp, palette.text.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(R.string.reader_settings_preview),
            color = palette.text,
            fontFamily = settings.fontFamily.toComposeFontFamily(),
            fontSize = (settings.fontSizeSp * 0.8f).sp,
            lineHeight = (settings.fontSizeSp * 0.8f * settings.lineHeightMultiplier).sp,
            maxLines = 2,
        )
    }
}

@Composable
private fun ThemeSwatch(
    theme: ReaderTheme,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = theme.palette()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Cinnabar else Ink.copy(alpha = 0.12f),
                shape = RoundedCornerShape(9.dp),
            )
            .background(palette.background)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(palette.accent),
        )
        Text(
            text = stringResource(theme.displayNameRes()),
            color = palette.text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SliderSetting(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingLabel(label)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = Cinnabar)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onFinished,
            colors = SliderDefaults.colors(
                thumbColor = Cinnabar,
                activeTrackColor = Cinnabar,
                inactiveTrackColor = PaperDeep,
            ),
        )
    }
}
