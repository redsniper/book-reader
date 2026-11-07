package com.moyue.reader.ui.shelf

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyue.reader.MoYueApplication
import com.moyue.reader.R
import com.moyue.reader.domain.model.Book
import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.model.ShelfItem
import com.moyue.reader.domain.service.PullDownUnlockDetector
import com.moyue.reader.ui.common.UiMessage
import com.moyue.reader.ui.components.BookCover
import com.moyue.reader.ui.theme.Cinnabar
import com.moyue.reader.ui.theme.Ink
import com.moyue.reader.ui.theme.InkFaint
import com.moyue.reader.ui.theme.InkSoft
import com.moyue.reader.ui.theme.Paper
import com.moyue.reader.ui.theme.PaperDeep
import com.moyue.reader.ui.theme.PaperLine
import kotlin.math.roundToInt

/**
 * 书架主界面。
 *
 * 同一页面承载普通/隐藏两种模式：
 * - 普通模式支持导入、编辑、隐藏、删除；
 * - 隐藏模式仅展示 hidden=true 的书籍，并提供退出入口。
 *
 * 下拉解锁由 NestedScrollConnection 捕获书架顶部的 overscroll，
 * 达到 140dp 阈值后弹出密码框。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    onOpenBook: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember {
        (context.applicationContext as MoYueApplication).container
    }
    val documentResolver = remember(context) { AndroidDocumentResolver(context) }
    val viewModel: ShelfViewModel = viewModel(
        factory = ShelfViewModel.factory(container, documentResolver),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 从设置页返回时刷新"是否已设置密码"状态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPasswordState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.importUris(uris) }

    // 隐藏模式下的返回键先退出隐藏书架，避免直接退出应用。
    androidx.activity.compose.BackHandler(enabled = state.mode == ShelfMode.HIDDEN) {
        viewModel.exitHiddenShelf()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            context.getString(message.resId, *message.args.toTypedArray()),
        )
        viewModel.clearMessage()
    }

    val density = LocalDensity.current
    val thresholdPx = with(density) {
        PullDownUnlockDetector.DEFAULT_THRESHOLD_DP.dp.toPx()
    }
    val detector = remember(thresholdPx) { PullDownUnlockDetector(thresholdPx) }
    var pullProgress by remember { mutableFloatStateOf(0f) }
    val gridState = rememberLazyGridState()
    val currentMode by rememberUpdatedState(state.mode)

    val nestedScrollConnection = remember(detector, gridState, viewModel) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (currentMode != ShelfMode.NORMAL) return Offset.Zero
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val atTop = gridState.firstVisibleItemIndex == 0 &&
                    gridState.firstVisibleItemScrollOffset == 0
                val triggered = detector.onOverscroll(available.y, atTop)
                pullProgress = detector.progress
                if (triggered) {
                    pullProgress = 0f
                    viewModel.onPullDownTriggered()
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                detector.reset()
                pullProgress = 0f
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                detector.reset()
                pullProgress = 0f
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(state.mode) {
        gridState.scrollToItem(0)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
        floatingActionButton = {
            if (state.mode == ShelfMode.NORMAL) {
                ExtendedFloatingActionButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf(
                                "text/plain",
                                "application/epub+zip",
                                "application/pdf",
                                "application/octet-stream",
                            ),
                        )
                    },
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = Ink,
                    contentColor = Color(0xFFFDF8EE),
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.shelf_import)) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Paper,
                        0.55f to Paper,
                        1f to Color(0xFFF3E9D6),
                    ),
                )
                .padding(innerPadding),
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                ShelfHeader(
                    mode = state.mode,
                    onSettings = onOpenSettings,
                    onExitHidden = { viewModel.exitHiddenShelf() },
                )

                Box(Modifier.weight(1f)) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 148.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(nestedScrollConnection),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 6.dp,
                            bottom = 120.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        if (!state.loading && state.books.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                if (state.mode == ShelfMode.HIDDEN) {
                                    HiddenEmptyState()
                                } else {
                                    EmptyShelfState(
                                        onImport = {
                                            importLauncher.launch(
                                                arrayOf(
                                                    "text/plain",
                                                    "application/epub+zip",
                                                    "application/pdf",
                                                    "application/octet-stream",
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }

                        items(state.books, key = { it.book.id }) { item ->
                            BookGridItem(
                                item = item,
                                coverFile = item.book.coverPath?.let { path ->
                                    runCatching { container.fileStore.coverFile(path) }.getOrNull()
                                },
                                onClick = { onOpenBook(item.book.id) },
                                onEdit = { viewModel.openEditor(item.book) },
                            )
                        }
                    }

                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Cinnabar,
                        )
                    }

                    if (state.loadError) {
                        Text(
                            text = stringResource(R.string.shelf_load_failed),
                            color = InkSoft,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(24.dp),
                        )
                    }

                    if (state.mode == ShelfMode.NORMAL) {
                        PullDownIndicator(
                            progress = pullProgress,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 78.dp),
                        )
                    }
                }
            }

            ImportProgressOverlay(
                progress = state.importProgress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 96.dp, start = 20.dp, end = 20.dp),
            )
        }
    }

    if (state.unlockDialogVisible) {
        UnlockPasswordDialog(
            error = state.unlockError,
            cooldownSeconds = state.unlockCooldownSeconds,
            onDismiss = viewModel::dismissUnlockDialog,
            onConfirm = viewModel::confirmUnlock,
        )
    }

    if (state.setPasswordPromptVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSetPasswordPrompt,
            title = { Text(stringResource(R.string.password_title)) },
            text = { Text(stringResource(R.string.shelf_password_prompt_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissSetPasswordPrompt()
                        onOpenSettings()
                    },
                ) { Text(stringResource(R.string.shelf_go_settings)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSetPasswordPrompt) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    state.editingBook?.let { book ->
        BookEditSheet(
            book = book,
            progress = state.books.firstOrNull { it.book.id == book.id }?.progress,
            currentChapterTitle = state.books.firstOrNull { it.book.id == book.id }
                ?.currentChapterTitle,
            coverFile = book.coverPath?.let { path ->
                runCatching { container.fileStore.coverFile(path) }.getOrNull()
            },
            onDismiss = viewModel::dismissEditor,
            onToggleHidden = { viewModel.toggleHidden(book) },
            onDelete = { viewModel.requestDelete(book) },
        )
    }

    state.deleteConfirmBook?.let { book ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.shelf_delete_confirm_title, book.title)) },
            text = { Text(stringResource(R.string.shelf_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteBook(book) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.shelf_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun ShelfHeader(
    mode: ShelfMode,
    onSettings: () -> Unit,
    onExitHidden: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Cinnabar),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = if (mode == ShelfMode.HIDDEN) {
                    stringResource(R.string.shelf_hidden_title)
                } else {
                    stringResource(R.string.app_name)
                },
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
            Spacer(Modifier.weight(1f))
            if (mode == ShelfMode.HIDDEN) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Cinnabar,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.shelf_settings),
                    tint = InkSoft,
                )
            }
        }
        Text(
            text = if (mode == ShelfMode.HIDDEN) {
                stringResource(R.string.shelf_hidden_hint)
            } else {
                stringResource(R.string.app_slogan)
            },
            color = InkSoft,
            style = MaterialTheme.typography.bodySmall,
            letterSpacing = 0.6.sp,
        )
        if (mode == ShelfMode.HIDDEN) {
            TextButton(
                onClick = onExitHidden,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
            ) {
                Icon(
                    Icons.Outlined.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.shelf_exit_hidden))
            }
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridItem(
    item: ShelfItem,
    coverFile: java.io.File?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    val book = item.book
    val progressPercent = ((item.progress?.percent ?: 0f) * 100)
        .roundToInt()
        .coerceIn(0, 100)
    val chapterTitle = item.currentChapterTitle ?: stringResource(R.string.shelf_not_started)

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onEdit),
    ) {
        Box {
            BookCover(
                title = book.title,
                author = book.author,
                coverFile = coverFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                shape = CircleShape,
                color = Paper.copy(alpha = 0.82f),
            ) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.shelf_more),
                        tint = InkSoft,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            color = Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = book.format.label(),
                style = MaterialTheme.typography.labelMedium,
                color = Cinnabar,
            )
            if (!book.author.isNullOrBlank()) {
                Text(
                    text = " · ${book.author}",
                    style = MaterialTheme.typography.labelMedium,
                    color = InkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape)
                .background(PaperLine),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progressPercent / 100f)
                    .height(2.dp)
                    .background(Cinnabar),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.shelf_progress, progressPercent, chapterTitle),
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyShelfState(onImport: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 80.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(PaperDeep),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "墨",
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                fontSize = 28.sp,
                color = Cinnabar.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.shelf_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.shelf_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onImport) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.shelf_import))
        }
    }
}

@Composable
private fun HiddenEmptyState() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 80.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = InkFaint,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.shelf_hidden_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.shelf_hidden_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
        )
    }
}

@Composable
private fun PullDownIndicator(progress: Float, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = progress > 0.03f, modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Paper.copy(alpha = 0.96f),
            shadowElevation = 2.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Cinnabar,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .width(70.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(PaperLine),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(Cinnabar),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (progress >= 1f) {
                        stringResource(R.string.shelf_pull_release)
                    } else {
                        stringResource(R.string.shelf_pull_hint)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSoft,
                )
            }
        }
    }
}

@Composable
private fun ImportProgressOverlay(
    progress: ImportProgress?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = progress != null, modifier = modifier) {
        if (progress != null) {
            val current = progress
            Surface(
            shape = RoundedCornerShape(14.dp),
            color = Paper.copy(alpha = 0.98f),
            shadowElevation = 4.dp,
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text(
                    text = if (current.currentName.isBlank()) {
                        stringResource(
                            R.string.shelf_importing_count,
                            current.current,
                            current.total,
                        )
                    } else {
                        stringResource(R.string.shelf_importing_book, current.currentName)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = {
                        if (current.total == 0) 0f else current.current.toFloat() / current.total
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = Cinnabar,
                    trackColor = PaperLine,
                )
            }
            }
        }
    }
}

@Composable
private fun UnlockPasswordDialog(
    error: UiMessage?,
    cooldownSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val blocked = cooldownSeconds > 0
    val submit = {
        if (!blocked) onConfirm(password)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_password_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    enabled = !blocked,
                    label = { Text(stringResource(R.string.password_hint)) },
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(error.resId, *error.args.toTypedArray()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = submit,
                enabled = password.isNotEmpty() && !blocked,
            ) {
                if (blocked) {
                    Text(
                        stringResource(R.string.shelf_message_cooldown, cooldownSeconds),
                    )
                } else {
                    Text(stringResource(R.string.password_unlock))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookEditSheet(
    book: Book,
    progress: com.moyue.reader.domain.model.ReadingProgress?,
    currentChapterTitle: String?,
    coverFile: java.io.File?,
    onDismiss: () -> Unit,
    onToggleHidden: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val percent = ((progress?.percent ?: 0f) * 100).roundToInt().coerceIn(0, 100)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.shelf_book_info),
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(
                    title = book.title,
                    author = book.author,
                    coverFile = coverFile,
                    modifier = Modifier
                        .width(82.dp)
                        .aspectRatio(0.72f),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Ink,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.shelf_author_format,
                            book.author ?: stringResource(R.string.common_unknown_author),
                            book.format.label(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )
                    Text(
                        text = Formatter.formatShortFileSize(context, book.fileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkFaint,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.shelf_progress,
                            percent,
                            currentChapterTitle ?: stringResource(R.string.shelf_not_started),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Cinnabar,
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            HorizontalDivider(color = PaperLine)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onToggleHidden,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    if (book.hidden) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (book.hidden) R.string.shelf_unhide_book else R.string.shelf_hide_book,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.shelf_delete_book))
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

private fun BookFormat.label(): String = when (this) {
    BookFormat.TXT -> "TXT"
    BookFormat.EPUB -> "EPUB"
    BookFormat.PDF -> "PDF"
}
