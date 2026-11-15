package com.moyue.reader.ui.external

import android.net.Uri
import com.moyue.reader.FakeDispatcherProvider
import com.moyue.reader.FakeShelfRepository
import com.moyue.reader.MainDispatcherRule
import com.moyue.reader.MutableClock
import com.moyue.reader.R
import com.moyue.reader.core.ExternalOpenQueue
import com.moyue.reader.core.ExternalOpenRequest
import com.moyue.reader.data.file.BookFileStore
import com.moyue.reader.domain.parser.BookParserFactory
import com.moyue.reader.domain.service.BookImportService
import com.moyue.reader.domain.service.ChapterSplitter
import com.moyue.reader.domain.service.ImportRequest
import com.moyue.reader.data.parser.TxtParser
import com.moyue.reader.ui.common.DocumentResolver
import java.io.ByteArrayInputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 外部打开的消费逻辑：串行导入、成功自动打开、重复脱敏、失败复用导入文案。
 *
 * 用真实 [BookImportService] + 内存仓库 + 临时目录，而不是 mock 掉导入，
 * 这样“复制 → SHA-256 → 解析 → 落库”的真实路径也一并被覆盖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalOpenViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repository: FakeShelfRepository
    private lateinit var queue: ExternalOpenQueue
    private lateinit var viewModel: ExternalOpenViewModel

    /** 记录每次 resolve 看到的 Uri，并按顺序返回对应内容。 */
    private lateinit var documents: MutableList<Pair<String, ImportRequest>>

    @Before
    fun setUp() {
        repository = FakeShelfRepository()
        queue = ExternalOpenQueue()
        documents = mutableListOf()
        val dispatchers = FakeDispatcherProvider(UnconfinedTestDispatcher())
        val importService = BookImportService(
            repository = repository,
            fileStore = BookFileStore(tempFolder.newFolder("files")),
            parserFactory = BookParserFactory(listOf(TxtParser(ChapterSplitter()))),
            dispatchers = dispatchers,
            clock = MutableClock(10_000L),
        )
        val resolver = object : DocumentResolver {
            override suspend fun resolve(uri: Uri): ImportRequest {
                val index = documents.indexOfFirst { it.first == uri.toString() }
                return documents[index].second
            }
        }
        viewModel = ExternalOpenViewModel(
            queue = queue,
            importService = importService,
            documentResolver = resolver,
        )
    }

    private fun enqueue(uri: String, mimeHint: String? = null) {
        queue.submit(ExternalOpenRequest(uri = uri, mimeHint = mimeHint))
    }

    private fun document(
        uri: String,
        name: String,
        text: String,
        mime: String? = null,
    ) {
        documents.add(
            uri to ImportRequest(
                displayName = name,
                mimeType = mime,
                sizeBytes = text.toByteArray().size.toLong(),
                openStream = { ByteArrayInputStream(text.toByteArray()) },
            ),
        )
    }

    @Test
    fun `imports and requests navigation to the new book`() = runTest {
        document("content://docs/1", "测试书.txt", "第一章 开始\n正文内容")
        enqueue("content://docs/1")

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.importing)
        assertNull(state.error)
        val bookId = state.openBookId
        assertNotNull(bookId)
        assertEquals(bookId, repository.books().single().id)

        viewModel.consumeOpen()
        assertNull(viewModel.uiState.value.openBookId)
    }

    @Test
    fun `duplicate shows generic message without title and stays closed`() = runTest {
        document("content://docs/1", "重复.txt", "第一章 开始\n同一份正文")
        enqueue("content://docs/1")
        advanceUntilIdle()
        viewModel.consumeOpen()

        document("content://docs/2", "重复.txt", "第一章 开始\n同一份正文")
        enqueue("content://docs/2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.openBookId)
        assertEquals(R.string.external_open_duplicate, state.error?.resId)
        // 关键：不得把已有书名塞进提示，命中的可能是隐藏书籍。
        assertTrue(state.error?.args.isNullOrEmpty())
        assertEquals(1, repository.books().size)
    }

    @Test
    fun `unsupported format reuses import error message`() = runTest {
        document("content://docs/3", "笔记.doc", "任意内容")
        enqueue("content://docs/3")

        advanceUntilIdle()

        assertEquals(R.string.import_error_unsupported, viewModel.uiState.value.error?.resId)
        assertNull(viewModel.uiState.value.openBookId)
        assertTrue(repository.books().isEmpty())
    }

    @Test
    fun `uses intent mime hint when provider reports no type`() = runTest {
        // provider 不给 MIME、文件名也没有扩展名：只有 Intent 上的 type 能救回来。
        document("content://docs/4", "无扩展名书", "第一章 无扩展名\n正文", mime = null)
        enqueue("content://docs/4", mimeHint = "text/plain")

        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals(1, repository.books().size)
        assertNotNull(viewModel.uiState.value.openBookId)
    }

    @Test
    fun `processes multiple requests serially`() = runTest {
        document("content://docs/5", "第一本.txt", "第一章 A\n内容甲")
        document("content://docs/6", "第二本.txt", "第一章 B\n内容乙")
        enqueue("content://docs/5")
        enqueue("content://docs/6")

        advanceUntilIdle()

        assertEquals(2, repository.books().size)
        assertNotNull(viewModel.uiState.value.openBookId)
        assertNull(viewModel.uiState.value.error)
    }
}
