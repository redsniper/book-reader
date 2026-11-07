package com.moyue.reader.domain.model

import kotlin.math.roundToInt

/**
 * 阅读排版设置。
 *
 * 这些值会同时影响：
 * - StaticLayout 分页测量（字号、行高、边距、段距）；
 * - Compose 文本渲染（必须使用相同参数才能保证分页准确）。
 */
data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.PARCHMENT,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.8f,
    val fontFamily: FontFamilyOption = FontFamilyOption.SERIF,
    val pageMode: PageMode = PageMode.HORIZONTAL,
    val horizontalPaddingDp: Int = 24,
) {
    /**
     * 将持久化数据中可能越界/损坏的值收敛到合法范围。
     * DataStore 中的旧版本数据或手工修改数据不应导致分页崩溃。
     */
    fun sanitized(): ReaderSettings = copy(
        fontSizeSp = fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP).roundToInt().toFloat(),
        lineHeightMultiplier = lineHeightMultiplier
            .coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)
            .let { (it * 10).roundToInt() / 10f },
        horizontalPaddingDp = horizontalPaddingDp.coerceIn(MIN_PADDING_DP, MAX_PADDING_DP),
    )

    companion object {
        const val MIN_FONT_SIZE_SP = 14f
        const val MAX_FONT_SIZE_SP = 32f
        const val MIN_LINE_HEIGHT = 1.2f
        const val MAX_LINE_HEIGHT = 2.4f
        const val MIN_PADDING_DP = 16
        const val MAX_PADDING_DP = 40
    }
}
