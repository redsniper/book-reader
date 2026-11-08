package com.moyue.reader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.moyue.reader.AppContainer
import com.moyue.reader.core.Clock
import com.moyue.reader.core.DispatcherProvider
import com.moyue.reader.data.file.BookFileStore
import com.moyue.reader.domain.model.Book
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.ReadingAnchor
import com.moyue.reader.domain.model.ReadingProgress
import com.moyue.reader.domain.model.ReaderSettings
import com.moyue.reader.domain.reader.PageBreaker
import com.moyue.reader.domain.reader.PageIndexBuilder
import com.moyue.reader.domain.reader.PageIndexCache
import com.moyue.reader.domain.reader.PageIndexSignature
import com.moyue.reader.domain.reader.PageLayoutSpec
import com.moyue.reader.domain.reader.ReaderPageIndex
import com.moyue.reader.domain.repository.ReadingRepository
import com.moyue.reader.domain.repository.ReadingSettingsRepository
import com.moyue.reader.domain.repository.ShelfRepository
import com.moyue.reader.domain.service.HiddenShelfSession
import com.moyue.reader.domain.service.ProgressCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

/** 阅读器错误类型，UI 映射为本地化文案。 */
enum class ReaderError {
    BOOK_MISSING,
    HIDDEN_LOCKED,
    FILE_MISSING,
    LOAD_FAILED,
    PAGINATION_FAILED,
}

/** 阅读视口（像素）。由 Compose 的 BoxWithConstraints 测量后传给 ViewModel。 */
data class ReaderViewport(
    val widthPx: Int,
    val heightPx: Int,
    val titleHeightPx: Int,
    val density: Float,
    val fontScale: Float,
)

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Paginating(
        val book: Book,
        val progress: Float,
    ) : ReaderUiState

    data class Ready(
        val book: Book,
        val chapters: List<Chapter>,
        val pageIndex: ReaderPageIndex,
        val currentPage: Int,
        val settings: ReaderSettings,
        val chromeVisible: Boolean,
        val isPaginating: Boolean = false,
        val paginationProgress: Float = 0f,
        val layoutVersion: Int = 0,
    ) : ReaderUiState

    data class PdfReady(
        val book: Book,
        val chapters: List<Chapter>,
        val pageCount: Int,
        val currentPage: Int,
        val settings: ReaderSettings,
        val chromeVisible: Boolean,
    ) : ReaderUiState

    data class Error(val error: ReaderError) : ReaderUiState
}

/**
 * 阅读器 ViewModel。
 *
 * 核心职责：
 * - 加载书籍/章节/设置/进度；
 * - 按视口与排版设置构建全书页索引；
 * - 维护当前页与阅读进度（字符锚点，跨排版可恢复）；
 * - 处理跳页、跳章、翻页模式与主题变更。
 *
 * 分页在 Dispatchers.Default 后台执行，UI 显示进度；排版变化时取消旧任务，
 * 用重排前的字符锚点恢复位置。
 */
