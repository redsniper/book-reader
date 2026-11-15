package com.moyue.reader.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookFormatTest {

    @Test
    fun `detects formats from extension case insensitively`() {
        assertEquals(BookFormat.TXT, BookFormat.fromFileName("小说.TXT"))
        assertEquals(BookFormat.EPUB, BookFormat.fromFileName("book.epub"))
        assertEquals(BookFormat.PDF, BookFormat.fromFileName("a.b.PDF"))
        assertNull(BookFormat.fromFileName("book.mobi"))
        assertNull(BookFormat.fromFileName("book"))
    }

    @Test
    fun `detects formats from mime`() {
        assertEquals(BookFormat.TXT, BookFormat.fromMimeType("text/plain"))
        assertEquals(BookFormat.EPUB, BookFormat.fromMimeType("application/epub+zip"))
        assertEquals(BookFormat.PDF, BookFormat.fromMimeType("application/pdf"))
        assertNull(BookFormat.fromMimeType("application/octet-stream"))
        assertNull(BookFormat.fromMimeType(null))
    }
}
