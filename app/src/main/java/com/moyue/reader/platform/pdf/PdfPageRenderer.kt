package com.moyue.reader.platform.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 扫描版 PDF 页面渲染器。
 *
 * Android PdfRenderer 不是线程安全的，且必须在同一线程串行访问；
 * 这里使用单线程调度器 + Mutex 串行化渲染，并用 LruCache 缓存最近页面。
 *
 * 调用方必须在组合销毁时调用 [close]，释放原生资源。
 */
class PdfPageRenderer(
    file: File,
    private val cachePages: Int = DEFAULT_CACHE_PAGES,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "moyue-pdf-renderer").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val mutex = Mutex()

    private val descriptor: ParcelFileDescriptor
    private val renderer: PdfRenderer

    @Volatile
    private var closed = false

    private val cache = object : android.util.LruCache<Int, Bitmap>(cachePages) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    val pageCount: Int

    init {
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(descriptor)
        pageCount = renderer.pageCount
    }

    /**
     * 渲染指定页（从 0 开始），按目标宽度等比缩放。
     *
     * @return 位图；关闭/异常/取消时返回 null，UI 显示占位。
     */
    suspend fun renderPage(index: Int, targetWidthPx: Int): Bitmap? {
        if (closed || index !in 0 until pageCount || targetWidthPx <= 0) return null
        cache.get(index)?.let { return it }
        return withContext(dispatcher) {
            mutex.withLock {
                if (closed) return@withLock null
                cache.get(index)?.let { return@withLock it }
                try {
                    renderer.openPage(index).use { page ->
                        val width = targetWidthPx.coerceAtLeast(1)
                        val scale = width.toFloat() / page.width.coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        // PdfRenderer 的透明区域需填白，否则夜间主题下可能透出背景。
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(index, bitmap)
                        bitmap
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        // 先停止接收新任务并等待正在渲染的页结束，再释放 PdfRenderer，
        // 避免原生对象在渲染中被销毁导致崩溃。
        executor.shutdown()
        runCatching { executor.awaitTermination(3, TimeUnit.SECONDS) }
        cache.evictAll()
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        private const val DEFAULT_CACHE_PAGES = 4
    }
}
