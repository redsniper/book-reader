package com.moyue.reader.data.parser

import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.parser.BookParseException
import com.moyue.reader.domain.parser.BookParser
import com.moyue.reader.domain.parser.ParseRequest
import com.moyue.reader.domain.parser.ParsedBook
import com.moyue.reader.domain.parser.ParsedChapter
import com.moyue.reader.domain.service.ChapterSplitter
import com.moyue.reader.domain.service.TextNormalizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.w3c.dom.Document as W3cDocument
import org.w3c.dom.Element as W3cElement

/**
 * EPUB 2 / EPUB 3 解析器。
 *
 * 只抽取可重排文字，不保留原书 CSS（v1 阅读器有统一的排版系统）。
 * 目录优先使用 EPUB3 nav，其次 EPUB2 NCX，最后回退为 spine 顺序。
 *
 * 安全防护：
 * - 不向磁盘解压，避免 Zip Slip；
 * - 限制条目数、单条目大小、总解压大小，避免 Zip Bomb；
 * - XML 解析禁用 DOCTYPE 与外部实体（XXE）；
 * - 加密/DRM 图书直接拒绝。
 */
class EpubParser(
    @Suppress("unused") private val chapterSplitter: ChapterSplitter,
) : BookParser {

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    override fun supports(format: BookFormat): Boolean = format == BookFormat.EPUB

    override suspend fun parse(request: ParseRequest): ParsedBook {
        val source = request.source
        if (!source.isFile) throw BookParseException.Corrupted("EPUB 文件不存在")
        if (source.length() > BookFormat.EPUB.sizeLimitBytes) {
            throw BookParseException.TooLarge(BookFormat.EPUB.sizeLimitBytes)
        }

        val zip = try {
            ZipFile(source)
        } catch (e: ZipException) {
            throw BookParseException.Corrupted("EPUB 不是有效的 ZIP 文件", e)
        } catch (e: Exception) {
            throw BookParseException.Io(e)
        }

        zip.use { archive ->
            validateZip(archive)

            // 1. container.xml → OPF 路径。
            val containerBytes = readEntry(archive, CONTAINER_PATH, MAX_XML_BYTES)
                ?: throw BookParseException.Corrupted("EPUB 缺少 META-INF/container.xml")
            val containerDoc = parseXml(containerBytes)
            val rootFile = firstElement(containerDoc, "rootfile")
                ?: throw BookParseException.Corrupted("EPUB 容器中缺少 rootfile")
            val opfPath = decodePath(rootFile.getAttribute("full-path"))
            if (opfPath.isBlank()) throw BookParseException.Corrupted("EPUB OPF 路径为空")

            val encryption = readEntry(archive, "META-INF/encryption.xml", MAX_XML_BYTES)
            if (encryption != null && String(encryption, Charsets.UTF_8).contains("EncryptedData")) {
                throw BookParseException.Encrypted()
            }

            // 2. OPF：元数据、manifest、spine。
            val opfBytes = readEntry(archive, opfPath, MAX_XML_BYTES)
                ?: throw BookParseException.Corrupted("EPUB 缺少 OPF 文件")
            val opf = parseXml(opfBytes)
            val opfBaseDir = opfPath.substringBeforeLast('/', "")

            val metadataTitles = elementsByLocalName(opf, "title")
                .map { it.textContent.trim() }
                .filter { it.isNotEmpty() }
            val metadataCreators = elementsByLocalName(opf, "creator")
                .map { it.textContent.trim() }
                .filter { it.isNotEmpty() }

            val manifest = elementsByLocalName(opf, "item").mapNotNull { element ->
                val id = element.getAttribute("id")
                val href = element.getAttribute("href")
                val mediaType = element.getAttribute("media-type")
                if (id.isBlank() || href.isBlank()) {
                    null
                } else {
                    ManifestItem(
                        id = id,
                        href = href,
                        mediaType = mediaType.lowercase(),
                        properties = element.getAttribute("properties").lowercase(),
                    )
                }
            }.associateBy { it.id }

            val spineIds = elementsByLocalName(opf, "itemref")
                .map { it.getAttribute("idref") }
                .filter { it.isNotBlank() }
            if (spineIds.isEmpty()) throw BookParseException.Corrupted("EPUB spine 为空")

            // 3. 目录（nav / NCX），键为去掉锚点后的 ZIP 内路径。
            val coverId = elementsByLocalName(opf, "meta")
                .firstOrNull { it.getAttribute("name").equals("cover", ignoreCase = true) }
                ?.getAttribute("content")
                ?.takeIf { it.isNotBlank() }
            val coverItem = manifest.values.firstOrNull {
                it.properties.split(Regex("\\s+")).contains("cover-image")
            } ?: coverId?.let(manifest::get)
            // 封面是可选内容：读取失败/过大不应导致整本书导入失败。
            val coverBytes = coverItem?.let { item ->
                runCatching {
                    readEntry(
                        archive = archive,
                        path = resolvePath(opfBaseDir, item.href),
                        maxBytes = MAX_COVER_BYTES,
                    )
                }.getOrNull()
            }

            val toc = parseTableOfContents(archive, opf, manifest, opfBaseDir)

            // 4. 按 spine 顺序抽取正文。
            val chapters = mutableListOf<ParsedChapter>()
            for (idRef in spineIds) {
                val item = manifest[idRef] ?: continue
                if (item.mediaType !in TEXT_MEDIA_TYPES) continue
                val path = resolvePath(opfBaseDir, item.href)
                if (path.isBlank()) continue
                val bytes = readEntry(archive, path, MAX_CONTENT_ENTRY_BYTES) ?: continue
                val html = TextDecoder.decode(bytes).text
                val jsoupDoc = Jsoup.parse(html)
                val documentTitle = jsoupDoc.title().trim()
                val rawText = extractReadableText(jsoupDoc)
                if (rawText.isBlank()) continue

                val title = toc[path]?.takeIf { it.isNotBlank() }
                    ?: documentTitle.takeIf { it.isNotBlank() }
                    ?: "第 ${chapters.size + 1} 章"
                val deduped = removeDuplicatedHeading(rawText, title)
                if (deduped.isBlank()) continue
                chapters.add(
                    ParsedChapter(
                        title = title.take(120),
                        text = deduped,
                    ),
                )
            }

            if (chapters.isEmpty()) {
                throw BookParseException.NoReadableText("EPUB 中未找到可读文本")
            }

            return ParsedBook(
                title = metadataTitles.firstOrNull(),
                author = metadataCreators.firstOrNull(),
                format = BookFormat.EPUB,
                coverBytes = coverBytes,
                chapters = chapters,
            )
        }
    }

    // ---------------------------------------------------------------------
    // 目录解析
    // ---------------------------------------------------------------------

    private fun parseTableOfContents(
        archive: ZipFile,
        opf: W3cDocument,
        manifest: Map<String, ManifestItem>,
        opfBaseDir: String,
    ): Map<String, String> {
        // EPUB3：properties 含 nav 的 XHTML。
        val navItem = manifest.values.firstOrNull {
            it.properties.split(Regex("\\s+")).contains("nav")
        }
        if (navItem != null) {
            val navPath = resolvePath(opfBaseDir, navItem.href)
            val navBytes = readEntry(archive, navPath, MAX_CONTENT_ENTRY_BYTES)
            if (navBytes != null) {
                val navBase = navPath.substringBeforeLast('/', "")
                val navDoc = Jsoup.parse(TextDecoder.decode(navBytes).text)
                var links = navDoc.select("nav a[href]")
                if (links.isEmpty()) links = navDoc.select("a[href]")
                val result = LinkedHashMap<String, String>()
                links.forEach { anchor ->
                    val href = anchor.attr("href")
                    val title = anchor.text().trim()
                    if (href.isBlank() || title.isBlank()) return@forEach
                    if (href.startsWith("http:", true) || href.startsWith("https:", true)) return@forEach
                    val path = resolvePath(navBase, href)
                    if (path.isNotBlank() && !result.containsKey(path)) {
                        result[path] = title
                    }
                }
                if (result.isNotEmpty()) return result
            }
        }

        // EPUB2：spine@toc 指向的 NCX。
        val tocId = elementsByLocalName(opf, "spine").firstOrNull()?.getAttribute("toc")
        val ncxItem = tocId?.takeIf { it.isNotBlank() }?.let(manifest::get)
        if (ncxItem != null) {
            val ncxPath = resolvePath(opfBaseDir, ncxItem.href)
            val ncxBytes = readEntry(archive, ncxPath, MAX_XML_BYTES)
            if (ncxBytes != null) {
                val ncxBase = ncxPath.substringBeforeLast('/', "")
                val ncx = parseXml(ncxBytes)
                val result = LinkedHashMap<String, String>()
                elementsByLocalName(ncx, "navPoint").forEach { navPoint ->
                    val labelElement = elementsByLocalName(navPoint, "text").firstOrNull()
                    val contentElement = elementsByLocalName(navPoint, "content").firstOrNull()
                    val title = labelElement?.textContent?.trim().orEmpty()
                    val src = contentElement?.getAttribute("src").orEmpty()
                    if (title.isBlank() || src.isBlank()) return@forEach
                    val path = resolvePath(ncxBase, src)
                    if (path.isNotBlank() && !result.containsKey(path)) {
                        result[path] = title
                    }
                }
                if (result.isNotEmpty()) return result
            }
        }
        return emptyMap()
    }

    // ---------------------------------------------------------------------
    // 正文抽取
    // ---------------------------------------------------------------------

    /** 从 XHTML 中抽取可读文字，块级元素转换为换行。 */
    private fun extractReadableText(document: Document): String {
        document.select("script, style, nav, svg, iframe, noscript").remove()
        val body = document.body() ?: return ""
        val builder = StringBuilder()
        body.childNodes().forEach { appendNode(it, builder) }
        return TextNormalizer.normalize(
            builder.toString()
                .replace('\u00A0', ' ')
                .replace("\u200B", ""),
        )
    }

    private fun appendNode(node: Node, out: StringBuilder) {
        when (node) {
            is TextNode -> out.append(node.wholeText)
            is Element -> {
                val tag = node.tagName().lowercase()
                if (tag in SKIP_TAGS) return
                if (tag == "br") {
                    out.append('\n')
                    return
                }
                if (tag == "img") return
                val isBlock = tag in BLOCK_TAGS
                if (isBlock && out.isNotEmpty() && out.last() != '\n') out.append('\n')
                node.childNodes().forEach { child -> appendNode(child, out) }
                if (isBlock && out.isNotEmpty() && out.last() != '\n') out.append('\n')
            }

            else -> node.childNodes().forEach { child -> appendNode(child, out) }
        }
    }

    /** EPUB 正文常重复一次章节标题，抽取后去掉，避免阅读页标题重复。 */
    private fun removeDuplicatedHeading(text: String, title: String): String {
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isNotEmpty() && firstLine.equals(title.trim(), ignoreCase = true)) {
            return text.lineSequence().drop(1).joinToString("\n").trimStart()
        }
        return text
    }

    // ---------------------------------------------------------------------
    // ZIP / XML 工具
    // ---------------------------------------------------------------------

    private fun validateZip(archive: ZipFile) {
        var count = 0
        var totalSize = 0L
        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            count++
            if (count > MAX_ENTRY_COUNT) {
                throw BookParseException.Corrupted("EPUB 条目数量异常")
            }
            if (entry.size > 0) {
                if (entry.size > MAX_SINGLE_ENTRY_BYTES) {
                    throw BookParseException.Corrupted("EPUB 单条目解压体积过大")
                }
                totalSize += entry.size
                if (totalSize > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw BookParseException.Corrupted("EPUB 解压体积过大")
                }
            }
        }
    }

    private fun readEntry(archive: ZipFile, path: String, maxBytes: Int): ByteArray? {
        val normalized = path.trimStart('/')
        if (normalized.isBlank() || normalized.contains("..")) return null
        val entry: ZipEntry = archive.getEntry(normalized) ?: return null
        if (entry.isDirectory) return null
        if (entry.size > maxBytes) throw BookParseException.Corrupted("EPUB 条目过大：$normalized")
        return try {
            archive.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream(minOf(entry.size.coerceAtLeast(0L), 64L * 1024).toInt())
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw BookParseException.Corrupted("EPUB 条目解压超过限制：$normalized")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (e: BookParseException) {
            throw e
        } catch (e: Exception) {
            throw BookParseException.Corrupted("读取 EPUB 条目失败：$normalized", e)
        }
    }

    private fun parseXml(bytes: ByteArray): W3cDocument {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                // 不同 Android/JVM 实现支持的特性不同，逐个尝试开启安全开关。
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
                runCatching {
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                }
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw BookParseException.Corrupted("EPUB XML 解析失败", e)
        }
    }

    private fun elementsByLocalName(document: W3cDocument, localName: String): List<W3cElement> =
        elementsByLocalName(document as org.w3c.dom.Node, localName)

    private fun elementsByLocalName(node: org.w3c.dom.Node, localName: String): List<W3cElement> {
        val nodes = when (node) {
            is W3cDocument -> node.getElementsByTagNameNS("*", localName)
            is W3cElement -> node.getElementsByTagNameNS("*", localName)
            else -> return emptyList()
        }
        return buildList {
            for (index in 0 until nodes.length) {
                (nodes.item(index) as? W3cElement)?.let(::add)
            }
        }
    }

    private fun firstElement(document: W3cDocument, localName: String): W3cElement? =
        elementsByLocalName(document, localName).firstOrNull()

    private fun decodePath(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }

    /**
     * 解析相对路径并归一化。
     *
     * @param baseDir 基准目录（不含文件名），如 "OEBPS/Text"
     */
    private fun resolvePath(baseDir: String, href: String): String {
        val raw = href.substringBefore('#').substringBefore('?').replace('\\', '/')
        if (raw.startsWith("http:", true) || raw.startsWith("https:", true) ||
            raw.startsWith("data:", true) || raw.startsWith("mailto:", true)
        ) {
            return ""
        }
        val decoded = decodePath(raw).trimStart('/')
        val parts = baseDir.split('/').filter { it.isNotEmpty() && it != "." }.toMutableList()
        decoded.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    private companion object {
        const val CONTAINER_PATH = "META-INF/container.xml"
        const val MAX_ENTRY_COUNT = 10_000
        const val MAX_XML_BYTES = 10 * 1024 * 1024
        const val MAX_CONTENT_ENTRY_BYTES = 20 * 1024 * 1024
        const val MAX_COVER_BYTES = 10 * 1024 * 1024
        const val MAX_SINGLE_ENTRY_BYTES = 50L * 1024 * 1024
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 300L * 1024 * 1024

        val TEXT_MEDIA_TYPES = setOf("application/xhtml+xml", "text/html", "application/x-dtbook+xml")
        val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "header", "footer", "aside",
            "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre",
            "tr", "table", "ul", "ol", "dl", "dt", "dd",
        )
        val SKIP_TAGS = setOf("script", "style", "nav", "svg", "iframe", "noscript")
    }
}
