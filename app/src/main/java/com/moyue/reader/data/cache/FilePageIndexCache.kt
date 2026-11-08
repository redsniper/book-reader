package com.moyue.reader.data.cache

import com.moyue.reader.data.file.BookFileStore
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.FontFamilyOption
import com.moyue.reader.domain.reader.PageIndexCache
import com.moyue.reader.domain.reader.PageIndexSignature
import com.moyue.reader.domain.reader.PageInfo
import com.moyue.reader.domain.reader.PageLayoutSpec
import com.moyue.reader.domain.reader.ReaderPageIndex
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 基于应用私有文件的页索引缓存。
 *
 * 设计权衡：
 * - **不写 Room**：页索引是纯派生数据，写入数据库需要 schema 迁移，且会让
 *   删除书籍/重置数据多一份清理责任；文件缓存可直接随书籍目录级联删除。
 * - **每套排版一个文件**：页边界与可用宽高、字号、行高强相关。按签名分文件，
 *   切换字号后再切回来无需重新排版；同时用 LRU 限制每本书保留的套数。
 * - **原子写入**：先写临时文件再 rename，避免进程被杀时留下半截索引被误读。
 * - **严格校验**：文件头签名 + 页区间连续覆盖校验，任何不一致都当未命中，
 *   宁可多排一次也不让错误的页区间截断正文。
 *
 * 所有方法都是阻塞 IO，调用方必须切到 IO 调度器。
 */
