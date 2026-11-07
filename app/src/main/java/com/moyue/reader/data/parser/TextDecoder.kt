package com.moyue.reader.data.parser

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本解码结果。
 *
 * [hadErrors] 为 true 表示最终使用了替换字符兜底解码，UI 可提示部分字符异常。
 */
data class DecodedText(
    val text: String,
    val charsetName: String,
    val hadErrors: Boolean,
)

/**
 * TXT 编码探测。
 *
 * 顺序：
 * 1. BOM（UTF-8 / UTF-16LE / UTF-16BE）；
 * 2. 无 BOM 时根据 NUL 字节分布判断 UTF-16；
 * 3. 严格 UTF-8；
 * 4. GB18030（兼容 GBK/GB2312，覆盖大量中文老书）；
 * 5. UTF-8 replacement 兜底。
 *
 * 该实现不依赖 Android，便于用真实字节夹具做单元测试。
 */
object TextDecoder {

    private val UTF_8: Charset = Charsets.UTF_8
    private val UTF_16_LE: Charset = Charsets.UTF_16LE
    private val UTF_16_BE: Charset = Charsets.UTF_16BE
    private val GB18030: Charset = runCatching { Charset.forName("GB18030") }
        .getOrElse { Charset.forName("GBK") }

    fun decode(bytes: ByteArray): DecodedText {
        if (bytes.isEmpty()) return DecodedText("", "UTF-8", false)

        // 1. BOM 优先。
        when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> {
                val text = decodeStrict(bytes.copyOfRange(3, bytes.size), UTF_8)
                return DecodedText(text ?: decodeReplacement(bytes, 3, UTF_8), "UTF-8", text == null)
            }

            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
                val text = decodeStrict(bytes.copyOfRange(2, bytes.size), UTF_16_LE)
                return DecodedText(text ?: decodeReplacement(bytes, 2, UTF_16_LE), "UTF-16LE", text == null)
            }

            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
                val text = decodeStrict(bytes.copyOfRange(2, bytes.size), UTF_16_BE)
                return DecodedText(text ?: decodeReplacement(bytes, 2, UTF_16_BE), "UTF-16BE", text == null)
            }
        }

        // 2. 无 BOM 的 UTF-16 猜测（某些旧程序导出的 TXT）。
        detectUtf16(bytes)?.let { charset ->
            val text = decodeStrict(bytes, charset)
            if (text != null) {
                return DecodedText(text, charset.name(), false)
            }
        }

        // 3. 严格 UTF-8。
        decodeStrict(bytes, UTF_8)?.let { return DecodedText(it, "UTF-8", false) }

        // 4. GB18030。
        decodeStrict(bytes, GB18030)?.let { return DecodedText(it, GB18030.name(), false) }

        // 5. 兜底：替换非法字节，保证至少能读。
        return DecodedText(decodeReplacement(bytes, 0, UTF_8), "UTF-8", true)
    }

    private fun detectUtf16(bytes: ByteArray): Charset? {
        val sampleSize = minOf(bytes.size, 4096)
        if (sampleSize < 8) return null
        var evenNuls = 0
        var oddNuls = 0
        for (index in 0 until sampleSize) {
            if (bytes[index] == 0.toByte()) {
                if (index % 2 == 0) evenNuls++ else oddNuls++
            }
        }
        val totalNuls = evenNuls + oddNuls
        if (totalNuls < sampleSize / 10) return null
        return when {
            oddNuls > evenNuls * 3 -> UTF_16_LE
            evenNuls > oddNuls * 3 -> UTF_16_BE
            else -> null
        }
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun decodeReplacement(bytes: ByteArray, offset: Int, charset: Charset): String =
        String(bytes, offset, bytes.size - offset, charset).removePrefix("\uFEFF")
}
