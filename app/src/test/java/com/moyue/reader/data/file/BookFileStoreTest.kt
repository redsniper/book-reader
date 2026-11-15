package com.moyue.reader.data.file

import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookFileStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fileStore: BookFileStore
    private lateinit var filesDir: java.io.File

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        fileStore = BookFileStore(filesDir)
    }

    @Test
    fun `stages hashes commits and resolves source`() {
        val bookId = UUID.randomUUID().toString()
        val content = "第一章 初见\n山风很轻。".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") {
            "%02x".format(it)
        }

        val staged = fileStore.stageSource(bookId, "txt", 1024) {
            ByteArrayInputStream(content)
        }

        assertEquals(content.size.toLong(), staged.sizeBytes)
        assertEquals(digest, staged.sha256)
        assertTrue(staged.tempFile.exists())
        assertEquals("books/$bookId/source.txt", staged.relativePath)

        fileStore.commit(bookId)
        val committed = fileStore.resolve(staged.relativePath)
        assertTrue(committed.isFile)
        assertArrayEquals(content, committed.readBytes())
        assertFalse(staged.tempFile.exists())
    }

    @Test
    fun `rejects file over limit and cleans temp directory`() {
        val bookId = UUID.randomUUID().toString()
        assertThrows(FileTooLargeException::class.java) {
            fileStore.stageSource(bookId, "txt", 10) {
                ByteArrayInputStream(ByteArray(100) { 1 })
            }
        }
        val tempDir = java.io.File(filesDir, "books/$bookId.tmp")
        assertFalse(tempDir.exists())
    }

    @Test
    fun `stores cover and deletes whole book directory`() {
        val bookId = UUID.randomUUID().toString()
        fileStore.stageSource(bookId, "epub", 1024) {
            ByteArrayInputStream("content".toByteArray())
        }
        val coverPath = fileStore.stageCover(bookId, ByteArray(32) { 7 })
        assertEquals("books/$bookId/cover.img", coverPath)
        fileStore.commit(bookId)
        assertTrue(fileStore.resolve(coverPath!!).isFile)

        fileStore.deleteBookFiles(bookId)
        assertFalse(java.io.File(filesDir, "books/$bookId").exists())
    }

    @Test
    fun `ignores empty or oversized cover`() {
        val bookId = UUID.randomUUID().toString()
        assertNullCover(fileStore.stageCover(bookId, ByteArray(0)))
        assertNullCover(fileStore.stageCover(bookId, ByteArray(BookFileStore.MAX_COVER_BYTES + 1)))
    }

    @Test
    fun `resolve rejects path traversal`() {
        assertThrows(SecurityException::class.java) {
            fileStore.resolve("../secret.txt")
        }
    }

    @Test
    fun `invalid book id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            fileStore.stageSource("../../bad", "txt", 10) { ByteArrayInputStream(ByteArray(0)) }
        }
    }

    @Test
    fun `commit without staged directory fails`() {
        assertThrows(IOException::class.java) {
            fileStore.commit(UUID.randomUUID().toString())
        }
    }

    private fun assertNullCover(value: String?) {
        org.junit.Assert.assertNull(value)
    }
}
