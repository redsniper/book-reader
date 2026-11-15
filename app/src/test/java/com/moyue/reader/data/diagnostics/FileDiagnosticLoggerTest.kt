package com.moyue.reader.data.diagnostics

import com.moyue.reader.MutableClock
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 诊断日志的关键约束：
 * - 单行结构化、换行转义，避免破坏日志格式；
 * - 高频事件按最小间隔节流；
 * - 文件按容量滚动，最多保留两份，避免写满真机磁盘。
 */
class FileDiagnosticLoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var directory: File
    private val clock = MutableClock(1_700_000_000_000L)

    @Before
    fun setUp() {
        directory = tempFolder.newFolder("diagnostics")
    }

    @Test
    fun `writes structured single-line entries and escapes values`() {
        val logger = FileDiagnosticLogger(directory, clock)
        try {
            logger.log(
                "reader.open",
                mapOf("format" to "TXT", "bad" to "line1\nline2", "ms" to 12),
            )
            logger.flushBlocking(2_000)

            val text = File(directory, "moyue.log").readText()
            assertTrue(text.contains("diagnostics.session"))
            assertTrue(text.contains("reader.open"))
            assertTrue(text.contains("format=\"TXT\""))
            assertTrue(text.contains("ms=12"))
            assertTrue(text.contains("line1 line2"))
            assertFalse(text.contains("line1\nline2"))
        } finally {
            logger.close()
        }
    }

    @Test
    fun `throttles repeated event within interval`() {
        val logger = FileDiagnosticLogger(directory, clock)
        try {
            logger.log("reader.settings", mapOf("font" to 18), minIntervalMs = 1_000)
            logger.log("reader.settings", mapOf("font" to 19), minIntervalMs = 1_000)
            clock.now += 1_000
            logger.log("reader.settings", mapOf("font" to 20), minIntervalMs = 1_000)
            logger.flushBlocking(2_000)

            val events = File(directory, "moyue.log")
                .readLines()
                .filter { it.contains("reader.settings") }
            assertEquals(2, events.size)
            assertTrue(events.last().contains("font=20"))
        } finally {
            logger.close()
        }
    }

    @Test
    fun `rotates to bounded number of files`() {
        val logger = FileDiagnosticLogger(
            directory = directory,
            clock = clock,
            maxBytes = 600,
            maxFiles = 2,
        )
        try {
            repeat(40) { index ->
                logger.log("test.event", mapOf("i" to index, "pad" to "abcdefghijklmnopqrstuvwxyz"))
            }
            logger.flushBlocking(3_000)

            val files = directory.listFiles()
                .orEmpty()
                .filter { it.name.startsWith(FileDiagnosticLogger.CURRENT_FILE_NAME) }
                .sortedBy { it.name }
            assertEquals(2, files.size)
            // 单文件允许因“先判断后写入”多出一行，但必须有明确上限。
            files.forEach { assertTrue("${it.name} size=${it.length()}", it.length() <= 900) }
            assertTrue(files.sumOf { it.length() } < 2_000)
        } finally {
            logger.close()
        }
    }
}
