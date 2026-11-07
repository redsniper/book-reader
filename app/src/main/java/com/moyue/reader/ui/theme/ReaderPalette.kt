package com.moyue.reader.ui.theme

import androidx.compose.ui.graphics.Color
import com.moyue.reader.R
import com.moyue.reader.domain.model.FontFamilyOption
import com.moyue.reader.domain.model.ReaderTheme
import androidx.compose.ui.text.font.FontFamily

/**
 * 阅读器颜色板。
 *
 * 应用整体固定"宣纸"视觉；阅读器按用户选择的主题切换背景与正文颜色。
 * 状态栏图标明暗通过 [isDark] 控制。
 */
data class ReaderPalette(
    val background: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val chromeSurface: Color,
    val isDark: Boolean,
)

fun ReaderTheme.palette(): ReaderPalette = when (this) {
    ReaderTheme.PARCHMENT -> ReaderPalette(
        background = Color(0xFFF5E6C8),
        text = Color(0xFF2A2118),
        secondary = Color(0xFF7A6A52),
        accent = Color(0xFF9C4A2F),
        chromeSurface = Color(0xF2F7EBD4),
        isDark = false,
    )

    ReaderTheme.NIGHT -> ReaderPalette(
        background = Color(0xFF0D0F12),
        text = Color(0xFFC9CBD1),
        secondary = Color(0xFF7B7F88),
        accent = Color(0xFFC98B5E),
        chromeSurface = Color(0xF20D0F12),
        isDark = true,
    )

    ReaderTheme.GREEN -> ReaderPalette(
        background = Color(0xFFDDE8D5),
        text = Color(0xFF20301F),
        secondary = Color(0xFF6B7D64),
        accent = Color(0xFF3F6B4A),
        chromeSurface = Color(0xF2E5EEDC),
        isDark = false,
    )

    ReaderTheme.WHITE -> ReaderPalette(
        background = Color(0xFFFAFAF7),
        text = Color(0xFF1C1C1C),
        secondary = Color(0xFF6C6C6C),
        accent = Color(0xFF8A3B2B),
        chromeSurface = Color(0xF2FFFFFF),
        isDark = false,
    )
}

fun ReaderTheme.displayNameRes(): Int = when (this) {
    ReaderTheme.PARCHMENT -> R.string.reader_theme_parchment
    ReaderTheme.NIGHT -> R.string.reader_theme_night
    ReaderTheme.GREEN -> R.string.reader_theme_green
    ReaderTheme.WHITE -> R.string.reader_theme_white
}

fun FontFamilyOption.displayNameRes(): Int = when (this) {
    FontFamilyOption.DEFAULT -> R.string.reader_font_default
    FontFamilyOption.SERIF -> R.string.reader_font_serif
    FontFamilyOption.SANS -> R.string.reader_font_sans
    FontFamilyOption.MONOSPACE -> R.string.reader_font_monospace
}

fun FontFamilyOption.toComposeFontFamily(): FontFamily = when (this) {
    FontFamilyOption.DEFAULT -> FontFamily.Default
    FontFamilyOption.SERIF -> FontFamily.Serif
    FontFamilyOption.SANS -> FontFamily.SansSerif
    FontFamilyOption.MONOSPACE -> FontFamily.Monospace
}
