package com.moyue.reader.data.file

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** 文件超过导入上限。 */
class FileTooLargeException(val limitBytes: Long) : IOException("File exceeds $limitBytes bytes")

/** 暂存文件信息。commit 前位于 {bookId}.tmp，commit 后位于 {bookId}。 */
data class StagedSource(
    val tempFile: File,
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * 应用私有目录中的书籍文件管理。
 *
 * 目录布局：
 * filesDir/books/{bookId}/source.{ext}
 * filesDir/books/{bookId}/cover.img
 * filesDir/books/{bookId}.tmp/  （导入暂存，失败时清理）
 *
 * 这里不依赖 Android Context，构造时传入 filesDir，便于 Robolectric/JVM 临时目录测试。
 */
class BookFileStore(filesDir: File) {

    private val rootDir: File = filesDir.absoluteFile
    private val booksDir: File = File(rootDir, BOOKS_DIR_NAME)

    fun newBookId(): String = UUID.randomUUID().toString()

    /**
     * 将输入流复制到暂存目录，同时计算 SHA-256 与大小。
     *
     * 调用方负责在成功后 [commit]，失败时 [discard]。
     */
    fun stageSource(
        bookId: String,
        extension: String,
        maxBytes: Long,
        openStream: () -> InputStream,
    ): StagedSource {
        validateBookId(bookId)
        val safeExtension = extension.filter { it.isLetterOrDigit() }.ifBlank { "bin" }
        val tempDir = tempDir(bookId)
        deleteQuietly(tempDir)
        if (!tempDir.mkdirs() && !tempDir.isDirectory) {
            throw IOException("无法创建书籍暂存目录")
        }

        val tempFile = File(tempDir, "source.$safeExtension")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            openStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw FileTooLargeException(maxBytes)
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (t: Throwable) {
            deleteQuietly(tempDir)
            throw t
        }

        return StagedSource(
            tempFile = tempFile,
            relativePath = "$BOOKS_DIR_NAME/$bookId/source.$safeExtension",
            sha256 = digest.digest().toHex(),
            sizeBytes = total,
        )
    }

    /**
     * 将封面字节写入暂存目录。封面缺失或过大时返回 null，不阻断导入。
     */
    fun stageCover(bookId: String, coverBytes: ByteArray?): String? {
        validateBookId(bookId)
        if (coverBytes == null || coverBytes.isEmpty() || coverBytes.size > MAX_COVER_BYTES) {
            return null
        }
        val tempDir = tempDir(bookId)
        if (!tempDir.mkdirs() && !tempDir.isDirectory) return null
        return try {
            File(tempDir, COVER_FILE_NAME).writeBytes(coverBytes)
            "$BOOKS_DIR_NAME/$bookId/$COVER_FILE_NAME"
        } catch (_: IOException) {
            null
        }
    }

    /** 将暂存目录原子重命名为正式书籍目录（同一文件系统内 rename）。 */
    fun commit(bookId: String) {
        validateBookId(bookId)
        val temp = tempDir(bookId)
        val target = bookDir(bookId)
        if (!temp.isDirectory) throw IOException("导入暂存目录不存在")
        if (target.exists()) {
            deleteQuietly(target)
        }
        if (!temp.renameTo(target)) {
            throw IOException("无法提交书籍文件")
        }
    }

    /** 丢弃暂存目录。 */
    fun discard(bookId: String) {
        validateBookId(bookId)
        deleteQuietly(tempDir(bookId))
    }

    /** 删除正式目录与可能的暂存目录。 */
    fun deleteBookFiles(bookId: String) {
        validateBookId(bookId)
        deleteQuietly(tempDir(bookId))
        deleteQuietly(bookDir(bookId))
    }

    /** 把数据库中的相对路径解析为绝对文件；拒绝逃逸出私有根目录的路径。 */
    fun resolve(relativePath: String): File {
        val file = File(rootDir, relativePath)
        val canonical = file.canonicalFile
        val rootCanonical = rootDir.canonicalFile
        if (!canonical.path.startsWith(rootCanonical.path + File.separator)) {
            throw SecurityException("非法文件路径")
        }
        return canonical
    }

    fun sourceFile(relativePath: String): File = resolve(relativePath)

    fun coverFile(relativePath: String): File = resolve(relativePath)

    private fun tempDir(bookId: String): File = File(booksDir, "$bookId.tmp")

    private fun bookDir(bookId: String): File = File(booksDir, bookId)

    private fun validateBookId(bookId: String) {
        require(BOOK_ID_PATTERN.matches(bookId)) { "非法书籍 ID" }
    }

    private fun deleteQuietly(file: File) {
        if (file.exists()) {
            file.deleteRecursively()
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

    companion object {
        const val BOOKS_DIR_NAME = "books"
        const val COVER_FILE_NAME = "cover.img"
        const val MAX_COVER_BYTES = 10 * 1024 * 1024
        private val BOOK_ID_PATTERN = Regex("^[A-Za-z0-9-]{8,64}$")
    }
}
