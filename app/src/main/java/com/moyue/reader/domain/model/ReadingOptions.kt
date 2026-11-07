package com.moyue.reader.domain.model

/** 阅读模式：左右翻页、上下连续滚动。 */
enum class PageMode { HORIZONTAL, VERTICAL }

/** 内置阅读主题；颜色映射位于 UI 层的 ReaderThemeColors。 */
enum class ReaderTheme(val displayName: String) {
    PARCHMENT("羊皮纸"),
    NIGHT("夜读"),
    GREEN("青竹"),
    WHITE("宣白"),
}

/** 正文字体族。v1 使用系统字体族，不加载外部字体文件。 */
enum class FontFamilyOption(val displayName: String) {
    DEFAULT("默认"),
    SERIF("衬线"),
    SANS("无衬线"),
    MONOSPACE("等宽"),
}
