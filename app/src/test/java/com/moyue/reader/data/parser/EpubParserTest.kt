package com.moyue.reader.data.parser

import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.parser.BookParseException
import com.moyue.reader.domain.parser.ParseRequest
import com.moyue.reader.domain.service.ChapterSplitter
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val parser = EpubParser(ChapterSplitter())

    @Test
    fun `parses epub3 metadata nav and chapters`() = runTest {
        val file = tempFolder.newFile("book.epub")
        writeEpub3(file, withCover = true)

        val parsed = parser.parse(ParseRequest(file, "book.epub", BookFormat.EPUB))

        assertEquals("测试之书", parsed.title)
        assertEquals("某某", parsed.author)
        assertEquals(2, parsed.chapters.size)
        assertEquals(listOf("第一章 初见", "第二章 再会"), parsed.chapters.map { it.title })
        assertTrue(parsed.chapters[0].text.contains("山里的风很轻"))
        assertTrue(parsed.chapters[1].text.contains("他下山了"))
        // 正文中的重复章节标题被去除。
        assertFalse(parsed.chapters[0].text.startsWith("第一章"))
        assertEquals("fake-cover", parsed.coverBytes?.let { String(it) })
    }

    @Test
    fun `parses epub2 ncx toc`() = runTest {
        val file = tempFolder.newFile("book2.epub")
        writeEpub2(file)

        val parsed = parser.parse(ParseRequest(file, "book2.epub", BookFormat.EPUB))

        assertEquals(2, parsed.chapters.size)
        assertEquals(listOf("第一章 初见", "第二章 再会"), parsed.chapters.map { it.title })
    }

    @Test
    fun `rejects encrypted epub`() = runTest {
        val file = tempFolder.newFile("encrypted.epub")
        // EncryptedData 没有 CipherReference：保护范围未知，保守拒绝。
        writeEpub3(
            file,
            encryptionXml = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData/></encryption>""",
        )

        val error = runCatching {
            parser.parse(ParseRequest(file, "encrypted.epub", BookFormat.EPUB))
        }.exceptionOrNull()
        assertTrue(error is BookParseException.Encrypted)
        assertTrue(error!!.message!!.isNotEmpty())
    }

    @Test
    fun `allows stale encryption manifest pointing to missing entry`() = runTest {
        // 多看等工具生成的书会带一份指向不存在 encrypted_data.xml 的清单，
        // 正文实际是明文；不能因为 EncryptedData 字样就整本拒绝。
        val file = tempFolder.newFile("stale-encryption.epub")
        writeEpub3(
            file,
            encryptionXml = encryptionXml(uri = "encrypted_data.xml"),
        )

        val parsed = parser.parse(ParseRequest(file, "stale.epub", BookFormat.EPUB))

        assertEquals(2, parsed.chapters.size)
        assertTrue(parsed.chapters[0].text.contains("山里的风很轻"))
    }

    @Test
    fun `allows font obfuscation declared in encryption manifest`() = runTest {
        // PDFBox 之外的常见 EPUB 会用 IDPF 算法混淆内嵌字体；本阅读器不渲染原书字体，
        // 跳过字体资源即可正常抽取正文。
        val file = tempFolder.newFile("font-obfuscated.epub")
        writeEpub3(
            file,
            encryptionXml = encryptionXml(
                uri = "OEBPS/Fonts/font.ttf",
                algorithm = "http://www.idpf.org/2008/embedding",
            ),
            extraEntries = mapOf("OEBPS/Fonts/font.ttf" to "fake-font"),
        )

        val parsed = parser.parse(ParseRequest(file, "font.epub", BookFormat.EPUB))

        assertEquals(2, parsed.chapters.size)
        assertTrue(parsed.chapters[1].text.contains("他下山了"))
    }

    @Test
    fun `rejects epub when spine content is encrypted`() = runTest {
        // 只写 OPF 相对路径，验证两种路径解释都能识别正文加密。
        val file = tempFolder.newFile("encrypted-content.epub")
        writeEpub3(
            file,
            encryptionXml = encryptionXml(uri = "ch1.xhtml"),
        )

        val error = runCatching {
            parser.parse(ParseRequest(file, "encrypted-content.epub", BookFormat.EPUB))
        }.exceptionOrNull()
        assertTrue(error is BookParseException.Encrypted)
    }

    @Test
    fun `rejects non zip file`() = runTest {
        val file = tempFolder.newFile("bad.epub")
        file.writeText("not a zip")

        val error = runCatching {
            parser.parse(ParseRequest(file, "bad.epub", BookFormat.EPUB))
        }.exceptionOrNull()
        assertTrue(error is BookParseException.Corrupted)
    }

    @Test
    fun `rejects epub without readable text`() = runTest {
        val file = tempFolder.newFile("empty.epub")
        writeEpub3(file, withText = false)

        val error = runCatching {
            parser.parse(ParseRequest(file, "empty.epub", BookFormat.EPUB))
        }.exceptionOrNull()
        assertTrue(error is BookParseException.NoReadableText)
    }

    private fun writeEpub3(
        file: File,
        encryptionXml: String? = null,
        withCover: Boolean = false,
        withText: Boolean = true,
        extraEntries: Map<String, String> = emptyMap(),
    ) {
        val text1 = if (withText) "山里的风很轻，少年背剑下山。他听见远处有人喊他。" else ""
        val text2 = if (withText) "他下山了，回头看了一眼。山门在雾里，像一幅旧画。" else ""
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">test-book</dc:identifier>
                <dc:title>测试之书</dc:title>
                <dc:creator>某某</dc:creator>
                <dc:language>zh-CN</dc:language>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                ${if (withCover) """<item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>""" else ""}
              </manifest>
              <spine>
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent()

        val nav = """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <head><title>目录</title></head>
              <body>
                <nav epub:type="toc">
                  <ol>
                    <li><a href="ch1.xhtml">第一章 初见</a></li>
                    <li><a href="ch2.xhtml">第二章 再会</a></li>
                  </ol>
                </nav>
              </body>
            </html>
        """.trimIndent()

        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to containerXml(),
            "OEBPS/content.opf" to opf,
            "OEBPS/nav.xhtml" to nav,
            "OEBPS/ch1.xhtml" to chapterXhtml("第一章 初见", text1),
            "OEBPS/ch2.xhtml" to chapterXhtml("第二章 再会", text2),
        )
        if (withCover) entries["OEBPS/cover.jpg"] = "fake-cover"
        if (encryptionXml != null) entries["META-INF/encryption.xml"] = encryptionXml
        entries.putAll(extraEntries)
        writeZip(file, entries)
    }

    private fun encryptionXml(
        uri: String,
        algorithm: String = "http://www.w3.org/2001/04/xmlenc#aes256-cbc",
    ): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                    xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
          <enc:EncryptedData>
            <enc:EncryptionMethod Algorithm="$algorithm"/>
            <enc:CipherData>
              <enc:CipherReference URI="$uri"/>
            </enc:CipherData>
          </enc:EncryptedData>
        </encryption>
    """.trimIndent()

    private fun writeEpub2(file: File) {
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">test-book-2</dc:identifier>
                <dc:title>旧书</dc:title>
                <dc:creator>旧作者</dc:creator>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent()
        val ncx = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN"
              "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="n1" playOrder="1">
                  <navLabel><text>第一章 初见</text></navLabel>
                  <content src="ch1.xhtml"/>
                </navPoint>
                <navPoint id="n2" playOrder="2">
                  <navLabel><text>第二章 再会</text></navLabel>
                  <content src="ch2.xhtml"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent()
        writeZip(
            file,
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to containerXml(),
                "OEBPS/content.opf" to opf,
                "OEBPS/toc.ncx" to ncx,
                "OEBPS/ch1.xhtml" to chapterXhtml("第一章 初见", "山风很轻。"),
                "OEBPS/ch2.xhtml" to chapterXhtml("第二章 再会", "他下山了。"),
            ),
        )
    }

    private fun containerXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private fun chapterXhtml(title: String, text: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>$title</title></head>
          <body>
            <h1>$title</h1>
            ${if (text.isNotBlank()) "<p>$text</p><p>第二段。</p>" else ""}
          </body>
        </html>
    """.trimIndent()

    private fun writeZip(file: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
