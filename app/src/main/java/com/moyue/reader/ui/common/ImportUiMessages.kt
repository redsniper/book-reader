package com.moyue.reader.ui.common

import com.moyue.reader.R
import com.moyue.reader.domain.service.ImportError

/**
 * 导入失败原因 → 用户可见文案的集中映射。
 *
 * 书架导入与“用其他应用打开”共用同一套领域错误类型，文案必须一致：
 * 集中在这里可以避免两条入口对同一种失败给出不同措辞，也避免 ViewModel
 * 之间复制粘贴映射代码。UI 层只拿资源 id，不依赖 Context。
 */
fun ImportError.toImportUiMessage(): UiMessage = when (this) {
    ImportError.UnsupportedFormat -> UiMessage(R.string.import_error_unsupported)
    is ImportError.FileTooLarge -> UiMessage(
        R.string.import_error_too_large,
        listOf((limitBytes / (1024 * 1024)).toInt()),
    )

    ImportError.Encrypted -> UiMessage(R.string.import_error_encrypted)
    ImportError.Corrupted -> UiMessage(R.string.import_error_corrupted)
    ImportError.NoReadableText -> UiMessage(R.string.import_error_no_text)
    ImportError.Io -> UiMessage(R.string.import_error_io)
    ImportError.Unknown -> UiMessage(R.string.import_error_unknown)
}
