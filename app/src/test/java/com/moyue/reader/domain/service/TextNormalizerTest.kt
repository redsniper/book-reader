package com.moyue.reader.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun `normalizes line endings and invisible characters`() {
        val raw = "\uFEFF第一章\r\n内容\u200B\r\r下一段\u0000"
        val normalized = TextNormalizer.normalize(raw)
        assertEquals("第一章\n内容\n\n下一段", normalized)
    }

    @Test
    fun `collapses excessive blank lines and trims trailing spaces`() {
        val raw = "第一段   \n\n\n\n第二段\t \n"
        assertEquals("第一段\n\n第二段", TextNormalizer.normalize(raw))
    }

    @Test
    fun `preserves full width indentation`() {
        val raw = "　　第一段\n　　第二段"
        assertEquals(raw, TextNormalizer.normalize(raw))
    }

    @Test
    fun `blank input stays blank`() {
        assertTrue(TextNormalizer.normalize(" \n\t\r\n ").isEmpty())
    }
}
