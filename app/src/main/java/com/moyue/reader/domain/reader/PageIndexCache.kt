package com.moyue.reader.domain.reader

import com.moyue.reader.domain.model.Chapter
import java.security.MessageDigest

/**
 * 页索引缓存引擎版本。
 *
 * 只要页边界算法发生变化（StaticLayout 行高处理、逐行装箱、标题预留高度、
 * 空章节页策略等），就必须递增该值。缓存文件会携带此版本，旧算法生成的
 * 页索引会被自动判定为失效，避免升级后继续使用与当前渲染不一致的页。
 */
const val PAGE_INDEX_ENGINE_VERSION: Int = 1

/**
 * 页索引缓存签名：描述“在什么输入下算出的页索引”。
 *
 * 分页结果只与以下输入有关：
 * - 源文件内容（[contentHash]）：文件变了，章节正文可能变；
 * - 章节切分（[chapterCount]、[totalChars]、[chapterMetaHash]）：同一文件重新解析后，
 *   章节边界或长度可能变化；
 * - 排版参数（[layout]）：可用宽高、字体、字号、行高、标题预留高度；
 * - 算法版本（[engineVersion]）：见 [PAGE_INDEX_ENGINE_VERSION]。
 *
 * 主题、翻页模式只影响颜色或页面容器，不影响页边界，因此不参与签名。
 *
 * 签名同时用于生成缓存文件名和写入文件头：文件名负责快速定位，
 * 文件头负责在哈希碰撞/文件被替换时再次严格校验。
 */
data class PageIndexSignature(
    val contentHash: String,
    val chapterCount: Int,
    val totalChars: Int,
    val chapterMetaHash: String,
    val layout: PageLayoutSpec,
    val engineVersion: Int = PAGE_INDEX_ENGINE_VERSION,
) {
    companion object {
        /** 根据当前章节与排版参数生成签名。章节只哈希元信息，不复制正文。 */
        fun of(
            contentHash: String,
            chapters: List<Chapter>,
            layout: PageLayoutSpec,
        ): PageIndexSignature {
            val digest = MessageDigest.getInstance("SHA-256")
            chapters.forEach { chapter ->
                // 同时写入 index 与 text.length，防止数据库中的 charCount 与正文长度
                // 因异常数据不一致时复用错误页区间。
                val line = "${chapter.index}:${chapter.charStart}:${chapter.charCount}:${chapter.text.length}\n"
                digest.update(line.toByteArray(Charsets.UTF_8))
            }
            return PageIndexSignature(
                contentHash = contentHash,
                chapterCount = chapters.size,
                totalChars = chapters.sumOf { it.charCount },
                chapterMetaHash = digest.digest().toHex(),
                layout = layout,
            )
        }

        private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
    }
}

/**
 * 页索引缓存端口。
 *
 * 领域层只关心“给定签名能否取回页索引”，不关心缓存是文件、数据库还是内存。
 * 实现方必须保证：
 * - 命中时返回的页区间与传入的 [chapters] 完全吻合（不重叠、不遗漏、不越界）；
 * - 未命中、缓存损坏或版本不符时返回 null，而不是抛异常中断阅读；
 * - [load] / [save] 是阻塞 IO，调用方负责切换到 IO 调度器。
 */
interface PageIndexCache {
    fun load(
        bookId: String,
        signature: PageIndexSignature,
        chapters: List<Chapter>,
    ): ReaderPageIndex?

    fun save(
        bookId: String,
        signature: PageIndexSignature,
        chapters: List<Chapter>,
        index: ReaderPageIndex,
    )
}
