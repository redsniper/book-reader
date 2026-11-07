package com.moyue.reader.ui.shelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyue.reader.AppContainer
import com.moyue.reader.R
import com.moyue.reader.core.Clock
import com.moyue.reader.domain.model.Book
import com.moyue.reader.domain.model.ShelfItem
import com.moyue.reader.domain.repository.ShelfRepository
import com.moyue.reader.domain.service.BookImportService
import com.moyue.reader.domain.service.HiddenShelfManager
import com.moyue.reader.domain.service.HiddenShelfSession
import com.moyue.reader.domain.service.ImportError
import com.moyue.reader.domain.service.ImportResult
import com.moyue.reader.domain.service.UnlockThrottle
import com.moyue.reader.ui.common.UiMessage
import kotlin.math.ceil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ShelfMode { NORMAL, HIDDEN }

data class ImportProgress(
    val current: Int,
    val total: Int,
    val currentName: String,
)

data class ShelfUiState(
    val mode: ShelfMode = ShelfMode.NORMAL,
    val books: List<ShelfItem> = emptyList(),
    val loading: Boolean = true,
    val loadError: Boolean = false,
    val passwordSet: Boolean = false,
    val unlockDialogVisible: Boolean = false,
    val unlockError: UiMessage? = null,
    val unlockCooldownSeconds: Int = 0,
    val setPasswordPromptVisible: Boolean = false,
    val editingBook: Book? = null,
    val deleteConfirmBook: Book? = null,
    val importProgress: ImportProgress? = null,
    val message: UiMessage? = null,
)

