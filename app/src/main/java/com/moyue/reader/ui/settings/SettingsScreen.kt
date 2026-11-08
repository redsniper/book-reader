package com.moyue.reader.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyue.reader.BuildConfig
import com.moyue.reader.MoYueApplication
import com.moyue.reader.R
import com.moyue.reader.ui.theme.Cinnabar
import com.moyue.reader.ui.theme.Ink
import com.moyue.reader.ui.theme.InkFaint
import com.moyue.reader.ui.theme.InkSoft
import com.moyue.reader.ui.theme.Paper
import com.moyue.reader.ui.theme.PaperLine
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * 设置页：阅读偏好 + 隐藏书架安全 + 关于。
 *
 * 阅读偏好与阅读器共享 DataStore；密码只在设置成功/失败后通过 Snackbar 提示，
 * 明文密码只存在于对话框的短生命周期状态中。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as MoYueApplication).container }
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var showSetPassword by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var exportingLogs by remember { mutableStateOf(false) }
    val exportScope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportScope.launch {
            exportingLogs = true
            val exported = container.diagnosticExporter.export(context.contentResolver, uri)
            exportingLogs = false
            snackbarHostState.showSnackbar(
                context.getString(
                    if (exported) R.string.settings_export_logs_success
                    else R.string.settings_export_logs_failure,
                ),
            )
        }
    }
    val exportFileName = remember {
        "moyue-diagnostics-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.txt"
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPasswordState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            context.getString(notice.resId, *notice.args.toTypedArray()),
        )
        viewModel.clearNotice()
    }

    Scaffold(
        containerColor = Paper,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Paper)
                .statusBarsPadding(),
        ) {
            SettingsTopBar(onBack)

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SettingsSection(
                    title = stringResource(R.string.settings_reading),
                    subtitle = stringResource(R.string.settings_reading_note),
                ) {
                    ReadingSettingsControls(
                        settings = state.settings,
                        onSettingsChange = viewModel::updateSettings,
                        showPreview = true,
                    )
                }

                SettingsSection(
                    title = stringResource(R.string.settings_privacy),
                    subtitle = stringResource(R.string.settings_privacy_note),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (state.passwordSet) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                            contentDescription = null,
                            tint = if (state.passwordSet) Cinnabar else InkFaint,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_hidden_password),
                                style = MaterialTheme.typography.titleMedium,
                                color = Ink,
                            )
                            Text(
                                text = stringResource(
                                    if (state.passwordSet) {
                                        R.string.settings_password_status_set
                                    } else {
                                        R.string.settings_password_status_unset
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.passwordSet) Cinnabar else InkSoft,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.settings_password_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (state.passwordSet) {
                                    showChangePassword = true
                                } else {
                                    showSetPassword = true
                                }
                            },
                            enabled = !state.working,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(
                                    if (state.passwordSet) {
                                        R.string.settings_change_password
                                    } else {
                                        R.string.settings_set_password
                                    },
                                ),
                            )
                        }
                        OutlinedButton(
                            onClick = { showReset = true },
                            enabled = !state.working,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(stringResource(R.string.settings_reset_hidden))
                        }
                    }
                }

                SettingsSection(
                    title = stringResource(R.string.settings_diagnostics),
                    subtitle = stringResource(R.string.settings_diagnostics_note),
                ) {
                    Text(
                        text = stringResource(R.string.settings_diagnostics_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { exportLauncher.launch(exportFileName) },
                        enabled = !exportingLogs,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (exportingLogs) R.string.settings_exporting
                                else R.string.settings_export_logs,
                            ),
                        )
                    }
                    val logDirectory = container.diagnostics.logDirectoryPath()
                    if (!logDirectory.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_logs_location, logDirectory),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkFaint,
                        )
                    }
                }

                SettingsSection(
                    title = stringResource(R.string.settings_about),
                    subtitle = null,
                ) {
                    Text(
                        text = stringResource(R.string.settings_about_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSoft,
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = PaperLine)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = stringResource(R.string.settings_version_title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSoft,
                        )
                        Text(
                            text = BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = stringResource(R.string.settings_offline),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSoft,
                        )
                        Text(
                            text = "NO INTERNET",
                            style = MaterialTheme.typography.labelSmall,
                            color = Cinnabar,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }
        }
    }

    if (showSetPassword) {
        PasswordDialog(
            title = stringResource(R.string.password_title_set),
            requireOldPassword = false,
            onDismiss = { showSetPassword = false },
            onSubmit = { _, newPassword ->
                viewModel.setPassword(newPassword)
                showSetPassword = false
            },
        )
    }

    if (showChangePassword) {
        PasswordDialog(
            title = stringResource(R.string.password_title_change),
            requireOldPassword = true,
            onDismiss = { showChangePassword = false },
            onSubmit = { oldPassword, newPassword ->
                viewModel.changePassword(oldPassword, newPassword)
                showChangePassword = false
            },
        )
    }

    if (showReset) {
        ResetHiddenShelfDialog(
            onDismiss = { showReset = false },
            onConfirm = { confirmation ->
                viewModel.resetHiddenShelf(confirmation)
                showReset = false
            },
        )
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = Ink,
            )
        }
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFFCF8F0),
        border = androidx.compose.foundation.BorderStroke(1.dp, PaperLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    requireOldPassword: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (oldPassword: String, newPassword: String) -> Unit,
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    val submit = {
        when {
            requireOldPassword && oldPassword.isEmpty() -> error = R.string.settings_password_error_old
            newPassword.length !in 4..64 -> error = R.string.settings_password_error_length
            newPassword != confirmPassword -> error = R.string.password_error_mismatch
            else -> {
                error = null
                onSubmit(oldPassword, newPassword)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (requireOldPassword) {
                    PasswordField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it; error = null },
                        label = stringResource(R.string.password_old_hint),
                        visible = visible,
                        onToggleVisible = { visible = !visible },
                    )
                }
                PasswordField(
                    value = newPassword,
                    onValueChange = { newPassword = it; error = null },
                    label = stringResource(R.string.password_new_hint),
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                )
                PasswordField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; error = null },
                    label = stringResource(R.string.password_confirm_hint),
                    visible = visible,
                    onToggleVisible = { visible = !visible },
                )
                error?.let {
                    Text(
                        text = stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit) {
                Text(stringResource(R.string.password_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
        ),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = null,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ResetHiddenShelfDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_reset_confirm_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.password_reset_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSoft,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = {
                        confirmation = it
                        error = false
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.password_reset_confirm_word)) },
                    isError = error,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (confirmation.trim() == SettingsViewModel.RESET_CONFIRM_WORD) {
                                onConfirm(confirmation)
                            } else {
                                error = true
                            }
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_reset_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (confirmation.trim() == SettingsViewModel.RESET_CONFIRM_WORD) {
                        onConfirm(confirmation)
                    } else {
                        error = true
                    }
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.settings_hidden_reset_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
