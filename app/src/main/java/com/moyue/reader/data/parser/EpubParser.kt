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
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.xml.sax.InputSource
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
 * - XML 解析禁止外部实体与外部 DTD（XXE），并限制内部实体膨胀；
 *   container/OPF/encryption 额外禁用 DOCTYPE，NCX 为兼容标准 EPUB2 允许 DOCTYPE；
 * - 只在加密真正覆盖正文时拒绝，字体混淆与陈旧加密清单不阻断导入。
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

            // 加密清单不能只看是否存在 EncryptedData：大量正常 EPUB 用 encryption.xml
            // 声明字体混淆，甚至留有指向不存在条目的陈旧清单（多看等工具生成的书常见）。
            // 这里先解析出被加密资源的 URI，等拿到 OPF/spine 后只拦截真正覆盖正文的目标，
            // 避免把可正常阅读的书误判为 DRM。
            val encryptionTargets = readEntry(archive, ENCRYPTION_PATH, MAX_XML_BYTES)
                ?.let(::parseEncryptionTargets)
                .orEmpty()
            val opfBaseDir = opfPath.substringBeforeLast('/', "")
            if (encryptionTargets.isNotEmpty() &&
                encryptionTargetsOverlap(
                    archive = archive,
                    targets = encryptionTargets,
                    opfBaseDir = opfBaseDir,
                    watchedPaths = setOf(opfPath, CONTAINER_PATH),
                )
            ) {
                throw BookParseException.Encrypted()
            }

            // 2. OPF：元数据、manifest、spine。
            val opfBytes = readEntry(archive, opfPath, MAX_XML_BYTES)
                ?: throw BookParseException.Corrupted("EPUB 缺少 OPF 文件")
            val opf = parseXml(opfBytes)

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

            // 只有 spine 里的正文文档被加密才会影响阅读；字体、图片、CSS 即使被
            // 加密（或加密清单指向不存在的条目）也无需解密，直接继续抽取正文。
            val spineContentPaths = spineIds.mapNotNull { manifest[it] }
                .filter { it.mediaType in TEXT_MEDIA_TYPES }
                .map { resolvePath(opfBaseDir, it.href) }
                .filter { it.isNotBlank() }
                .toSet()
            if (spineContentPaths.isNotEmpty() &&
                encryptionTargetsOverlap(
                    archive = archive,
                    targets = encryptionTargets,
                    opfBaseDir = opfBaseDir,
                    watchedPaths = spineContentPaths,
                )
            ) {
                throw BookParseException.Encrypted()
            }

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
                // 标准 EPUB2 的 NCX 常带 W3C DTD 声明；外部 DTD 仍被禁用，
                // 只放开 DOCTYPE 语法，避免合法的旧书全部解析失败。
                val ncx = parseXml(ncxBytes, allowDoctype = true)
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
    // 加密清单
    // ---------------------------------------------------------------------

    /**
     * 解析 encryption.xml 中被声明的加密目标 URI。
     *
     * 只收集 URI、不立即拒绝：OCF 规范要求 CipherReference 相对容器根，但部分生成器
     * 写成相对 OPF；目标还可能根本不在 ZIP 中（陈旧或伪装的加密清单）。返回空字符串
     * 表示 EncryptedData 缺少可用 URI，无法判断保护范围，由调用方按加密处理。
     */
    private fun parseEncryptionTargets(bytes: ByteArray): List<String> {
        val document = try {
            parseXml(bytes)
        } catch (_: BookParseException.Corrupted) {
            // 清单本身损坏时无法确认正文是否受影响，保守地继续按加密处理。
            throw BookParseException.Encrypted("EPUB 加密信息无法解析")
        }
        return elementsByLocalName(document, "EncryptedData").map { encryptedData ->
            elementsByLocalName(encryptedData, "CipherReference").firstOrNull()
                ?.getAttribute("URI")
                ?.trim()
                .orEmpty()
        }
    }

    /**
     * 判断加密目标是否覆盖 [watchedPaths] 中真实存在的资源。
     *
     * 只有命中 spine 正文 / OPF / container 才拦截；目标缺失或只涉及字体、图片、CSS 时
     * 不影响可重排文字抽取，继续导入。空 URI 表示保护范围未知，按加密处理。
     */
    private fun encryptionTargetsOverlap(
        archive: ZipFile,
        targets: List<String>,
        opfBaseDir: String,
        watchedPaths: Set<String>,
    ): Boolean {
        if (watchedPaths.isEmpty()) return false
        return targets.any { uri ->
            if (uri.isBlank()) return@any true
            encryptionCandidates(uri, opfBaseDir).any { candidate ->
                candidate in watchedPaths && archive.getEntry(candidate)?.isDirectory == false
            }
        }
    }

    /**
     * 生成加密 URI 的候选 ZIP 路径。
     *
     * 同时尝试容器根相对与 OPF 相对两种解释；只有真实存在的条目才参与判定，
     * 因此指向不存在文件的陈旧清单不会误伤整本书。
     */
    private fun encryptionCandidates(uri: String, opfBaseDir: String): List<String> {
        val raw = uri.substringBefore('#').substringBefore('?').trim().replace('\\', '/')
        if (raw.isBlank()) return emptyList()
        if (raw.startsWith("http:", true) || raw.startsWith("https:", true) ||
            raw.startsWith("data:", true) || raw.startsWith("mailto:", true)
        ) {
            return emptyList()
        }
        val decoded = decodePath(raw)
        val rootRelative = normalizeContainerPath(decoded)
        val opfRelative = if (opfBaseDir.isBlank()) rootRelative else resolvePath(opfBaseDir, decoded)
        return listOf(rootRelative, opfRelative).filter { it.isNotBlank() }.distinct()
    }

    /** 容器根相对路径归一化：去掉前导斜杠，处理 `.` 与 `..`，防止路径穿越。 */
    private fun normalizeContainerPath(raw: String): String {
        val parts = mutableListOf<String>()
        raw.trimStart('/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
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

    /**
     * 安全解析 EPUB 内部 XML。
     *
     * @param allowDoctype 仅 NCX 需要：标准 EPUB2 会声明 NCX DTD，严格禁用 DOCTYPE
     *   会把合法旧书误判为损坏。无论是否允许 DOCTYPE，都必须关闭外部实体/外部 DTD，
     *   并通过实体解析器拒绝一切外部请求，防止 XXE。
     */
    private fun parseXml(bytes: ByteArray, allowDoctype: Boolean = false): W3cDocument {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                // 不同 Android/JVM 实现支持的特性不同，逐个尝试开启安全开关。
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                if (!allowDoctype) {
                    runCatching {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    }
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
                // Android 的 XMLConstants 未暴露 ACCESS_EXTERNAL_* 常量，使用标准属性 URI。
                runCatching {
                    setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                }
                runCatching {
                    setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
                }
                // 部分平台的 SAX 特性支持不完整，这里再限制实体膨胀上限（失败则忽略）。
                runCatching {
                    setAttribute(
                        "http://www.oracle.com/xml/jaxp/properties/entityExpansionLimit",
                        "10000",
                    )
                }
            }
            factory.newDocumentBuilder().apply {
                // 最后一道防线：即使外部实体开关未生效，也不允许解析器访问网络/文件。
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }.parse(ByteArrayInputStream(bytes))
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
        const val ENCRYPTION_PATH = "META-INF/encryption.xml"
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
