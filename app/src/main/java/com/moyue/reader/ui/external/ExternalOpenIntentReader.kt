package com.moyue.reader.ui.external

import android.content.Intent
import com.moyue.reader.core.ExternalOpenRequest

/**
 * 把系统的 `ACTION_VIEW` Intent 解析成外部打开请求。
 *
 * 只做“是否值得处理”的判定，不访问 ContentResolver：
 * 1. action 必须是 `ACTION_VIEW`（桌面图标是 MAIN、分享是 SEND，均忽略）；
 * 2. 必须带 data，且 scheme 为 `content` 或 `file`——http(s) 链接不是可导入的本地文件；
 * 3. 保留发送方给的 MIME 作为提示：部分文件管理器 `getType()` 返回 null，
 *    但 Intent 上带了类型，导入服务识别格式时会用到。
 *
 * 这里无法校验文件是否真实可读：读权限随 Intent 一次性授予，真正的读取
 * 交给导入流水线的 `openStream`，失败时按 IO 错误展示。
 */
object ExternalOpenIntentReader {

    /** 允许的 URI scheme。`file://` 仅用于兼容老式发送方，新应用普遍使用 `content://`。 */
    private val SUPPORTED_SCHEMES = setOf("content", "file")

    fun read(intent: Intent?): ExternalOpenRequest? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        val scheme = data.scheme?.lowercase() ?: return null
        if (scheme !in SUPPORTED_SCHEMES) return null
        val uri = data.toString()
        if (uri.isBlank()) return null
        return ExternalOpenRequest(
            uri = uri,
            mimeHint = intent.type?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
