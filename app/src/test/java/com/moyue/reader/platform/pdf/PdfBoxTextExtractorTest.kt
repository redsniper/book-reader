package com.moyue.reader.platform.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.parser.BookParseException
import com.moyue.reader.domain.parser.ParseRequest
import com.moyue.reader.domain.service.ChapterSplitter
import com.moyue.reader.data.parser.PdfParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 使用 PDFBox 生成真实 PDF，验证平台适配器的文本提取与 PdfParser 的扫描版判定。
 * 这些测试需要 Android 资源（标准字体），使用 Robolectric 运行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfBoxTextExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun `extracts text from generated text pdf`() {
        val file = tempFolder.newFile("text.pdf")
        createTextPdf(file)
        val extraction = PdfBoxTextExtractor().extract(file)

        assertEquals(2, extraction.pageCount)
        assertEquals(2, extraction.pageTexts.size)
        assertTrue(extraction.pageTexts[0].contains("Chapter 1"))
        assertTrue(extraction.pageTexts[1].contains("Chapter 2"))
    }

    @Test
    fun `real text pdf is reflowed not treated as scanned`() = runTest {
        val file = tempFolder.newFile("text-reflow.pdf")
        createTextPdf(file)
        val parser = PdfParser(PdfBoxTextExtractor(), ChapterSplitter())

        val parsed = parser.parse(ParseRequest(file, "text-reflow.pdf", BookFormat.PDF))

        assertFalse(parsed.isScannedPdf)
        assertTrue(parsed.chapters.size >= 2)
        assertTrue(parsed.chapters.first().title.contains("Chapter"))
    }

    @Test
    fun `encrypted pdf maps to domain encrypted error`() {
        val file = tempFolder.newFile("encrypted.pdf")
        createTextPdf(file, encrypted = true)

        val error = runCatching { PdfBoxTextExtractor().extract(file) }.exceptionOrNull()
        assertTrue(error is BookParseException.Encrypted)
    }

    private fun createTextPdf(file: File, encrypted: Boolean = false) {
        PDDocument().use { document ->
            val page1 = PDPage().apply { document.addPage(this) }
            val page2 = PDPage().apply { document.addPage(this) }
            writeText(
                document,
                page1,
                listOf(
                    "Chapter 1",
                    "The wind in the mountains was light, and the boy carried a sword.",
                    "He walked down the stone steps and heard a bell in the distance.",
                ),
            )
            writeText(
                document,
                page2,
                listOf(
                    "Chapter 2",
                    "He looked back once. The mountain gate was lost in the mist.",
                    "It looked like an old painting wet by the rain.",
                ),
            )
            if (encrypted) {
                val policy = StandardProtectionPolicy(
                    "owner-password",
                    "user-password",
                    AccessPermission(),
                ).apply { encryptionKeyLength = 128 }
                document.protect(policy)
            }
            document.save(file)
        }
    }

    private fun writeText(
        document: PDDocument,
        page: PDPage,
        lines: List<String>,
    ) {
        PDPageContentStream(document, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 12f)
            stream.newLineAtOffset(50f, 720f)
            lines.forEach { line ->
                stream.showText(line)
                stream.newLineAtOffset(0f, -20f)
            }
            stream.endText()
        }
    }
}
