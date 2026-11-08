package com.moyue.reader.ui.reader

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyue.reader.MoYueApplication
import com.moyue.reader.R
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.PageMode
import com.moyue.reader.domain.model.ReaderSettings
import com.moyue.reader.domain.reader.PageInfo
import com.moyue.reader.platform.pdf.PdfPageRenderer
import com.moyue.reader.ui.settings.ReadingSettingsControls
import com.moyue.reader.ui.theme.ReaderPalette
import com.moyue.reader.ui.theme.palette
import com.moyue.reader.ui.theme.toComposeFontFamily
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * 阅读器页面。
 *
 * 页面尺寸测量后交给 ViewModel 分页；左右模式使用 HorizontalPager，
 * 上下模式使用连续滚动 LazyColumn。两种模式共享同一份全书页索引，
 * 因此页码、跳页、目录与进度完全一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as MoYueApplication).container }
    val viewModel: ReaderViewModel = viewModel(
        factory = ReaderViewModel.factory(container, bookId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settingsOrDefault()
    val palette = settings.theme.palette()

    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }

    // 返回时立即保存进度。
    BackHandler {
        viewModel.saveNow()
        onBack()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.saveNow()
                // 后台锁定由 MainActivity 在 onStop 完成；返回前台时兜底检查，
                // 防止 Flow 回调与首帧之间出现隐藏正文。
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 沉浸式 + 保持屏幕常亮；离开时恢复系统栏。
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, view).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (window != null) {
                WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            viewModel.saveNow()
        }
    }

    // 系统栏图标明暗跟随阅读主题。
    LaunchedEffect(palette.isDark) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    // 阅读器全屏时系统栏由本页主动隐藏，栏的临时显隐（含软键盘带出状态栏）
    // 不应改变分页视口；分屏/自由窗口下系统栏由系统保留，才需要安全区。
    val isMultiWindow = (context as? Activity)?.isInMultiWindowMode == true
    val readerWindowInsets = if (isMultiWindow) {
        WindowInsets.safeDrawing
    } else {
        // 只避让物理挖孔，不避让可隐藏的状态栏/导航栏，也不避让 IME。
        WindowInsets.displayCutout
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(readerWindowInsets),
        ) {
            val density = LocalDensity.current
            val horizontalPaddingPx = with(density) { settings.horizontalPaddingDp.dp.roundToPx() }
            val verticalPaddingPx = with(density) { READER_VERTICAL_PADDING.roundToPx() }
            val contentWidthPx = (constraints.maxWidth - horizontalPaddingPx * 2).coerceAtLeast(1)
            val contentHeightPx = (constraints.maxHeight - verticalPaddingPx * 2).coerceAtLeast(1)
            val titleHeightPx = with(density) { TITLE_LINE_HEIGHT.toPx() + TITLE_GAP.toPx() }
            // 字号与行高必须和正文 Text 用同一个 Density 解析：
            // Android 14+ 的系统字体缩放是非线性的，不能手算 sp×density×fontScale；
            // 否则分页器与渲染器会使用不同的字号/行高（前者偏大），出现半屏空白。
            val fontSizePx = with(density) { settings.fontSizeSp.sp.toPx() }
            val lineHeightPx = with(density) {
                (settings.fontSizeSp * settings.lineHeightMultiplier).sp.toPx()
            }
            val viewport = ReaderViewport(
                widthPx = contentWidthPx,
                heightPx = contentHeightPx,
                titleHeightPx = titleHeightPx.roundToInt(),
                fontSizePx = fontSizePx,
                lineHeightPx = lineHeightPx,
                fontFamily = settings.fontFamily,
            )

            LaunchedEffect(viewport) {
                viewModel.onViewportMeasured(viewport)
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = settings.horizontalPaddingDp.dp,
                        vertical = READER_VERTICAL_PADDING,
                    ),
            ) {
                when (val current = state) {
                    ReaderUiState.Loading -> ReaderLoading(palette, progress = null)
                    is ReaderUiState.Paginating -> ReaderLoading(
                        palette = palette,
                        progress = current.progress,
                    )

                    is ReaderUiState.Error -> ReaderErrorView(
                        error = current.error,
                        palette = palette,
                        onRetry = viewModel::retry,
                        onBack = {
                            viewModel.saveNow()
                            onBack()
                        },
                    )

                    is ReaderUiState.Ready -> {
                        ReaderTextContent(
                            state = current,
                            palette = palette,
                            contentWidthPx = contentWidthPx,
                            contentHeightPx = contentHeightPx,
                            onPageChanged = viewModel::onPageChanged,
                            onToggleChrome = viewModel::toggleChrome,
                        )
                        if (current.isPaginating) {
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                RepaginatingOverlay(current.paginationProgress)
                            }
                        }
                    }

                    is ReaderUiState.PdfReady -> {
                        val sourceFile = remember(current.book.id) {
                            runCatching {
                                container.fileStore.sourceFile(current.book.sourcePath)
                            }.getOrNull()
                        }
                        val renderer = remember(sourceFile) {
                            sourceFile?.let { file ->
                                runCatching { PdfPageRenderer(file) }.getOrNull()
                            }
                        }
                        DisposableEffect(renderer) {
                            onDispose { renderer?.close() }
                        }
                        PdfContent(
                            state = current,
                            palette = palette,
                            renderer = renderer,
                            contentWidthPx = contentWidthPx,
                            contentHeightPx = contentHeightPx,
                            onPageChanged = viewModel::onPageChanged,
                            onToggleChrome = viewModel::toggleChrome,
                        )
                    }
                }
            }
        }

        val chromeVisible = when (val current = state) {
            is ReaderUiState.Ready -> current.chromeVisible
            is ReaderUiState.PdfReady -> current.chromeVisible
            else -> false
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                bookTitle = state.bookTitleOrEmpty(),
                palette = palette,
                onBack = {
                    viewModel.saveNow()
                    onBack()
                },
                onToc = { showToc = true },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                state = state,
                palette = palette,
                onPrevPage = {
                    val page = state.currentPageZeroBased()
                    if (page > 0) viewModel.jumpToPage(page)
                },
                onNextPage = {
                    val page = state.currentPageZeroBased()
                    if (page + 1 < state.totalPages()) viewModel.jumpToPage(page + 2)
                },
                onJump = { showJump = true },
                onSettings = { showSettings = true },
            )
        }

        if (showToc) {
            ReaderTocSheet(
                state = state,
                onDismiss = { showToc = false },
                onSelectChapter = { index ->
                    viewModel.jumpToChapter(index)
                    showToc = false
                },
            )
        }

        if (showSettings) {
            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                containerColor = Color(0xFFFCF8F0),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.reader_settings),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF211D18),
                    )
                    Spacer(Modifier.height(16.dp))
                    if (state.isScannedPdf()) {
                        Text(
                            text = stringResource(R.string.reader_scanned_pdf_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6B6255),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    ReadingSettingsControls(
                        settings = settings,
                        onSettingsChange = viewModel::applySettings,
                        showPreview = true,
                        compact = true,
                    )
                }
            }
        }

        if (showJump) {
            JumpToPageDialog(
                currentPage = state.currentPageZeroBased() + 1,
                totalPages = state.totalPages().coerceAtLeast(1),
                onDismiss = { showJump = false },
                onJump = { page ->
                    viewModel.jumpToPage(page)
                    showJump = false
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 文本阅读
// ---------------------------------------------------------------------------

@Composable
private fun ReaderTextContent(
    state: ReaderUiState.Ready,
    palette: ReaderPalette,
    contentWidthPx: Int,
    contentHeightPx: Int,
    onPageChanged: (Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    val density = LocalDensity.current
    val pageHeight = with(density) { contentHeightPx.toDp() }
    when (state.settings.pageMode) {
        PageMode.HORIZONTAL -> {
            key(state.layoutVersion) {
                val pagerState = rememberPagerState(initialPage = state.currentPage) {
                    state.pageIndex.totalPages
                }
                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }
                        .distinctUntilChanged()
                        .collect { page -> onPageChanged(page) }
                }
                LaunchedEffect(state.currentPage) {
                    if (pagerState.currentPage != state.currentPage) {
                        pagerState.scrollToPage(state.currentPage)
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                ) { page ->
                    ReaderTextPage(
                        page = state.pageIndex.pages[page],
                        chapter = state.chapters.getOrNull(
                            state.pageIndex.chapterIndexForPage(page),
                        ),
                        settings = state.settings,
                        palette = palette,
                        pageHeight = pageHeight,
                        onToggleChrome = onToggleChrome,
                    )
                }
            }
        }

        PageMode.VERTICAL -> {
            key(state.layoutVersion) {
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = state.currentPage,
                )
                LaunchedEffect(listState) {
                    snapshotFlow {
                        listState.firstVisibleItemIndex to listState.isScrollInProgress
                    }
                        .distinctUntilChanged()
                        .filter { (_, scrolling) -> !scrolling }
                        .map { (page, _) -> page }
                        .distinctUntilChanged()
                        .collect { page -> onPageChanged(page) }
                }
                LaunchedEffect(state.currentPage) {
                    if (!listState.isScrollInProgress &&
                        listState.firstVisibleItemIndex != state.currentPage
                    ) {
                        listState.scrollToItem(state.currentPage)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                ) {
                    itemsIndexed(state.pageIndex.pages) { page, pageInfo ->
                        ReaderTextPage(
                            page = pageInfo,
                            chapter = state.chapters.getOrNull(pageInfo.chapterIndex),
                            settings = state.settings,
                            palette = palette,
                            pageHeight = pageHeight,
                            onToggleChrome = onToggleChrome,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderTextPage(
    page: PageInfo,
    chapter: Chapter?,
    settings: ReaderSettings,
    palette: ReaderPalette,
    pageHeight: androidx.compose.ui.unit.Dp,
    onToggleChrome: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(pageHeight)
            .clipToBounds()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onToggleChrome,
            ),
    ) {
        if (chapter != null) {
            Column(Modifier.fillMaxSize()) {
                if (page.isChapterStart) {
                    Text(
                        text = chapter.title,
                        color = palette.text,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = TITLE_FONT_SIZE,
                        lineHeight = TITLE_LINE_HEIGHT,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(TITLE_GAP))
                }
                val text = chapter.text.substring(
                    page.start.coerceIn(0, chapter.text.length),
                    page.end.coerceIn(0, chapter.text.length),
                )
                Text(
                    text = text,
                    color = palette.text,
                    fontFamily = settings.fontFamily.toComposeFontFamily(),
                    fontSize = settings.fontSizeSp.sp,
                    lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 扫描版 PDF
// ---------------------------------------------------------------------------

@Composable
private fun PdfContent(
    state: ReaderUiState.PdfReady,
    palette: ReaderPalette,
    renderer: PdfPageRenderer?,
    contentWidthPx: Int,
    contentHeightPx: Int,
    onPageChanged: (Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    val density = LocalDensity.current
    val pageHeight = with(density) { contentHeightPx.toDp() }
    when (state.settings.pageMode) {
        PageMode.HORIZONTAL -> {
            val pagerState = rememberPagerState(initialPage = state.currentPage) {
                state.pageCount
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }
                    .distinctUntilChanged()
                    .collect { page -> onPageChanged(page) }
            }
            LaunchedEffect(state.currentPage) {
                if (pagerState.currentPage != state.currentPage) {
                    pagerState.scrollToPage(state.currentPage)
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                PdfPageView(
                    renderer = renderer,
                    pageIndex = page,
                    targetWidthPx = contentWidthPx,
                    onToggleChrome = onToggleChrome,
                )
            }
        }

        PageMode.VERTICAL -> {
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = state.currentPage,
            )
            LaunchedEffect(listState) {
                snapshotFlow {
                    listState.firstVisibleItemIndex to listState.isScrollInProgress
                }
                    .distinctUntilChanged()
                    .filter { (_, scrolling) -> !scrolling }
                    .map { (page, _) -> page }
                    .distinctUntilChanged()
                    .collect { page -> onPageChanged(page) }
            }
            LaunchedEffect(state.currentPage) {
                if (!listState.isScrollInProgress &&
                    listState.firstVisibleItemIndex != state.currentPage
                ) {
                    listState.scrollToItem(state.currentPage)
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.pageCount) { page ->
                    Box(Modifier.fillMaxWidth().height(pageHeight)) {
                        PdfPageView(
                            renderer = renderer,
                            pageIndex = page,
                            targetWidthPx = contentWidthPx,
                            onToggleChrome = onToggleChrome,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageView(
    renderer: PdfPageRenderer?,
    pageIndex: Int,
    targetWidthPx: Int,
    onToggleChrome: () -> Unit,
) {
    var bitmap by remember(pageIndex, targetWidthPx, renderer) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(pageIndex, targetWidthPx, renderer) {
        bitmap = renderer?.renderPage(pageIndex, targetWidthPx)
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onToggleChrome,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = stringResource(R.string.reader_scanned_page, pageIndex + 1),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF9C4A2F))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (renderer == null) {
                        stringResource(R.string.reader_page_load_failed)
                    } else {
                        stringResource(R.string.common_loading)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B6255),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 加载与错误
// ---------------------------------------------------------------------------

@Composable
private fun ReaderLoading(palette: ReaderPalette, progress: Float?) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "墨",
            fontFamily = FontFamily.Serif,
            fontSize = 44.sp,
            color = palette.text.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(18.dp))
        if (progress == null) {
            CircularProgressIndicator(color = palette.accent)
        } else {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.width(180.dp),
                color = palette.accent,
                trackColor = palette.text.copy(alpha = 0.1f),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.reader_paginating,
                    (progress.coerceIn(0f, 1f) * 100).roundToInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondary,
            )
        }
    }
}

@Composable
private fun ReaderErrorView(
    error: ReaderError,
    palette: ReaderPalette,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                if (error == ReaderError.HIDDEN_LOCKED) {
                    R.string.reader_error_locked_title
                } else {
                    R.string.reader_error_title
                },
            ),
            style = MaterialTheme.typography.titleLarge,
            color = palette.text,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(error.messageRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.secondary,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                onClick = onBack,
                colors = ButtonDefaults.textButtonColors(contentColor = palette.accent),
            ) {
                Text(stringResource(R.string.reader_back_shelf))
            }
            if (error != ReaderError.HIDDEN_LOCKED && error != ReaderError.BOOK_MISSING) {
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.accent),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.reader_retry))
                }
            }
        }
    }
}

@Composable
private fun RepaginatingOverlay(progress: Float) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = Color(0xF2FCF8F0),
        shadowElevation = 4.dp,
        modifier = Modifier.padding(24.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF9C4A2F),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(
                    R.string.reader_settings_repaginating,
                ) + " " + (progress.coerceIn(0f, 1f) * 100).roundToInt() + "%",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF211D18),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 顶栏 / 底栏 / 目录 / 跳页
// ---------------------------------------------------------------------------

@Composable
private fun ReaderTopBar(
    bookTitle: String,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onToc: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.chromeSurface)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.reader_back),
                tint = palette.text,
            )
        }
        Text(
            text = bookTitle,
            color = palette.text,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToc) {
            Icon(
                Icons.AutoMirrored.Outlined.List,
                contentDescription = stringResource(R.string.reader_toc),
                tint = palette.text,
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    palette: ReaderPalette,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onJump: () -> Unit,
    onSettings: () -> Unit,
) {
    val currentPage = state.currentPageZeroBased()
    val totalPages = state.totalPages().coerceAtLeast(1)
    val percent = state.progressPercent()
    val chapterInfo = state.chapterInfo()

    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.chromeSurface)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 10.dp, bottom = 8.dp),
    ) {
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = palette.accent,
            trackColor = palette.text.copy(alpha = 0.1f),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$percent%",
                color = palette.accent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = chapterInfo,
                color = palette.secondary,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.reader_page_indicator, currentPage + 1, totalPages),
                color = palette.secondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row {
                IconButton(onClick = onPrevPage) {
                    Icon(
                        Icons.Outlined.ChevronLeft,
                        contentDescription = stringResource(R.string.reader_prev_page),
                        tint = palette.text,
                    )
                }
                IconButton(onClick = onNextPage) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = stringResource(R.string.reader_next_page),
                        tint = palette.text,
                    )
                }
            }
            TextButton(onClick = onJump) {
                Text(stringResource(R.string.reader_jump), color = palette.text)
            }
            TextButton(onClick = onSettings) {
                Text(
                    text = "Aa",
                    color = palette.text,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTocSheet(
    state: ReaderUiState,
    onDismiss: () -> Unit,
    onSelectChapter: (Int) -> Unit,
) {
    val chapters = state.chaptersOrEmpty()
    val currentIndex = state.currentChapterIndex()
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0)),
    )
    LaunchedEffect(currentIndex) {
        if (chapters.isNotEmpty()) {
            listState.scrollToItem(currentIndex.coerceIn(0, chapters.lastIndex))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFFCF8F0),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.reader_toc),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF211D18),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.reader_chapter_list, chapters.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B6255),
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(chapters) { index, chapter ->
                    val selected = index == currentIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelectChapter(index) }
                            .background(
                                if (selected) Color(0xFFF0DED4) else Color.Transparent,
                            )
                            .padding(horizontal = 24.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = (index + 1).toString().padStart(3, ' '),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) Color(0xFF9C4A2F) else Color(0xFF9C9282),
                            modifier = Modifier.width(36.dp),
                        )
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) Color(0xFF9C4A2F) else Color(0xFF211D18),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Text(
                                text = stringResource(R.string.reader_toc_current),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9C4A2F),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JumpToPageDialog(
    currentPage: Int,
    totalPages: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
) {
    var input by remember(currentPage) { mutableStateOf(currentPage.toString()) }
    var error by remember { mutableStateOf(false) }
    val submit = {
        val page = input.toIntOrNull()
        if (page == null || page !in 1..totalPages) {
            error = true
        } else {
            onJump(page)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_jump_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it.filter(Char::isDigit).take(7)
                        error = false
                    },
                    label = {
                        Text(stringResource(R.string.reader_jump_input_label, totalPages))
                    },
                    singleLine = true,
                    isError = error,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                if (error) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.reader_jump_invalid, totalPages),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit) {
                Text(stringResource(R.string.reader_jump))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// 状态辅助
// ---------------------------------------------------------------------------

private fun ReaderError.messageRes(): Int = when (this) {
    ReaderError.BOOK_MISSING -> R.string.reader_error_book_missing
    ReaderError.HIDDEN_LOCKED -> R.string.reader_error_hidden_locked
    ReaderError.FILE_MISSING -> R.string.reader_error_file_missing
    ReaderError.LOAD_FAILED -> R.string.reader_error_parse
    ReaderError.PAGINATION_FAILED -> R.string.reader_error_pagination
}

private fun ReaderUiState.settingsOrDefault(): ReaderSettings = when (this) {
    is ReaderUiState.Ready -> settings
    is ReaderUiState.PdfReady -> settings
    is ReaderUiState.Error -> settings
    else -> ReaderSettings()
}

private fun ReaderUiState.bookTitleOrEmpty(): String = when (this) {
    is ReaderUiState.Ready -> book.title
    is ReaderUiState.PdfReady -> book.title
    is ReaderUiState.Paginating -> book.title
    else -> ""
}

private fun ReaderUiState.chaptersOrEmpty(): List<Chapter> = when (this) {
    is ReaderUiState.Ready -> chapters
    is ReaderUiState.PdfReady -> chapters
    else -> emptyList()
}

private fun ReaderUiState.isScannedPdf(): Boolean = when (this) {
    is ReaderUiState.PdfReady -> true
    is ReaderUiState.Ready -> book.isScannedPdf
    else -> false
}

private fun ReaderUiState.currentPageZeroBased(): Int = when (this) {
    is ReaderUiState.Ready -> currentPage
    is ReaderUiState.PdfReady -> currentPage
    else -> 0
}

private fun ReaderUiState.totalPages(): Int = when (this) {
    is ReaderUiState.Ready -> pageIndex.totalPages
    is ReaderUiState.PdfReady -> pageCount
    else -> 0
}

private fun ReaderUiState.progressPercent(): Int = when (this) {
    is ReaderUiState.Ready -> pageIndex.percentInt(currentPage)
    is ReaderUiState.PdfReady -> {
        if (pageCount <= 0) 0 else (((currentPage + 1) * 100) / pageCount).coerceIn(0, 100)
    }
    else -> 0
}

private fun ReaderUiState.currentChapterIndex(): Int = when (this) {
    is ReaderUiState.Ready -> pageIndex.chapterIndexForPage(currentPage)
    is ReaderUiState.PdfReady -> currentPage
    else -> 0
}

@Composable
private fun ReaderUiState.chapterInfo(): String = when (this) {
    is ReaderUiState.Ready -> {
        val index = pageIndex.chapterIndexForPage(currentPage)
        val chapter = chapters.getOrNull(index)?.title.orEmpty()
        val position = stringResource(
            R.string.reader_chapter_progress,
            index + 1,
            chapters.size,
        )
        "$position · $chapter"
    }

    is ReaderUiState.PdfReady -> {
        stringResource(R.string.reader_chapter_progress, currentPage + 1, pageCount)
    }

    else -> ""
}

private val TITLE_FONT_SIZE = 24.sp
private val TITLE_LINE_HEIGHT = 34.sp
private val TITLE_GAP = 12.dp
private val READER_VERTICAL_PADDING = 18.dp