/**
 * 书架 ViewModel。
 *
 * 普通书架与隐藏书架复用同一个列表，仅切换 hidden 查询条件；
 * 隐藏模式属于内存 UI 状态，不写入 SavedState，进程重启后自然回到普通书架。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModel(
    private val repository: ShelfRepository,
    private val importService: BookImportService,
    private val hiddenShelfManager: HiddenShelfManager,
    private val hiddenShelfSession: HiddenShelfSession,
    private val documentResolver: DocumentResolver,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShelfUiState())
    val uiState: StateFlow<ShelfUiState> = _uiState.asStateFlow()

    private val mode = MutableStateFlow(ShelfMode.NORMAL)

    private val unlockThrottle = UnlockThrottle(clock = clock)
    private var cooldownJob: Job? = null

    init {
        refreshPasswordState()
        viewModelScope.launch {
            mode.flatMapLatest { currentMode ->
                repository.observeShelf(hidden = currentMode == ShelfMode.HIDDEN)
                    .map<List<ShelfItem>, Pair<List<ShelfItem>, Throwable?>> { it to null }
                    .catch { error -> emit(emptyList<ShelfItem>() to error) }
            }.collect { (books, error) ->
                _uiState.update { state ->
                    state.copy(
                        books = books,
                        loading = false,
                        loadError = error != null,
                    )
                }
            }
        }
    }

    fun refreshPasswordState() {
        _uiState.update { it.copy(passwordSet = hiddenShelfManager.isPasswordSet()) }
    }

    // ---------------------------------------------------------------------
    // 导入
    // ---------------------------------------------------------------------

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var imported = 0
            var failed = 0
            var firstFailure: UiMessage? = null
            _uiState.update {
                it.copy(
                    importProgress = ImportProgress(0, uris.size, ""),
                    message = null,
                )
            }

            uris.forEachIndexed { index, uri ->
                val request = try {
                    documentResolver.resolve(uri)
                } catch (e: Exception) {
                    failed++
                    firstFailure = firstFailure ?: UiMessage(R.string.import_error_io)
                    _uiState.update {
                        it.copy(importProgress = ImportProgress(index + 1, uris.size, ""))
                    }
                    return@forEachIndexed
                }

                _uiState.update {
                    it.copy(
                        importProgress = ImportProgress(
                            current = index + 1,
                            total = uris.size,
                            currentName = request.displayName,
                        ),
                    )
                }

                when (val result = importService.import(request)) {
                    is ImportResult.Imported -> imported++
                    is ImportResult.Duplicate -> {
                        failed++
                        firstFailure = firstFailure ?: UiMessage(
                            R.string.shelf_message_duplicate,
                            listOf(result.existingTitle),
                        )
                    }

                    is ImportResult.Failed -> {
                        failed++
                        firstFailure = firstFailure ?: result.error.toUiMessage()
                    }
                }
            }

            val message = when {
                failed == 0 -> UiMessage(R.string.shelf_message_imported, listOf(imported))
                imported == 0 && failed == uris.size -> firstFailure
                else -> UiMessage(
                    R.string.shelf_message_import_summary,
                    listOf(imported, failed),
                )
            }
            _uiState.update {
                it.copy(importProgress = null, message = message)
            }
        }
    }

    private fun ImportError.toUiMessage(): UiMessage = when (this) {
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

    // ---------------------------------------------------------------------
    // 隐藏书架
    // ---------------------------------------------------------------------

    /** UI 下拉达到阈值后调用。 */
    fun onPullDownTriggered() {
        if (_uiState.value.mode == ShelfMode.HIDDEN) return
        refreshPasswordState()
        if (_uiState.value.passwordSet) {
            _uiState.update {
                it.copy(
                    unlockDialogVisible = true,
                    unlockError = null,
                    unlockCooldownSeconds = ceil(
                        unlockThrottle.remainingCooldownMillis() / 1000.0,
                    ).toInt(),
                )
            }
            if (_uiState.value.unlockCooldownSeconds > 0) startCooldownTicker()
        } else {
            _uiState.update { it.copy(setPasswordPromptVisible = true) }
        }
    }

    fun dismissUnlockDialog() {
        _uiState.update { it.copy(unlockDialogVisible = false, unlockError = null) }
    }

    fun dismissSetPasswordPrompt() {
        _uiState.update { it.copy(setPasswordPromptVisible = false) }
    }

    fun confirmUnlock(password: String) {
        if (_uiState.value.unlockCooldownSeconds > 0) {
            _uiState.update {
                it.copy(
                    unlockError = UiMessage(
                        R.string.shelf_message_cooldown,
                        listOf(it.unlockCooldownSeconds),
                    ),
                )
            }
            return
        }
        if (password.isEmpty()) {
            _uiState.update { it.copy(unlockError = UiMessage(R.string.shelf_message_wrong_password)) }
            return
        }

        viewModelScope.launch {
            val ok = hiddenShelfManager.verify(password.toCharArray())
            if (ok) {
                unlockThrottle.recordSuccess()
                cooldownJob?.cancel()
                hiddenShelfSession.unlock()
                // 先切换到 loading 状态，再切换查询条件，避免 flow 的同步发射被随后的
                // emptyList 覆盖（Unconfined 测试/快速调度下会触发）。
                _uiState.update {
                    it.copy(
                        mode = ShelfMode.HIDDEN,
                        unlockDialogVisible = false,
                        unlockError = null,
                        unlockCooldownSeconds = 0,
                        books = emptyList(),
                        loading = true,
                        message = UiMessage(R.string.shelf_message_unlocked),
                    )
                }
                mode.value = ShelfMode.HIDDEN
            } else {
                val remaining = unlockThrottle.recordFailure()
                val seconds = ceil(remaining / 1000.0).toInt()
                _uiState.update {
                    it.copy(
                        unlockError = if (seconds > 0) {
                            UiMessage(R.string.shelf_message_cooldown, listOf(seconds))
                        } else {
                            UiMessage(R.string.shelf_message_wrong_password)
                        },
                        unlockCooldownSeconds = seconds,
                    )
                }
                if (seconds > 0) startCooldownTicker()
            }
        }
    }

    private fun startCooldownTicker() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            while (true) {
                val seconds = ceil(unlockThrottle.remainingCooldownMillis() / 1000.0).toInt()
                _uiState.update {
                    it.copy(
                        unlockCooldownSeconds = seconds,
                        unlockError = if (seconds > 0) {
                            UiMessage(R.string.shelf_message_cooldown, listOf(seconds))
                        } else {
                            null
                        },
                    )
                }
                if (seconds <= 0) break
                delay(1_000)
            }
        }
    }

    fun exitHiddenShelf() {
        hiddenShelfSession.lock()
        _uiState.update {
            it.copy(
                mode = ShelfMode.NORMAL,
                books = emptyList(),
                loading = true,
                message = UiMessage(R.string.shelf_message_exited_hidden),
            )
        }
        mode.value = ShelfMode.NORMAL
    }

    // ---------------------------------------------------------------------
    // 编辑 / 隐藏 / 删除
    // ---------------------------------------------------------------------

    fun openEditor(book: Book) {
        _uiState.update { it.copy(editingBook = book) }
    }

    fun dismissEditor() {
        _uiState.update { it.copy(editingBook = null) }
    }

    fun toggleHidden(book: Book) {
        viewModelScope.launch {
            runCatching { repository.setHidden(book.id, !book.hidden) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            editingBook = null,
                            message = if (book.hidden) {
                                UiMessage(R.string.shelf_message_unhidden, listOf(book.title))
                            } else {
                                UiMessage(R.string.shelf_message_hidden, listOf(book.title))
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            editingBook = null,
                            message = UiMessage(
                                R.string.shelf_message_operation_failed,
                                listOf(error.message ?: "unknown"),
                            ),
                        )
                    }
                }
        }
    }

    fun requestDelete(book: Book) {
        _uiState.update { it.copy(deleteConfirmBook = book) }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(deleteConfirmBook = null) }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            runCatching { repository.deleteBook(book.id) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            deleteConfirmBook = null,
                            editingBook = null,
                            message = UiMessage(R.string.shelf_message_deleted, listOf(book.title)),
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            deleteConfirmBook = null,
                            message = UiMessage(
                                R.string.shelf_message_operation_failed,
                                listOf(error.message ?: "unknown"),
                            ),
                        )
                    }
                }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    override fun onCleared() {
        cooldownJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(
            container: AppContainer,
            documentResolver: DocumentResolver,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ShelfViewModel(
                    repository = container.shelfRepository,
                    importService = container.importService,
                    hiddenShelfManager = container.hiddenShelfManager,
                    hiddenShelfSession = container.hiddenShelfSession,
                    documentResolver = documentResolver,
                    clock = container.clock,
                )
            }
        }
    }
}
