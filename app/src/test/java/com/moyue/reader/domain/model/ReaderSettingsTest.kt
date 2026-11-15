package com.moyue.reader.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSettingsTest {

    @Test
    fun `sanitizes out of range values`() {
        val dirty = ReaderSettings(
            fontSizeSp = 100f,
            lineHeightMultiplier = 0.1f,
            horizontalPaddingDp = 500,
        )
        val safe = dirty.sanitized()
        assertEquals(32f, safe.fontSizeSp, 0.001f)
        assertEquals(1.2f, safe.lineHeightMultiplier, 0.001f)
        assertEquals(40, safe.horizontalPaddingDp)
    }

    @Test
    fun `font size rounds to integer steps`() {
        assertEquals(18f, ReaderSettings(fontSizeSp = 18.4f).sanitized().fontSizeSp, 0.001f)
        assertEquals(19f, ReaderSettings(fontSizeSp = 18.6f).sanitized().fontSizeSp, 0.001f)
    }

    @Test
    fun `defaults satisfy constraints`() {
        val defaults = ReaderSettings()
        assertEquals(defaults, defaults.sanitized())
        assertEquals(ReaderTheme.PARCHMENT, defaults.theme)
        assertEquals(PageMode.HORIZONTAL, defaults.pageMode)
        assertEquals(FontFamilyOption.SERIF, defaults.fontFamily)
    }
}