class FilePageIndexCache(
    private val fileStore: BookFileStore,
) : PageIndexCache {

    override fun load(
        bookId: String,
        signature: PageIndexSignature,
        chapters: List<Chapter>,
    ): ReaderPageIndex? {
        if (chapters.isEmpty()) return null
        val file = cacheFile(bookId, signature)
        if (!file.isFile) return null
        if (file.length() !in 1..MAX_CACHE_FILE_BYTES) {
            deleteQuietly(file)
            return null
        }

        return try {
            val index = read(file, bookId, signature, chapters)
            if (index == null) {
                deleteQuietly(file)
            } else {
                // 命中即更新访问时间，供 LRU 淘汰时保留最近使用的排版。
                file.setLastModified(System.currentTimeMillis())
            }
            index
        } catch (_: IOException) {
            deleteQuietly(file)
            null
        } catch (_: RuntimeException) {
            // 损坏的缓存不能影响阅读；RuntimeException 包含枚举值非法、
            // 负长度等格式异常。真正的取消异常不会出现在阻塞 IO 中。
            deleteQuietly(file)
            null
        }
    }

    override fun save(
        bookId: String,
        signature: PageIndexSignature,
        chapters: List<Chapter>,
        index: ReaderPageIndex,
    ) {
        require(chapters.isNotEmpty()) { "空章节不写页索引缓存" }
        require(signature.chapterCount == chapters.size) { "缓存签名与章节数量不一致" }
        require(signature.totalChars == chapters.sumOf { it.charCount }) {
            "缓存签名与章节总字数不一致"
        }
        require(validatePages(index.pages, chapters)) { "页索引未连续覆盖章节正文" }

        val dir = fileStore.paginationCacheDir(bookId)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建页索引缓存目录")
        }
        val target = File(dir, cacheFileName(signature))
        val temp = File.createTempFile("page-index-", ".tmp", dir)
        try {
            write(temp, bookId, signature, index.pages)
            moveAtomically(temp, target)
            target.setLastModified(System.currentTimeMillis())
            prune(dir)
        } finally {
            deleteQuietly(temp)
        }
    }

    // ---------------------------------------------------------------------
    // 读取与校验
    // ---------------------------------------------------------------------

    private fun read(
        file: File,
        bookId: String,
        signature: PageIndexSignature,
        chapters: List<Chapter>,
    ): ReaderPageIndex? {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != MAGIC) return null
            if (input.readInt() != FORMAT_VERSION) return null
            if (input.readInt() != signature.engineVersion) return null
            if (input.readUTF() != bookId) return null
            if (input.readUTF() != signature.contentHash) return null
            if (input.readInt() != signature.chapterCount) return null
            if (input.readInt() != signature.totalChars) return null
            if (input.readUTF() != signature.chapterMetaHash) return null
            if (!readLayout(input).matches(signature.layout)) return null

            val pageCount = input.readInt()
            if (pageCount <= 0 || pageCount > maxPageCount(chapters)) return null
            val pages = ArrayList<PageInfo>(pageCount)
            repeat(pageCount) {
                val chapterIndex = input.readInt()
                val start = input.readInt()
                val end = input.readInt()
                val flags = input.readUnsignedByte()
                // 目前只有 bit0 表示章节首页；其余位保留，出现即视为损坏。
                if (flags and IS_CHAPTER_START_FLAG.inv() != 0) return null
                pages.add(
                    PageInfo(
                        chapterIndex = chapterIndex,
                        start = start,
                        end = end,
                        isChapterStart = flags and IS_CHAPTER_START_FLAG != 0,
                        pdfPageIndex = null,
                    ),
                )
            }
            // 文件尾部若还有数据，说明格式或长度被破坏。
            if (input.read() != -1) return null
            if (!validatePages(pages, chapters)) return null
            return ReaderPageIndex(pages, chapters)
        }
    }

    private fun readLayout(input: DataInputStream): PageLayoutSpec {
        val widthPx = input.readInt()
        val heightPx = input.readInt()
        val titleHeightPx = input.readInt()
        val fontFamily = FontFamilyOption.valueOf(input.readUTF())
        val fontSizeBits = input.readInt()
        val lineHeightBits = input.readInt()
        return PageLayoutSpec(
            widthPx = widthPx,
            heightPx = heightPx,
            fontFamily = fontFamily,
            fontSizePx = java.lang.Float.intBitsToFloat(fontSizeBits),
            lineHeightPx = java.lang.Float.intBitsToFloat(lineHeightBits),
            chapterTitleHeightPx = titleHeightPx,
        )
    }

    /**
     * 校验页区间是否恰好覆盖所有章节：
     * - 章节从 0 开始连续出现，不能跳章或乱序；
     * - 每章第一页从 0 开始且标记 isChapterStart，后续页首尾相接；
     * - 每章最后一页结束于正文长度，全书最后一页结束于末章正文长度；
     * - 空章节保留一个 [0,0] 占位页。
     */
    private fun validatePages(pages: List<PageInfo>, chapters: List<Chapter>): Boolean {
        if (pages.isEmpty() || chapters.isEmpty()) return false
        var currentChapter = -1
        var lastEnd = 0
        pages.forEach { page ->
            if (page.pdfPageIndex != null) return false
            if (page.chapterIndex !in chapters.indices) return false
            val chapter = chapters[page.chapterIndex]
            // 正文渲染使用 chapter.text.substring(page.start, page.end)，
            // 因此以 text.length 作为硬上限，不能只信数据库里的 charCount。
            if (page.start < 0 || page.end < page.start || page.end > chapter.text.length) {
                return false
            }
            if (page.end == page.start && chapter.text.isNotEmpty()) return false
            if (page.chapterIndex != currentChapter) {
                // 第一次出现必须是第 0 章，之后必须严格 +1，确保没有章节漏页。
                val expected = currentChapter + 1
                if (page.chapterIndex != expected) return false
                if (currentChapter >= 0 && lastEnd != chapters[currentChapter].text.length) {
                    return false
                }
                if (page.start != 0 || !page.isChapterStart) return false
                currentChapter = page.chapterIndex
            } else {
                if (page.start != lastEnd || page.isChapterStart) return false
            }
            lastEnd = page.end
        }
        return currentChapter == chapters.lastIndex &&
            lastEnd == chapters.last().text.length
    }

    private fun maxPageCount(chapters: List<Chapter>): Int {
        // 每页至少覆盖 1 个字符；空章节也占 1 页。上限同时防御损坏文件
        // 通过超大 pageCount 触发巨量分配。
        var count = 0L
        chapters.forEach { count += maxOf(1, it.text.length).toLong() }
        return count.coerceAtMost(MAX_PAGE_COUNT.toLong()).toInt()
    }

    private fun PageLayoutSpec.matches(other: PageLayoutSpec): Boolean =
        widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            chapterTitleHeightPx == other.chapterTitleHeightPx &&
            fontFamily == other.fontFamily &&
            fontSizePx.toRawBits() == other.fontSizePx.toRawBits() &&
            lineHeightPx.toRawBits() == other.lineHeightPx.toRawBits()

    // ---------------------------------------------------------------------
    // 写入、命名与清理
    // ---------------------------------------------------------------------

    private fun write(
        file: File,
        bookId: String,
        signature: PageIndexSignature,
        pages: List<PageInfo>,
    ) {
        FileOutputStream(file).use { fos ->
            val output = DataOutputStream(BufferedOutputStream(fos))
            output.writeInt(MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeInt(signature.engineVersion)
            output.writeUTF(bookId)
            output.writeUTF(signature.contentHash)
            output.writeInt(signature.chapterCount)
            output.writeInt(signature.totalChars)
            output.writeUTF(signature.chapterMetaHash)
            output.writeInt(signature.layout.widthPx)
            output.writeInt(signature.layout.heightPx)
            output.writeInt(signature.layout.chapterTitleHeightPx)
            output.writeUTF(signature.layout.fontFamily.name)
            output.writeInt(signature.layout.fontSizePx.toRawBits())
            output.writeInt(signature.layout.lineHeightPx.toRawBits())
            output.writeInt(pages.size)
            pages.forEach { page ->
                require(page.pdfPageIndex == null) { "扫描 PDF 页不应写入文本页索引缓存" }
                output.writeInt(page.chapterIndex)
                output.writeInt(page.start)
                output.writeInt(page.end)
                output.writeByte(if (page.isChapterStart) IS_CHAPTER_START_FLAG else 0)
            }
            output.flush()
            // 页索引是派生数据，fsync 只是降低“刚排完就掉电”导致半截文件的概率；
            // 即使失败也不影响当前阅读，异常由调用方统一忽略。
            fos.fd.sync()
        }
    }

    private fun cacheFile(bookId: String, signature: PageIndexSignature): File {
        val dir = fileStore.paginationCacheDir(bookId)
        return File(dir, cacheFileName(signature))
    }

    private fun cacheFileName(signature: PageIndexSignature): String {
        val layout = signature.layout
        val canonical = buildString {
            append(signature.engineVersion).append('|')
            append(signature.contentHash).append('|')
            append(signature.chapterCount).append('|')
            append(signature.totalChars).append('|')
            append(signature.chapterMetaHash).append('|')
            append(layout.widthPx).append('|')
            append(layout.heightPx).append('|')
            append(layout.chapterTitleHeightPx).append('|')
            append(layout.fontFamily.name).append('|')
            append(layout.fontSizePx.toRawBits()).append('|')
            append(layout.lineHeightPx.toRawBits())
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.toHex().take(32) + CACHE_FILE_SUFFIX
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /** 每本书最多保留 [MAX_CACHED_LAYOUTS] 套、总量不超过 [MAX_TOTAL_CACHE_BYTES]。 */
    private fun prune(dir: File) {
        // 进程在写临时文件时被杀会留下 .tmp；只清理明显过期的文件，
        // 避免误删另一个正在写入的临时文件。
        val now = System.currentTimeMillis()
        dir.listFiles { file ->
            file.isFile &&
                file.name.endsWith(TEMP_FILE_SUFFIX) &&
                now - file.lastModified() > STALE_TEMP_MILLIS
        }?.forEach { deleteQuietly(it) }

        val files = dir.listFiles { file ->
            file.isFile && file.name.endsWith(CACHE_FILE_SUFFIX)
        }?.sortedByDescending { it.lastModified() } ?: return

        var kept = 0
        var bytes = 0L
        files.forEach { file ->
            val withinCount = kept < MAX_CACHED_LAYOUTS
            // 至少保留最新一套；单套超过总量上限时也保留，避免永远无法命中。
            val withinBytes = kept == 0 || bytes + file.length() <= MAX_TOTAL_CACHE_BYTES
            if (withinCount && withinBytes) {
                kept += 1
                bytes += file.length()
            } else {
                deleteQuietly(file)
            }
        }
    }

    private fun deleteQuietly(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

    private companion object {
        const val MAGIC = 0x4D595047 // "MYPG"
        const val FORMAT_VERSION = 1
        const val IS_CHAPTER_START_FLAG = 0x01
        const val CACHE_FILE_SUFFIX = ".idx"
        const val TEMP_FILE_SUFFIX = ".tmp"
        const val STALE_TEMP_MILLIS = 60L * 60 * 1000
        const val MAX_CACHED_LAYOUTS = 4
        const val MAX_TOTAL_CACHE_BYTES = 8L * 1024 * 1024
        const val MAX_CACHE_FILE_BYTES = 16L * 1024 * 1024
        const val MAX_PAGE_COUNT = 1_000_000
    }
}
