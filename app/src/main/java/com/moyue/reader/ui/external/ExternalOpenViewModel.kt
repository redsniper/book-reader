package com.moyue.reader.ui.external

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyue.reader.AppContainer
import com.moyue.reader.R
import com.moyue.reader.core.DiagnosticLogger
import com.moyue.reader.core.ExternalOpenQueue
import com.moyue.reader.core.ExternalOpenRequest
import com.moyue.reader.domain.service.BookImportService
import com.moyue.reader.domain.service.ImportResult
import com.moyue.reader.ui.common.DocumentResolver
import com.moyue.reader.ui.common.UiMessage
import com.moyue.reader.ui.common.toImportUiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 外部打开流程的 UI 状态。
 *
 * 只有一个“当前请求”的槽位：
 * - [importing] 为 true 时展示不可取消的进度对话框；
 * - [error] 非空时展示错误对话框（重复文件也走这里）；
 * - [openBookId] 非空表示导入成功、等待导航到阅读器，导航后由 [consumeOpen] 清空。
 */
data class ExternalOpenUiState(
    val importing: Boolean = false,
    val importingName: String? = null,
    val error: UiMessage? = null,
    val openBookId: String? = null,
)

/**
 * “用其他应用打开”的根级 ViewModel。
 *
 * 为什么放在导航根而不是书架页：`ACTION_VIEW` 可能发生在任何页面（阅读器、设置页），
 * 必须在页面切换与配置变更期间持续消费队列；书架页的 ViewModel 在隐藏书架、
 * 阅读器页面上并不活跃，接不住请求。
 *
 * 串行性：队列是单消费者 `Channel`，`collect` 内部顺序执行，因此同一时刻只导入一本书，
 * 与设计“大文件禁止并发导入”一致。取消（页面离开）由 `viewModelScope` 传播，
 * 导入服务内部的重活都在可取消的 IO 上下文中。
 */
class ExternalOpenViewModel(
    private val queue: ExternalOpenQueue,
    private val importService: BookImportService,
    private val documentResolver: DocumentResolver,
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.None,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExternalOpenUiState())
    val uiState: StateFlow<ExternalOpenUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            queue.requests().collect { request -> handle(request) }
        }
    }

    /** 导航到阅读器后调用，避免返回书架时重复打开同一本书。 */
    fun consumeOpen() {
        _uiState.update { it.copy(openBookId = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun handle(request: ExternalOpenRequest) {
        val uri = Uri.parse(request.uri)
        val scheme = uri.scheme?.lowercase()
        // 只记录 scheme/MIME 等元数据；不写文件名、路径或书名。
        diagnostics.log(
            "external.open",
            mapOf("scheme" to scheme, "mime" to request.mimeHint),
        )
        if (scheme !in SUPPORTED_SCHEMES) {
            diagnostics.log("external.done", mapOf("result" to "unsupported"))
            _uiState.update {
                it.copy(
                    importing = false,
                    importingName = null,
                    error = UiMessage(R.string.external_open_unsupported_uri),
                )
            }
            return
        }

        // 先亮出进度框再解析元数据：部分 provider 的 query 较慢，用户需要立刻看到反馈。
        _uiState.update { it.copy(importing = true, importingName = null, error = null) }

        val resolved = try {
            documentResolver.resolve(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishWithError(UiMessage(R.string.import_error_io), result = "failed")
            return
        }

        _uiState.update { it.copy(importingName = resolved.displayName) }

        // provider 往往不返回 type；发送方在 Intent 上带的 type 是可靠的兜底。
        val importRequest = if (resolved.mimeType.isNullOrBlank() && request.mimeHint != null) {
            resolved.copy(mimeType = request.mimeHint)
        } else {
            resolved
        }

        val result = try {
            importService.import(importRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishWithError(
                UiMessage(R.string.import_error_unknown),
                result = "failed",
                size = importRequest.sizeBytes,
            )
            return
        }

        val doneFields = mapOf<String, Any?>(
            "result" to when (result) {
                is ImportResult.Imported -> "opened"
                is ImportResult.Duplicate -> "duplicate"
                is ImportResult.Failed -> "failed"
            },
            "size" to (importRequest.sizeBytes ?: -1),
        )
        diagnostics.log("external.done", doneFields)

        when (result) {
            is ImportResult.Imported -> _uiState.update {
                it.copy(importing = false, importingName = null, openBookId = result.bookId)
            }

            is ImportResult.Duplicate -> _uiState.update {
                it.copy(
                    importing = false,
                    importingName = null,
                    // 刻意不展示书名：命中的可能是 hidden=true 的书籍，普通书架的语境下
                    // 泄露书名会破坏隐藏书架的界面隐藏语义。
                    error = UiMessage(R.string.external_open_duplicate),
                )
            }

            is ImportResult.Failed -> _uiState.update {
                it.copy(
                    importing = false,
                    importingName = null,
                    error = result.error.toImportUiMessage(),
                )
            }
        }
    }

    private fun finishWithError(message: UiMessage, result: String, size: Long? = null) {
        diagnostics.log(
            "external.done",
            mapOf("result" to result, "size" to (size ?: -1)),
        )
        _uiState.update {
            it.copy(importing = false, importingName = null, error = message)
        }
    }

    companion object {
        private val SUPPORTED_SCHEMES = setOf("content", "file")

        fun factory(
            container: AppContainer,
            documentResolver: DocumentResolver,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ExternalOpenViewModel(
                    queue = container.externalOpenQueue,
                    importService = container.importService,
                    documentResolver = documentResolver,
                    diagnostics = container.diagnostics,
                )
            }
        }
    }
}
