package com.moyue.reader.platform.pdf

import com.moyue.reader.domain.parser.BookParseException
import com.moyue.reader.domain.parser.PdfTextExtraction
import com.moyue.reader.domain.parser.PdfTextExtractor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException

/**
 * PDFBox-Android 文本提取适配器。
 *
 * 每次只提取一页，避免对超长 PDF 构造超大字符串；
 * 若文档加密，转换为领域异常，由导入服务提示用户。
 */
class PdfBoxTextExtractor : PdfTextExtractor {

    override fun extract(file: File): PdfTextExtraction {
        if (!file.isFile) throw BookParseException.Corrupted("PDF 文件不存在")
        val document = try {
            PDDocument.load(file)
        } catch (e: InvalidPasswordException) {
            throw BookParseException.Encrypted("该 PDF 需要密码")
        } catch (e: IOException) {
            throw BookParseException.Corrupted("PDF 读取失败", e)
        } catch (e: Exception) {
            throw BookParseException.Corrupted("PDF 解析失败", e)
        }

        return document.use { doc ->
            if (doc.isEncrypted) {
                throw BookParseException.Encrypted("该 PDF 受密码保护")
            }
            val pageCount = doc.numberOfPages
            if (pageCount <= 0) throw BookParseException.Corrupted("PDF 没有页面")
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
            }
            val pageTexts = ArrayList<String>(pageCount)
            for (page in 1..pageCount) {
                stripper.startPage = page
                stripper.endPage = page
                pageTexts.add(stripper.getText(doc).orEmpty())
            }
            val info = doc.documentInformation
            PdfTextExtraction(
                pageCount = pageCount,
                pageTexts = pageTexts,
                title = info?.title?.takeIf { it.isNotBlank() },
                author = info?.author?.takeIf { it.isNotBlank() },
            )
        }
    }
}