class ReaderViewModel(
    private val bookId: String,
    private val shelfRepository: ShelfRepository,
    private val readingRepository: ReadingRepository,
    private val settingsRepository: ReadingSettingsRepository,
    private val pageBreaker: PageBreaker,
    private val pageIndexCache: PageIndexCache,
    private val fileStore: BookFileStore,
    private val hiddenShelfSession: HiddenShelfSession,
    private val progressCalculator: ProgressCalculator,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var book: Book? = null
    private var chapters: List<Chapter> = emptyList()
    private var viewport: ReaderViewport? = null
    private var settings: ReaderSettings = ReaderSettings()
    private var pageIndex: ReaderPageIndex? = null
    private var savedAnchor: ReadingAnchor? = null
    private var chromeVisible: Boolean = true
    private var layoutVersion: Int = 0
    private var paginationJob: Job? = null
    private var saveJob: Job? = null

    /** 当前页索引对应的缓存签名；同签名重复回调时可直接复用，不再重排。 */
    private var readySignature: PageIndexSignature? = null

    /** 正在执行的排版签名；用于合并重复的分页请求。 */
    private var paginationSignature: PageIndexSignature? = null

    init {
        loadBook()
    }

    // ---------------------------------------------------------------------
    // 加载
    // ---------------------------------------------------------------------

    private fun loadBook() {
        viewModelScope.launch {
            _uiState.value = ReaderUiState.Loading
            try {
                val loadedBook = shelfRepository.getBook(bookId)
                if (loadedBook == null) {
                    _uiState.value = ReaderUiState.Error(ReaderError.BOOK_MISSING)
                    return@launch
                }
                book = loadedBook
                if (loadedBook.hidden && !hiddenShelfSession.isUnlocked) {
                    _uiState.value = ReaderUiState.Error(ReaderError.HIDDEN_LOCKED)
                    return@launch
                }
                val sourceMissing = runCatching {
                    !fileStore.resolve(loadedBook.sourcePath).isFile
                }.getOrDefault(true)
                if (sourceMissing) {
                    _uiState.value = ReaderUiState.Error(ReaderError.FILE_MISSING)
                    return@launch
                }

                settings = settingsRepository.settings.first()
                shelfRepository.updateLastOpened(bookId, clock.nowMillis())
                val progress = readingRepository.getProgress(bookId)

                if (loadedBook.isScannedPdf) {
                    val loadedChapters = readingRepository.getChapters(bookId)
                    if (loadedChapters.isEmpty()) {
                        _uiState.value = ReaderUiState.Error(ReaderError.LOAD_FAILED)
                        return@launch
                    }
                    chapters = loadedChapters
                    val current = (progress?.chapterIndex ?: 0)
                        .coerceIn(0, loadedChapters.lastIndex)
                    _uiState.value = ReaderUiState.PdfReady(
                        book = loadedBook,
                        chapters = loadedChapters,
                        pageCount = loadedChapters.size,
                        currentPage = current,
                        settings = settings,
                        chromeVisible = chromeVisible,
                    )
                } else {
                    val loadedChapters = readingRepository.getChaptersWithText(bookId)
                    if (loadedChapters.isEmpty() || loadedChapters.all { it.text.isBlank() }) {
                        _uiState.value = ReaderUiState.Error(ReaderError.LOAD_FAILED)
                        return@launch
                    }
                    chapters = loadedChapters
                    savedAnchor = resolveAnchor(progress)
                    _uiState.value = ReaderUiState.Paginating(loadedBook, 0f)
                    if (viewport != null) startPagination()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.value = ReaderUiState.Error(ReaderError.LOAD_FAILED)
            }
        }
    }

    /** 进度中的章节/偏移可能因重新解析而变化，失效时按百分比兜底。 */
    private fun resolveAnchor(progress: ReadingProgress?): ReadingAnchor {
        if (progress == null || chapters.isEmpty()) {
            return ReadingAnchor(0, 0)
        }
        val chapter = chapters.getOrNull(progress.chapterIndex)
        if (chapter == null) {
            return progressCalculator.anchorForPercent(progress.percent, chapters)
        }
        return ReadingAnchor(
            chapterIndex = progress.chapterIndex,
            charOffsetInChapter = progress.charOffsetInChapter.coerceIn(0, chapter.charCount),
            pdfPageIndex = null,
        )
    }

    // ---------------------------------------------------------------------
    // 视口与分页
    // ---------------------------------------------------------------------

    fun onViewportMeasured(viewport: ReaderViewport) {
        if (viewport.widthPx <= 0 || viewport.heightPx <= 0) return
        if (this.viewport == viewport) return
        this.viewport = viewport
        val state = _uiState.value
        if (state is ReaderUiState.Ready) {
            startPagination()
        } else if (state is ReaderUiState.Paginating) {
            startPagination()
        }
    }

    private fun startPagination() {
        val currentBook = book ?: return
        val currentViewport = viewport ?: return
        if (currentBook.isScannedPdf || chapters.isEmpty()) return

        val snapshotSettings = settings
        val spec = buildLayoutSpec(snapshotSettings, currentViewport)
        val signature = PageIndexSignature.of(currentBook.contentHash, chapters, spec)

        // 幂等保护：同一套输入已经排好或正在排时，不重复启动任务。
        // 跳页、弹窗、转场都可能让 Compose 重新回调视口，但页索引本身没有变化；
        // 这里按完整签名拦截，避免“翻一页就重排全书”的放大效应。
        if (paginationJob?.isActive == true && paginationSignature == signature) return
        if (pageIndex != null && readySignature == signature) return

        val previousState = _uiState.value
        val anchor = when (previousState) {
            is ReaderUiState.Ready -> previousState.pageIndex.anchorForPage(previousState.currentPage)
            else -> savedAnchor ?: ReadingAnchor(0, 0)
        }
        val currentChromeVisible = when (previousState) {
            is ReaderUiState.Ready -> previousState.chromeVisible
            else -> chromeVisible
        }

        paginationJob?.cancel()
        paginationSignature = signature
        if (previousState is ReaderUiState.Ready) {
            _uiState.value = previousState.copy(
                isPaginating = true,
                paginationProgress = 0f,
                settings = settings,
            )
        } else {
            _uiState.value = ReaderUiState.Paginating(currentBook, 0f)
        }

        paginationJob = viewModelScope.launch {
            try {
                // 先尝试文件缓存；命中时只需反序列化页区间，避免重新跑 StaticLayout。
                val cached = try {
                    withContext(dispatchers.io) {
                        pageIndexCache.load(bookId, signature, chapters)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 缓存损坏/IO 失败不能阻断阅读，按未命中继续现场分页。
                    null
                }

                val index = if (cached != null) {
                    cached
                } else {
                    val built = withContext(dispatchers.default) {
                        PageIndexBuilder(pageBreaker).build(chapters, spec) { progress ->
                            updatePaginationProgress(progress)
                        }
                    }
                    try {
                        withContext(dispatchers.io) {
                            pageIndexCache.save(bookId, signature, chapters, built)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 缓存写入失败只影响下次打开速度，不影响本次阅读。
                    }
                    built
                }

                if (index.isEmpty) {
                    _uiState.value = ReaderUiState.Error(ReaderError.PAGINATION_FAILED)
                    return@launch
                }
                pageIndex = index
                readySignature = signature
                layoutVersion += 1
                val targetPage = index.pageForAnchor(anchor)
                _uiState.value = ReaderUiState.Ready(
                    book = currentBook,
                    chapters = chapters,
                    pageIndex = index,
                    currentPage = targetPage,
                    settings = snapshotSettings,
                    chromeVisible = currentChromeVisible,
                    isPaginating = false,
                    paginationProgress = 1f,
                    layoutVersion = layoutVersion,
                )
                scheduleSave()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.value = ReaderUiState.Error(ReaderError.PAGINATION_FAILED)
            }
        }
    }

    private fun updatePaginationProgress(progress: Float) {
        _uiState.update { state ->
            when (state) {
                is ReaderUiState.Paginating -> state.copy(progress = progress)
                is ReaderUiState.Ready -> state.copy(
                    isPaginating = true,
                    paginationProgress = progress,
                )

                else -> state
            }
        }
    }

    private fun buildLayoutSpec(settings: ReaderSettings, viewport: ReaderViewport): PageLayoutSpec {
        val fontSizePx = settings.fontSizeSp * viewport.density * viewport.fontScale
        return PageLayoutSpec(
            widthPx = viewport.widthPx,
            heightPx = viewport.heightPx,
            fontFamily = settings.fontFamily,
            fontSizePx = fontSizePx,
            lineHeightPx = fontSizePx * settings.lineHeightMultiplier,
            chapterTitleHeightPx = viewport.titleHeightPx,
        )
    }

    // ---------------------------------------------------------------------
    // 页码与进度
    // ---------------------------------------------------------------------

    fun onPageChanged(page: Int) {
        val state = _uiState.value
        when (state) {
            is ReaderUiState.Ready -> {
                if (page !in 0 until state.pageIndex.totalPages) return
                if (page == state.currentPage) return
                _uiState.value = state.copy(currentPage = page)
                scheduleSave()
            }

            is ReaderUiState.PdfReady -> {
                if (page !in 0 until state.pageCount) return
                if (page == state.currentPage) return
                _uiState.value = state.copy(currentPage = page)
                scheduleSave()
            }

            else -> Unit
        }
    }

    fun jumpToPage(pageOneBased: Int) {
        val state = _uiState.value
        val targetZeroBased = pageOneBased - 1
        when (state) {
            is ReaderUiState.Ready -> {
                if (targetZeroBased !in 0 until state.pageIndex.totalPages) return
                _uiState.value = state.copy(currentPage = targetZeroBased)
                scheduleSave()
            }

            is ReaderUiState.PdfReady -> {
                if (targetZeroBased !in 0 until state.pageCount) return
                _uiState.value = state.copy(currentPage = targetZeroBased)
                scheduleSave()
            }

            else -> Unit
        }
    }

    fun jumpToChapter(chapterIndex: Int) {
        val state = _uiState.value
        when (state) {
            is ReaderUiState.Ready -> {
                val page = state.pageIndex.pageForChapter(chapterIndex)
                _uiState.value = state.copy(currentPage = page)
                scheduleSave()
            }

            is ReaderUiState.PdfReady -> {
                if (chapterIndex !in 0 until state.pageCount) return
                _uiState.value = state.copy(currentPage = chapterIndex)
                scheduleSave()
            }

            else -> Unit
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MILLIS)
            persistProgress()
        }
    }

    private suspend fun persistProgress() {
        val snapshot = progressSnapshot() ?: return
        runCatching { readingRepository.saveProgress(bookId, snapshot) }
    }

    /**
     * 立即保存。使用应用级作用域，确保返回书架/页面销毁时仍能写完。
     */
    fun saveNow() {
        saveJob?.cancel()
        val snapshot = progressSnapshot() ?: return
        appScope.launch(NonCancellable) {
            runCatching { readingRepository.saveProgress(bookId, snapshot) }
        }
    }

    private fun progressSnapshot(): ReadingProgress? {
        val state = _uiState.value
        return when (state) {
            is ReaderUiState.Ready -> {
                val anchor = state.pageIndex.anchorForPage(state.currentPage)
                ReadingProgress(
                    chapterIndex = anchor.chapterIndex,
                    charOffsetInChapter = anchor.charOffsetInChapter,
                    percent = state.pageIndex.progressPercent(state.currentPage),
                    updatedAt = clock.nowMillis(),
                )
            }

            is ReaderUiState.PdfReady -> {
                val page = state.currentPage.coerceIn(0, state.pageCount - 1)
                ReadingProgress(
                    chapterIndex = page,
                    charOffsetInChapter = 0,
                    percent = ((page + 1).toFloat() / state.pageCount).coerceIn(0f, 1f),
                    updatedAt = clock.nowMillis(),
                )
            }

            else -> null
        }
    }

    // ---------------------------------------------------------------------
    // UI 状态变更
    // ---------------------------------------------------------------------

    fun toggleChrome() {
        chromeVisible = !chromeVisible
        _uiState.update { state ->
            when (state) {
                is ReaderUiState.Ready -> state.copy(chromeVisible = chromeVisible)
                is ReaderUiState.PdfReady -> state.copy(chromeVisible = chromeVisible)
                else -> state
            }
        }
    }

    /**
     * 应用排版设置。
     *
     * 主题与翻页模式不需要重新分页；字号/行距/字体/边距变化会触发重排，
     * 重排前用当前页字符锚点恢复位置。
     */
    fun applySettings(newSettings: ReaderSettings) {
        val safe = newSettings.sanitized()
        val old = settings
        val layoutChanged = old.fontSizeSp != safe.fontSizeSp ||
            old.lineHeightMultiplier != safe.lineHeightMultiplier ||
            old.fontFamily != safe.fontFamily ||
            old.horizontalPaddingDp != safe.horizontalPaddingDp
        settings = safe
        chromeVisible = when (val state = _uiState.value) {
            is ReaderUiState.Ready -> state.chromeVisible
            is ReaderUiState.PdfReady -> state.chromeVisible
            else -> chromeVisible
        }
        viewModelScope.launch { runCatching { settingsRepository.update(safe) } }

        _uiState.update { state ->
            when (state) {
                is ReaderUiState.Ready -> state.copy(settings = safe)
                is ReaderUiState.PdfReady -> state.copy(settings = safe)
                else -> state
            }
        }

        val isScannedPdf = book?.isScannedPdf == true
        if (layoutChanged && !isScannedPdf) {
            startPagination()
        }
    }

    fun retry() {
        paginationJob?.cancel()
        pageIndex = null
        readySignature = null
        paginationSignature = null
        savedAnchor = null
        loadBook()
    }

    override fun onCleared() {
        saveNow()
        paginationJob?.cancel()
        saveJob?.cancel()
        super.onCleared()
    }

    companion object {
        const val SAVE_DEBOUNCE_MILLIS = 400L

        fun factory(container: AppContainer, bookId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ReaderViewModel(
                        bookId = bookId,
                        shelfRepository = container.shelfRepository,
                        readingRepository = container.readingRepository,
                        settingsRepository = container.settingsRepository,
                        pageBreaker = container.pageBreaker,
                        pageIndexCache = container.pageIndexCache,
                        fileStore = container.fileStore,
                        hiddenShelfSession = container.hiddenShelfSession,
                        progressCalculator = container.progressCalculator,
                        clock = container.clock,
                        dispatchers = container.dispatchers,
                        appScope = container.appScope,
                    )
                }
            }
    }
}
