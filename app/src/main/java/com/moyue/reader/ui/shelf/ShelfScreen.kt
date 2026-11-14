package com.moyue.reader.ui.shelf

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyue.reader.MoYueApplication
import com.moyue.reader.ui.common.AndroidDocumentResolver
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 书架主界面。
 *
 * 同一页面承载普通/隐藏两种模式：
 * - 普通模式支持导入、编辑、隐藏、删除；
 * - 隐藏模式仅展示 hidden=true 的书籍，并提供退出入口。
 *
 * 下拉解锁由 NestedScrollConnection 捕获书架顶部的 overscroll：
 * 阈值是整窗高度的 1/2；阈值前只跟手下移、不显示任何提示，越过阈值才淡入提示，
 * 松手后弹出密码框。
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
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
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

    // 消息只在 RESUMED 时展示：后台锁定会在应用不可见时写入提示，
    // 若提前调用 showSnackbar，用户返回时提示早已超时消失。
    LaunchedEffect(state.message, lifecycleState) {
        val message = state.message ?: return@LaunchedEffect
        if (lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
        snackbarHostState.showSnackbar(
            context.getString(message.resId, *message.args.toTypedArray()),
        )
        viewModel.clearMessage()
    }

    val density = LocalDensity.current
    // 书架根布局是 edge-to-edge 的整窗，onSizeChanged 报告的才是含系统栏的真实窗口高度；
    // 首帧高度未知时阈值为 0，但用户不可能在布局完成前开始拖动。
    var windowHeightPx by remember { mutableIntStateOf(0) }
    val thresholdPx = PullDownUnlockDetector.thresholdPxForWindowHeight(windowHeightPx)
    val maxVisualOffsetPx = with(density) {
        PullDownUnlockDetector.MAX_VISUAL_OFFSET_DP.dp.toPx()
    }
    val detector = remember(thresholdPx, maxVisualOffsetPx) {
        PullDownUnlockDetector(thresholdPx, maxVisualOffsetPx)
    }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // 隐藏书架首次数据到达后的淡入上浮进度；1 表示完全显示。
    val reveal = remember { Animatable(1f) }
    // 用 rememberSaveable 记录模式与待播放转场：从隐藏阅读器返回时导航会重建本页，
    // 只有“普通书架 -> 隐藏书架”的真实切换才应播放一次进入转场。
    var lastModeName by rememberSaveable { mutableStateOf(state.mode.name) }
    var entryAnimationPending by rememberSaveable { mutableStateOf(false) }
    // 位移只在布局阶段读取，拖动时不会触发整页重组；armed 是布尔值，只在越过/退出
    // 阈值时改变一次，重组代价可以忽略。阈值前 armed 恒为 false，提示层根本不参与组合。
    var pullOffset by remember { mutableFloatStateOf(0f) }
    var pullArmed by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val gridState = rememberLazyGridState()
    val currentMode by rememberUpdatedState(state.mode)

    /**
     * 松手后的回弹。
     *
     * 使用 Compose 的帧时钟驱动单个浮点状态，避免为每个拖动事件创建协程；
     * 回弹期间若再次下拉，调用方会取消该任务，由状态机按新的手势位移接管。
     * 提示层在回弹开始时立即淡出，不跟着手指位移一起滑走。
     */
    fun settlePull() {
        pullArmed = false
        if (pullOffset <= 0f) return
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = pullOffset,
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
            ) { value, _ -> pullOffset = value }
            pullOffset = 0f
        }
    }

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
                val wasArmed = detector.isArmed
                if (!detector.onDrag(available.y, atTop)) {
                    // 离开顶部或反向滚动：把已经拉出的内容弹回去，并立即撤掉提示。
                    if (pullOffset > 0f || pullArmed) settlePull()
                    return Offset.Zero
                }

                // 手指重新接管时，必须停止上一次回弹，避免两个动画争抢位移。
                settleJob?.cancel()
                pullOffset = detector.visualOffsetPx
                // 阈值前 pullArmed 恒为 false，提示层不参与组合；越过阈值那一帧才出现。
                pullArmed = detector.isArmed
                if (!wasArmed && detector.isArmed) {
                    // 只在“刚刚越过阈值”的那一帧震动，按住不放不会连续震。
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                // 消费掉 overscroll，避免系统拉伸效果与自定义位移叠加。
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (currentMode != ShelfMode.NORMAL) {
                    detector.reset()
                    return Velocity.Zero
                }
                val trigger = detector.onRelease()
                settlePull()
                if (trigger) viewModel.onPullDownTriggered()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // 兜底：极少数输入路径只派发 postFling，避免 ARMED 状态悬挂。
                if (currentMode == ShelfMode.NORMAL && detector.isArmed) {
                    val trigger = detector.onRelease()
                    settlePull()
                    if (trigger) viewModel.onPullDownTriggered()
                } else {
                    detector.reset()
                    // 兜底收敛残留下拉位移，避免异常输入路径把内容永久推下去；
                    // 若 preFling 已经启动回弹则不重复启动，防止动画从半途重新计时。
                    if (settleJob?.isActive != true) settlePull()
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(state.mode) {
        // 模式切换时立即收敛所有下拉/回弹状态，避免隐藏书架上残留位移。
        settleJob?.cancel()
        settleJob = null
        detector.reset()
        pullOffset = 0f
        pullArmed = false
        gridState.scrollToItem(0)
    }

    // 窗口尺寸变化（旋转、分屏、折叠）会重算阈值并重建检测器；进行中的手势必须复位，
    // 否则累计位移会按旧阈值继续判定，松手时可能误触发。
    LaunchedEffect(thresholdPx, maxVisualOffsetPx) {
        detector.reset()
        settleJob?.cancel()
        settleJob = null
        pullOffset = 0f
        pullArmed = false
    }

    // 只在模式真实切换时标记待播放转场；导航重建（如从隐藏阅读器返回）不会误触发。
    LaunchedEffect(state.mode) {
        if (state.mode == ShelfMode.HIDDEN && lastModeName == ShelfMode.NORMAL.name) {
            entryAnimationPending = true
        }
        if (state.mode == ShelfMode.NORMAL) {
            entryAnimationPending = false
            reveal.snapTo(1f)
        }
        lastModeName = state.mode.name
    }

    // 隐藏书架首批数据到达后再播放转场：验证前列表为空，不会预渲染任何隐藏书籍。
    LaunchedEffect(state.mode, state.loading, entryAnimationPending) {
        if (state.mode == ShelfMode.HIDDEN && !state.loading && entryAnimationPending) {
            reveal.snapTo(0f)
            reveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
            )
            // 必须在动画结束后再清标记：它是本 effect 的 key，提前清会取消正在播放的
            // 动画，导致隐藏书架停在半透明状态。
            entryAnimationPending = false
        }
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
                // Scaffold 的 contentWindowInsets 为 0，这里的尺寸就是含系统栏的整窗高度；
                // 阈值按它计算，保证“半屏”与用户手指能走到的距离一致。
                .onSizeChanged { windowHeightPx = it.height }
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
                    // 提示层必须画在网格下面：网格下移后才露出来；阈值前不参与组合，
                    // 因此读屏在普通书架上不会读到隐藏入口文案。
                    if (state.mode == ShelfMode.NORMAL) {
                        // 外层 Column 提供了 ColumnScope 隐式接收者，必须显式指定
                        // 顶层的 AnimatedVisibility，否则会解析到 ColumnScope 扩展版本。
                        androidx.compose.animation.AnimatedVisibility(
                            visible = pullArmed,
                            modifier = Modifier.align(Alignment.TopCenter),
                            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.9f),
                            exit = fadeOut(tween(120)),
                        ) {
                            PullDownBackdrop()
                        }
                    }

                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 148.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .offset { IntOffset(0, pullOffset.roundToInt()) }
                            .graphicsLayer {
                                // 转场只影响绘制，不改变布局，避免首帧隐藏列表跳动。
                                alpha = 0.12f + 0.88f * reveal.value
                                translationY = (1f - reveal.value) * 18.dp.toPx()
                            }
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

    if (state.hiddenLockedNotice) {
        AlertDialog(
            onDismissRequest = viewModel::dismissHiddenLockedNotice,
            title = { Text(stringResource(R.string.shelf_hidden_locked_title)) },
            text = { Text(stringResource(R.string.shelf_hidden_locked_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissHiddenLockedNotice()
                        viewModel.onPullDownTriggered()
                    },
                ) { Text(stringResource(R.string.shelf_hidden_locked_unlock)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissHiddenLockedNotice) {
                    Text(stringResource(R.string.shelf_hidden_locked_dismiss))
                }
            },
        )
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

/**
 * 下拉解锁的顶部提示层。
 *
 * 只有在越过阈值、状态机进入 `ARMED` 后才参与组合：淡入 / 淡出由外层
 * [AnimatedVisibility] 负责，内容本身是静态的“开锁圆环 + 松开进入”，不做进度填充。
 * 之所以不在阈值前显示进度环或“继续下拉”文案，是需求方确认的隐藏入口规则：
 * 未达阈值时书架只跟手移动，屏幕不能出现任何“可以进入”的提示。
 */
@Composable
private fun PullDownBackdrop(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .border(3.dp, Cinnabar, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = Cinnabar,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.shelf_pull_release),
            style = MaterialTheme.typography.labelMedium,
            color = Cinnabar,
            letterSpacing = 0.4.sp,
        )
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
