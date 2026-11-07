package com.moyue.reader.domain.service

/**
 * 文本规范化。
 *
 * 所有解析器在分章前调用，保证：
 * - 统一换行符；
 * - 去除 BOM、零宽空格、NUL 等不可见字符；
 * - 清理行尾空白；
 * - 最多保留一个空行作为段落分隔。
 *
 * 不改变语序、标点与段落数量，也保留全角缩进。
 */
object TextNormalizer {

    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        var text = raw
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u200B", "")
            .replace("\u0000", "")
            .replace('\u000C', '\n')

        // 逐行去除行尾空白；去除行首 ASCII 空格/制表符，但保留全角缩进。
        text = text.lineSequence()
            .joinToString("\n") { line ->
                line.trimStart(' ', '\t').trimEnd(' ', '\t')
            }

        // 合并三个及以上连续空行为一个空行。
        text = text.replace(Regex("\n[ \\t]*\n[ \\t]*\n+"), "\n\n")
        return text.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
    }
}
