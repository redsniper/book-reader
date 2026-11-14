package com.moyue.reader

import android.app.Application
import java.io.File
import com.moyue.reader.core.DefaultDispatcherProvider
import com.moyue.reader.core.DiagnosticLogger
import com.moyue.reader.core.DispatcherProvider
import com.moyue.reader.core.ExternalOpenQueue
import com.moyue.reader.core.SystemClock
import com.moyue.reader.core.Clock
import com.moyue.reader.data.cache.FilePageIndexCache
import com.moyue.reader.data.diagnostics.DiagnosticExporter
import com.moyue.reader.data.diagnostics.FileDiagnosticLogger
import com.moyue.reader.data.db.AppDatabase
import com.moyue.reader.data.file.BookFileStore
import com.moyue.reader.data.parser.EpubParser
import com.moyue.reader.data.parser.PdfParser
import com.moyue.reader.data.parser.TxtParser
import com.moyue.reader.data.repository.DataStoreSettingsRepository
import com.moyue.reader.data.repository.RoomBookRepository
import com.moyue.reader.data.repository.SharedPreferencesHiddenPasswordStore
import com.moyue.reader.data.repository.readerSettingsDataStore
import com.moyue.reader.domain.crypto.Pbkdf2PasswordHasher
import com.moyue.reader.domain.parser.BookParserFactory
import com.moyue.reader.domain.reader.PageBreaker
import com.moyue.reader.domain.reader.PageIndexCache
import com.moyue.reader.domain.repository.ReadingRepository
import com.moyue.reader.domain.repository.ReadingSettingsRepository
import com.moyue.reader.domain.repository.ShelfRepository
import com.moyue.reader.domain.service.BookImportService
import com.moyue.reader.domain.service.ChapterSplitter
import com.moyue.reader.domain.service.HiddenShelfManager
import com.moyue.reader.domain.service.HiddenShelfSession
import com.moyue.reader.domain.service.ProgressCalculator
import com.moyue.reader.platform.pagination.AndroidPageBreaker
import com.moyue.reader.platform.pdf.PdfBoxTextExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手写依赖注入容器（组合根）。
 *
 * 所有依赖在此组装；UI 层只拿到接口。使用 lazy 延迟创建，避免冷启动时
 * 打开数据库或初始化解析器。appScope 用于不随页面销毁的收尾写操作（如保存进度）。
 */
class AppContainer(application: Application) {

    val appContext = application.applicationContext

    val dispatchers: DispatcherProvider = DefaultDispatcherProvider
    val clock: Clock = SystemClock

    /** 应用级协程作用域：退出阅读器后仍能完成进度落库。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 外部“用其他应用打开”请求的进程内队列。
     *
     * Activity 在 onCreate/onNewIntent 写入，根级 ExternalOpenViewModel 串行消费；
     * 只存在内存，不参与持久化（进程被杀即丢弃）。
     */
    val externalOpenQueue: ExternalOpenQueue = ExternalOpenQueue()

    /**
     * 真机诊断日志。
     *
     * 放在应用专属外部目录（`Android/data/<包名>/files/diagnostics`），
     * 无需存储权限；用户可在设置页一键导出到“下载”目录反馈。
     */
    val diagnostics: DiagnosticLogger by lazy {
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        FileDiagnosticLogger(File(base, "diagnostics"), clock)
    }

    val diagnosticExporter: DiagnosticExporter by lazy {
        DiagnosticExporter(diagnostics, dispatchers)
    }

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }

    val fileStore: BookFileStore by lazy { BookFileStore(appContext.filesDir) }

    val bookRepository: RoomBookRepository by lazy {
        RoomBookRepository(
            database = database,
            fileStore = fileStore,
            dispatchers = dispatchers,
            clock = clock,
        )
    }

    val shelfRepository: ShelfRepository get() = bookRepository
    val readingRepository: ReadingRepository get() = bookRepository

    val settingsRepository: ReadingSettingsRepository by lazy {
        DataStoreSettingsRepository(appContext.readerSettingsDataStore, dispatchers)
    }

    val passwordStore: SharedPreferencesHiddenPasswordStore by lazy {
        SharedPreferencesHiddenPasswordStore(
            appContext.getSharedPreferences(
                SharedPreferencesHiddenPasswordStore.PREFERENCES_NAME,
                android.content.Context.MODE_PRIVATE,
            ),
        )
    }

    val hiddenShelfSession: HiddenShelfSession by lazy { HiddenShelfSession() }

    val hiddenShelfManager: HiddenShelfManager by lazy {
        HiddenShelfManager(
            passwordStore = passwordStore,
            hasher = Pbkdf2PasswordHasher(),
            shelfRepository = bookRepository,
            dispatchers = dispatchers,
        )
    }

    val chapterSplitter: ChapterSplitter by lazy { ChapterSplitter() }

    val parserFactory: BookParserFactory by lazy {
        BookParserFactory(
            listOf(
                TxtParser(chapterSplitter),
                EpubParser(chapterSplitter),
                PdfParser(PdfBoxTextExtractor(), chapterSplitter),
            ),
        )
    }

    val importService: BookImportService by lazy {
        BookImportService(
            repository = bookRepository,
            fileStore = fileStore,
            parserFactory = parserFactory,
            dispatchers = dispatchers,
            clock = clock,
            diagnostics = diagnostics,
        )
    }

    val pageBreaker: PageBreaker by lazy { AndroidPageBreaker() }

    /**
     * 页索引文件缓存。与分页器分开注入，方便测试替换为内存实现；
     * 缓存目录位于每本书的私有目录下，删除书籍时一并清理。
     */
    val pageIndexCache: PageIndexCache by lazy { FilePageIndexCache(fileStore) }

    val progressCalculator: ProgressCalculator by lazy { ProgressCalculator() }
}
