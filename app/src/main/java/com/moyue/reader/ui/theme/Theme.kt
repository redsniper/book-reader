package com.moyue.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 应用主色板。阅读器内部会按 [com.moyue.reader.domain.model.ReaderTheme] 单独着色，
 * 书架、设置等页面始终使用这套"宣纸 + 墨 + 朱砂"视觉。
 */
private val MoYueColorScheme = lightColorScheme(
    primary = Cinnabar,
    onPrimary = Color(0xFFFFFBF4),
    primaryContainer = CinnabarSoft,
    onPrimaryContainer = Color(0xFF4A1D10),
    secondary = Moss,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2ECDC),
    onSecondaryContainer = Color(0xFF1C2E19),
    tertiary = Color(0xFF8A6A3B),
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFCF8F0),
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkSoft,
    outline = PaperLine,
    outlineVariant = Color(0xFFE8DECB),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

@Composable
fun MoYueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MoYueColorScheme,
        typography = MoYueTypography,
        content = content,
    )
}
