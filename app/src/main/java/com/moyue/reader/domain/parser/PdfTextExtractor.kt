package com.moyue.reader.domain.parser

import java.io.File

/** PDF 文本层提取结果（由平台适配器实现）。 */
data class PdfTextExtraction(
    val pageCount: Int,
    val pageTexts: List<String>,
    val title: String?,
    val author: String?,
)

/**
 * PDF 文本提取接口。
 *
 * 使用接口而非直接依赖 PDFBox，方便：
 * - 纯 JVM 测试扫描版判定与分章逻辑；
 * - 将来替换为其他 PDF 引擎。
 *
 * 实现应将加密、损坏等异常转换为 [BookParseException]。
 */
interface PdfTextExtractor {
    fun extract(file: File): PdfTextExtraction
}
