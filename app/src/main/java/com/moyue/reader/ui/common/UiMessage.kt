package com.moyue.reader.ui.common

import androidx.annotation.StringRes

/**
 * UI 消息：ViewModel 只携带字符串资源与参数，不直接依赖 Context。
 * Compose 层通过 stringResource(resId, *args) 渲染。
 */
data class UiMessage(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList(),
)
