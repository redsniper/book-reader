package com.moyue.reader.ui.external

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.moyue.reader.R

/**
 * 外部打开流程的根级浮层：导入进度 + 错误/重复提示。
 *
 * 放在 NavHost 之外，因此无论在书架、设置还是阅读器页面触发“用其他应用打开”，
 * 用户都能看到进度与结果；对话框由独立窗口承载，不参与页面布局与返回栈。
 */
@Composable
fun ExternalOpenOverlays(
    state: ExternalOpenUiState,
    onDismissError: () -> Unit,
) {
    if (state.importing) {
        val name = state.importingName
        // 不可取消：此时文件正在复制/解析，中途关闭对话框只会让用户误以为没反应。
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (name.isNullOrBlank()) {
                            stringResource(R.string.external_open_importing_unknown)
                        } else {
                            stringResource(R.string.external_open_importing, name)
                        },
                    )
                }
            },
        )
    }

    val error = state.error
    if (error != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.external_open_error_title)) },
            text = { Text(stringResource(error.resId, *error.args.toTypedArray())) },
            confirmButton = {
                TextButton(onClick = onDismissError) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }
}
