package com.moyue.reader.data.parser

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDecoderTest {

    private val sample = "第一章 初见\n山里的风很轻，少年背着剑。\n"

    @Test
    fun `decodes utf8 with bom`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray()
        val decoded = TextDecoder.decode(bytes)
        assertEquals(sample, decoded.text)
        assertEquals("UTF-8", decoded.charsetName)
        assertFalse(decoded.hadErrors)
    }

    @Test
    fun `decodes utf16le with bom`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)
        val decoded = TextDecoder.decode(bytes)
        assertEquals(sample, decoded.text)
        assertEquals("UTF-16LE", decoded.charsetName)
    }

    @Test
    fun `decodes utf16be with bom`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + sample.toByteArray(Charsets.UTF_16BE)
        val decoded = TextDecoder.decode(bytes)
        assertEquals(sample, decoded.text)
        assertEquals("UTF-16BE", decoded.charsetName)
    }

    @Test
    fun `decodes gb18030 when not valid utf8`() {
        val gb = Charset.forName("GB18030")
        val bytes = sample.toByteArray(gb)
        val decoded = TextDecoder.decode(bytes)
        assertEquals(sample, decoded.text)
        assertEquals("GB18030", decoded.charsetName)
    }

    @Test
    fun `decodes plain utf8`() {
        val decoded = TextDecoder.decode(sample.toByteArray())
        assertEquals(sample, decoded.text)
        assertEquals("UTF-8", decoded.charsetName)
    }

    @Test
    fun `decodes bomless utf16le ascii`() {
        val ascii = "Chapter 1\nHello world.\n"
        val decoded = TextDecoder.decode(ascii.toByteArray(Charsets.UTF_16LE))
        assertEquals(ascii, decoded.text)
    }

    @Test
    fun `empty input yields empty text`() {
        val decoded = TextDecoder.decode(ByteArray(0))
        assertEquals("", decoded.text)
        assertFalse(decoded.hadErrors)
    }
}
