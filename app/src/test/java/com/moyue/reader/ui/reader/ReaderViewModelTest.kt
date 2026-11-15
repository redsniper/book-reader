package com.moyue.reader.ui.reader

import com.moyue.reader.FakeDispatcherProvider
import com.moyue.reader.FakeSettingsRepository
import com.moyue.reader.FakeShelfRepository
import com.moyue.reader.MainDispatcherRule
import com.moyue.reader.MutableClock
import com.moyue.reader.data.cache.FilePageIndexCache
import com.moyue.reader.data.file.BookFileStore
import com.moyue.reader.domain.model.Book
import com.moyue.reader.domain.model.BookFormat
import com.moyue.reader.domain.model.Chapter
import com.moyue.reader.domain.model.FontFamilyOption
import com.moyue.reader.domain.model.ReaderSettings
import com.moyue.reader.domain.model.ReaderTheme
import com.moyue.reader.domain.model.ReadingProgress
import com.moyue.reader.domain.reader.PageBreaker
import com.moyue.reader.domain.reader.PageIndexCache
import com.moyue.reader.domain.reader.PageLayoutSpec
import com.moyue.reader.domain.reader.PageRange
import com.moyue.reader.domain.repository.ReadingSettingsRepository
import com.moyue.reader.domain.service.HiddenShelfSession
import com.moyue.reader.domain.service.ProgressCalculator
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repository: FakeShelfRepository
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var fileStore: BookFileStore
    private lateinit var pageIndexCache: PageIndexCache
    private lateinit var session: HiddenShelfSession
    private lateinit var clock: MutableClock
    private lateinit var dispatchers: FakeDispatcherProvider
    private lateinit var appScope: CoroutineScope

    @Before
    fun setUp() {
        repository = FakeShelfRepository()
        settingsRepository = FakeSettingsRepository()
        fileStore = BookFileStore(tempFolder.newFolder("files"))
        pageIndexCache = FilePageIndexCache(fileStore)
        session = HiddenShelfSession()
        session.unlock()
        clock = MutableClock(1_000L)
        dispatchers = FakeDispatcherProvider(UnconfinedTestDispatcher())
        appScope = CoroutineScope(UnconfinedTestDispatcher())
    }

    @Test
    fun `restores anchor jumps pages and saves progress`() = runTest {
        val textChapters = chapters()
        val book = saveBook(textChapters, initialProgress = ReadingProgress(1, 10, 0.63f, 1L))
        val viewModel = createViewModel(book.id)

        viewModel.onViewportMeasured(viewport())
        val ready = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(6, ready.pageIndex.totalPages)
        assertEquals(4, ready.currentPage)

        viewModel.jumpToPage(2)
        assertEquals(1, (viewModel.uiState.value as ReaderUiState.Ready).currentPage)

        viewModel.onPageChanged(5)
        viewModel.saveNow()

        val saved = repository.getProgress(book.id)!!
        assertEquals(1, saved.chapterIndex)
        assertEquals(20, saved.charOffsetInChapter)
        assertEquals(1f, saved.percent, 0.0001f)
    }

    @Test
    fun `repagination preserves character anchor`() = runTest {
        val book = saveBook(chapters(), initialProgress = ReadingProgress(1, 10, 0.63f, 1L))
        val viewModel = createViewModel(book.id)
        viewModel.onViewportMeasured(viewport())
        val first = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(4, first.currentPage)

        viewModel.applySettings(first.settings.copy(fontSizeSp = 24f))
        // 新架构下由 UI 重新测量视口驱动重排（字号 px 由 Compose 解析）。
        viewModel.onViewportMeasured(viewport(fontSizePx = 24f))

        val repaginated = viewModel.uiState.value as ReaderUiState.Ready
        assertTrue(repaginated.layoutVersion > first.layoutVersion)
        assertEquals(24f, repaginated.settings.fontSizeSp, 0.0001f)
        val anchor = repaginated.pageIndex.anchorForPage(repaginated.currentPage)
        assertEquals(1, anchor.chapterIndex)
        assertEquals(10, anchor.charOffsetInChapter)
    }

    @Test
    fun `theme change does not repaginate`() = runTest {
        val book = saveBook(chapters(), initialProgress = ReadingProgress(0, 0, 0f, 1L))
        val viewModel = createViewModel(book.id)
        viewModel.onViewportMeasured(viewport())
        val before = viewModel.uiState.value as ReaderUiState.Ready

        viewModel.applySettings(before.settings.copy(theme = com.moyue.reader.domain.model.ReaderTheme.NIGHT))

        val after = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(before.layoutVersion, after.layoutVersion)
        assertEquals(com.moyue.reader.domain.model.ReaderTheme.NIGHT, after.settings.theme)
    }

    @Test
    fun `reopening same layout reuses cached page index`() = runTest {
        val book = saveBook(chapters(), initialProgress = ReadingProgress(1, 10, 0.63f, 1L))
        val breaker = CountingPageBreaker()

        val first = createViewModel(book.id, breaker)
        first.onViewportMeasured(viewport())
        val firstReady = first.uiState.value as ReaderUiState.Ready
        // 两章各调用一次 breakChapter。
        assertEquals(2, breaker.breakCalls)

        val reopened = createViewModel(book.id, breaker)
        reopened.onViewportMeasured(viewport())
        val reopenedReady = reopened.uiState.value as ReaderUiState.Ready

        // 缓存命中后不应再调用分页器。
        assertEquals(2, breaker.breakCalls)
        assertEquals(firstReady.pageIndex.pages, reopenedReady.pageIndex.pages)
        assertEquals(firstReady.currentPage, reopenedReady.currentPage)
    }

    @Test
    fun `jumping pages does not rebuild page index`() = runTest {
        val book = saveBook(chapters(), initialProgress = ReadingProgress(1, 10, 0.63f, 1L))
        val breaker = CountingPageBreaker()
        val viewModel = createViewModel(book.id, breaker)
        viewModel.onViewportMeasured(viewport())
        val before = viewModel.uiState.value as ReaderUiState.Ready
        val callsBefore = breaker.breakCalls

        viewModel.jumpToPage(2)
        viewModel.onPageChanged(5)
        val after = viewModel.uiState.value as ReaderUiState.Ready

        assertEquals(callsBefore, breaker.breakCalls)
        assertEquals(before.layoutVersion, after.layoutVersion)
        assertEquals(5, after.currentPage)
    }

    @Test
    fun `layout change repaginates instead of using stale cache`() = runTest {
        val book = saveBook(chapters(), initialProgress = ReadingProgress(0, 0, 0f, 1L))
        val breaker = CountingPageBreaker()
        val viewModel = createViewModel(book.id, breaker)
        viewModel.onViewportMeasured(viewport())
        val first = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(2, breaker.breakCalls)

        viewModel.applySettings(first.settings.copy(fontSizeSp = 24f))
        viewModel.onViewportMeasured(viewport(fontSizePx = 24f))

        val repaginated = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(4, breaker.breakCalls)
        assertTrue(repaginated.layoutVersion > first.layoutVersion)
        assertEquals(24f, repaginated.settings.fontSizeSp, 0.0001f)
    }

    @Test
    fun `viewport font metrics drive pagination even before settings update`() = runTest {
        val book = saveBook(chapters(), initialProgress = ReadingProgress(0, 0, 0f, 1L))
        val breaker = CountingPageBreaker()
        val viewModel = createViewModel(book.id, breaker)
        viewModel.onViewportMeasured(viewport())
        val first = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(2, breaker.breakCalls)

        // 只改视口解析出的字号（模拟 Compose 的非线性字体缩放），不调用 applySettings：
        // 分页必须以视口度量为准，避免设置值与实际渲染字号不一致导致半屏空白。
        viewModel.onViewportMeasured(viewport(fontSizePx = 24f))

        val repaginated = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(4, breaker.breakCalls)
        assertTrue(repaginated.layoutVersion > first.layoutVersion)
    }

    @Test
    fun `loading viewport measured with default settings is discarded`() = runTest {
        val book = saveBook(chapters(), initialProgress = ReadingProgress(1, 10, 0.63f, 1L))
        val breaker = CountingPageBreaker()
        val gatedSettings = GateSettingsRepository(ReaderSettings(fontSizeSp = 24f))
        val viewModel = createViewModel(book.id, breaker, gatedSettings)

        // 模拟真实首帧：设置还没从 DataStore 读出，ReaderScreen 先按默认 18sp
        // 测到视口；这个视口不能用于分页，否则会先排一套 18sp 索引，再按 24sp
        // 重排，两次之间只剩“页起点”，页内字符偏移丢失导致进度前跳。
        assertTrue(viewModel.uiState.value is ReaderUiState.Loading)
        viewModel.onViewportMeasured(viewport(fontSizePx = 18f))
        assertEquals(0, breaker.breakCalls)

        gatedSettings.release()
        val paginating = viewModel.uiState.value as ReaderUiState.Paginating
        assertEquals(24f, paginating.settings.fontSizeSp, 0.0001f)
        assertEquals(0, breaker.breakCalls)

        // 设置发布后，ReaderScreen 会用同一帧的真实字号重新测量并只排一次。
        viewModel.onViewportMeasured(viewport(fontSizePx = 24f))
        val ready = viewModel.uiState.value as ReaderUiState.Ready
        assertEquals(24f, ready.settings.fontSizeSp, 0.0001f)
        assertEquals(2, breaker.breakCalls)
        val anchor = ready.pageIndex.anchorForPage(ready.currentPage)
        assertEquals(1, anchor.chapterIndex)
        assertEquals(10, anchor.charOffsetInChapter)
    }

    @Test
    fun `hidden book is blocked until session unlocks`() = runTest {
        session.lock()
        val book = saveBook(chapters(), initialProgress = ReadingProgress(0, 0, 0f, 1L), hidden = true)
        val locked = createViewModel(book.id)
        assertEquals(ReaderUiState.Error(ReaderError.HIDDEN_LOCKED), locked.uiState.value)

        session.unlock()
        val unlocked = createViewModel(book.id)
        viewModelReady(unlocked)
    }

    @Test
    fun `background lock revokes hidden reader and saves progress`() = runTest {
        val book = saveBook(chapters(), initialProgress = ReadingProgress(0, 0, 0f, 1L), hidden = true)
        val viewModel = createViewModel(book.id)
        viewModel.onViewportMeasured(viewport())
        val ready = viewModel.uiState.value as ReaderUiState.Ready
        viewModel.onPageChanged(1)
        val expectedAnchor = ready.pageIndex.anchorForPage(1)

        // 模拟 MainActivity.onStop：会话锁定后阅读器必须立即丢弃正文。
        session.lock()
        advanceUntilIdle()

        assertEquals(ReaderUiState.Error(ReaderError.HIDDEN_LOCKED), viewModel.uiState.value)
        val saved = repository.getProgress(book.id)!!
        assertEquals(expectedAnchor.chapterIndex, saved.chapterIndex)
        assertEquals(expectedAnchor.charOffsetInChapter, saved.charOffsetInChapter)
    }

    @Test
    fun `background lock keeps normal book readable`() = runTest {
        val book = saveBook(chapters(), initialProgress = ReadingProgress(0, 0, 0f, 1L))
        val viewModel = createViewModel(book.id)
        viewModel.onViewportMeasured(viewport())
        assertTrue(viewModel.uiState.value is ReaderUiState.Ready)

        session.lock()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ReaderUiState.Ready)
    }

    @Test
    fun `hidden lock error keeps user theme`() = runTest {
        settingsRepository.update(ReaderSettings(theme = ReaderTheme.NIGHT))
        session.lock()
        val book = saveBook(chapters(), initialProgress = ReadingProgress(0, 0, 0f, 1L), hidden = true)

        val viewModel = createViewModel(book.id)
        val error = viewModel.uiState.value as ReaderUiState.Error

        assertEquals(ReaderError.HIDDEN_LOCKED, error.error)
        assertEquals(ReaderTheme.NIGHT, error.settings.theme)
    }

    @Test
    fun `scanned pdf opens as page mode and saves page progress`() = runTest {
        val book = saveBook(
            chapters = (0 until 3).map { page ->
                Chapter(page, "第 ${page + 1} 页", 0, 0, "", pdfPageIndex = page)
            },
            initialProgress = ReadingProgress(1, 0, 0.66f, 1L),
            scanned = true,
            extension = "pdf",
        )
        val viewModel = createViewModel(book.id)
        val state = viewModel.uiState.value as ReaderUiState.PdfReady
        assertEquals(3, state.pageCount)
        assertEquals(1, state.currentPage)

        viewModel.jumpToPage(3)
        viewModel.saveNow()
        val saved = repository.getProgress(book.id)!!
        assertEquals(2, saved.chapterIndex)
        assertEquals(1f, saved.percent, 0.0001f)
    }

    private fun createViewModel(
        bookId: String,
        pageBreaker: PageBreaker = AdaptivePageBreaker(),
        settings: ReadingSettingsRepository = settingsRepository,
    ): ReaderViewModel = ReaderViewModel(
        bookId = bookId,
        shelfRepository = repository,
        readingRepository = repository,
        settingsRepository = settings,
        pageBreaker = pageBreaker,
        pageIndexCache = pageIndexCache,
        fileStore = fileStore,
        hiddenShelfSession = session,
        progressCalculator = ProgressCalculator(),
        clock = clock,
        dispatchers = dispatchers,
        appScope = appScope,
    )

    private fun viewModelReady(viewModel: ReaderViewModel) {
        viewModel.onViewportMeasured(viewport())
        assertTrue(viewModel.uiState.value is ReaderUiState.Ready)
    }

    private fun viewport(fontSizePx: Float = 18f) = ReaderViewport(
        widthPx = 200,
        heightPx = 300,
        titleHeightPx = 20,
        fontSizePx = fontSizePx,
        lineHeightPx = fontSizePx * 1.8f,
        fontFamily = FontFamilyOption.SERIF,
    )

    private suspend fun saveBook(
        chapters: List<Chapter>,
        initialProgress: ReadingProgress,
        hidden: Boolean = false,
        scanned: Boolean = false,
        extension: String = "txt",
    ): Book {
        val bookId = UUID.randomUUID().toString()
        val staged = fileStore.stageSource(bookId, extension, 10_000) {
            ByteArrayInputStream("source".toByteArray())
        }
        fileStore.commit(bookId)
        val book = Book(
            id = bookId,
            title = "测试书",
            author = "作者",
            format = if (scanned) BookFormat.PDF else BookFormat.TXT,
            sourcePath = staged.relativePath,
            contentHash = bookId,
            coverPath = null,
            fileSize = staged.sizeBytes,
            hidden = hidden,
            chapterCount = chapters.size,
            totalChars = chapters.sumOf { it.charCount },
            isScannedPdf = scanned,
            importedAt = 1L,
            lastOpenedAt = null,
        )
        repository.saveImportedBook(book, chapters, initialProgress)
        return book
    }

    private fun chapters(): List<Chapter> {
        var cursor = 0
        val first = Chapter(0, "第一章", cursor, 25, "a".repeat(25))
        cursor += 25
        val second = Chapter(1, "第二章", cursor, 30, "b".repeat(30))
        return listOf(first, second)
    }

    /** 设置读取被闸门挡住，用于模拟真实 UI 首帧早于 DataStore 返回的时序。 */
    private class GateSettingsRepository(
        initial: ReaderSettings,
    ) : ReadingSettingsRepository {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
        private val gate = CompletableDeferred<Unit>()

        override val settings: Flow<ReaderSettings> = flow {
            gate.await()
            emit(state.value)
        }

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun update(settings: ReaderSettings) {
            state.value = settings.sanitized()
        }
    }

    /** 统计真实分页调用次数，用于断言缓存命中与跳页不会触发重排。 */
    private class CountingPageBreaker(
        private val delegate: PageBreaker = AdaptivePageBreaker(),
    ) : PageBreaker {
        var breakCalls: Int = 0
            private set

        override fun breakChapter(
            title: String,
            text: String,
            spec: PageLayoutSpec,
        ): List<PageRange> {
            breakCalls += 1
            return delegate.breakChapter(title, text, spec)
        }
    }

    /** 字号越大，每页字符越少，用于验证重排后的锚点恢复。 */
    private class AdaptivePageBreaker : PageBreaker {
        override fun breakChapter(title: String, text: String, spec: PageLayoutSpec): List<PageRange> {
            val charsPerPage = if (spec.fontSizePx > 20f) 5 else 10
            if (text.isEmpty()) return listOf(PageRange(0, 0))
            val pages = mutableListOf<PageRange>()
            var start = 0
            while (start < text.length) {
                val end = (start + charsPerPage).coerceAtMost(text.length)
                pages.add(PageRange(start, end))
                start = end
            }
            return pages
        }
    }
}
