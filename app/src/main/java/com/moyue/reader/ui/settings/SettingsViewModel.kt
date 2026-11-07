package com.moyue.reader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyue.reader.AppContainer
import com.moyue.reader.R
import com.moyue.reader.domain.model.ReaderSettings
import com.moyue.reader.domain.repository.ReadingSettingsRepository
import com.moyue.reader.domain.service.HiddenShelfManager
import com.moyue.reader.domain.service.PasswordActionResult
import com.moyue.reader.ui.common.UiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: ReaderSettings = ReaderSettings(),
    val loading: Boolean = true,
    val passwordSet: Boolean = false,
    val working: Boolean = false,
    val notice: UiMessage? = null,
)

/**
 * 设置页 ViewModel。
 *
 * 阅读偏好与阅读器共享同一个 DataStore；密码操作全部委托 HiddenShelfManager，
 * ViewModel 不接触哈希或 SharedPreferences 细节。
 */
class SettingsViewModel(
    private val settingsRepository: ReadingSettingsRepository,
    private val hiddenShelfManager: HiddenShelfManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshPasswordState()
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings, loading = false) }
            }
        }
    }

    fun refreshPasswordState() {
        _uiState.update { it.copy(passwordSet = hiddenShelfManager.isPasswordSet()) }
    }

    fun updateSettings(settings: ReaderSettings) {
        _uiState.update { it.copy(settings = settings) }
        viewModelScope.launch {
            runCatching { settingsRepository.update(settings) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            notice = UiMessage(
                                R.string.shelf_message_operation_failed,
                                listOf(error.message ?: "unknown"),
                            ),
                        )
                    }
                }
        }
    }

    fun setPassword(newPassword: String) {
        if (_uiState.value.working) return
        _uiState.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = hiddenShelfManager.setInitialPassword(newPassword.toCharArray())
            handlePasswordResult(result, successMessage = R.string.settings_password_set_success)
        }
    }

    fun changePassword(oldPassword: String, newPassword: String) {
        if (_uiState.value.working) return
        _uiState.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = hiddenShelfManager.changePassword(
                oldPassword.toCharArray(),
                newPassword.toCharArray(),
            )
            handlePasswordResult(result, successMessage = R.string.settings_password_changed_success)
        }
    }

    /**
     * 清空隐藏书架并重置密码。
     * @param confirmation 用户必须输入"清空"两字，防止误触。
     */
    fun resetHiddenShelf(confirmation: String) {
        if (_uiState.value.working) return
        if (confirmation.trim() != RESET_CONFIRM_WORD) {
            _uiState.update { it.copy(notice = UiMessage(R.string.settings_reset_error)) }
            return
        }
        _uiState.update { it.copy(working = true) }
        viewModelScope.launch {
            val deleted = hiddenShelfManager.resetHiddenShelf()
            _uiState.update {
                it.copy(
                    working = false,
                    passwordSet = false,
                    notice = UiMessage(R.string.settings_hidden_reset_done, listOf(deleted)),
                )
            }
        }
    }

    private fun handlePasswordResult(result: PasswordActionResult, successMessage: Int) {
        val notice = when (result) {
            PasswordActionResult.Success -> {
                refreshPasswordState()
                UiMessage(successMessage)
            }

            PasswordActionResult.InvalidLength -> UiMessage(R.string.settings_password_error_length)
            PasswordActionResult.WrongOldPassword -> UiMessage(R.string.settings_password_error_old)
            PasswordActionResult.NotSet -> UiMessage(R.string.settings_password_error_not_set)
            PasswordActionResult.AlreadySet -> UiMessage(R.string.settings_password_error_not_set)
        }
        _uiState.update { it.copy(working = false, notice = notice) }
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    companion object {
        const val RESET_CONFIRM_WORD = "清空"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    hiddenShelfManager = container.hiddenShelfManager,
                )
            }
        }
    }
}
