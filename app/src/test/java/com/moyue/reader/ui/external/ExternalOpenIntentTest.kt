package com.moyue.reader.ui.external

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 外部打开入口的两条契约：
 * 1. `ExternalOpenIntentReader` 只接受“本地文件 + VIEW”，其余 Intent 必须原样忽略；
 * 2. 清单里的过滤器真的能被系统按 MIME 查询到——这是本次问题的根因，
 *    必须由测试锁住，否则以后有人删掉过滤器又会出现“候选列表里没有墨阅”。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalOpenIntentTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `reads view intent with content uri and mime`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://com.android.providers.downloads/document/42"), "text/plain")
        }

        val request = ExternalOpenIntentReader.read(intent)

        assertEquals("content://com.android.providers.downloads/document/42", request?.uri)
        assertEquals("text/plain", request?.mimeHint)
    }

    @Test
    fun `reads legacy file uri`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("file:///storage/emulated/0/Download/book.epub"), "application/epub+zip")
        }

        assertEquals(
            "file:///storage/emulated/0/Download/book.epub",
            ExternalOpenIntentReader.read(intent)?.uri,
        )
    }

    @Test
    fun `ignores non view actions and non local schemes`() {
        assertNull(ExternalOpenIntentReader.read(null))
        assertNull(
            ExternalOpenIntentReader.read(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            ),
        )
        assertNull(
            ExternalOpenIntentReader.read(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, Uri.parse("content://docs/1"))
                },
            ),
        )
        assertNull(
            ExternalOpenIntentReader.read(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/book.txt")),
            ),
        )
        assertNull(ExternalOpenIntentReader.read(Intent(Intent.ACTION_VIEW)))
    }

    @Test
    fun `manifest filter is discoverable for every supported mime`() {
        val types = listOf(
            "text/plain",
            "application/epub+zip",
            "application/pdf",
            "application/octet-stream",
        )

        types.forEach { mime ->
            // 必须带 data（content://）：过滤器声明了 scheme，系统要求 intent 同时有
            // 匹配的 scheme + type 才算命中；单纯 setType 在真机上也不会匹配。
            val intent = Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(Uri.parse("content://com.android.providers.downloads/document/1"), mime)
            }
            val resolvers = context.packageManager
                .queryIntentActivities(intent, 0)
                .map { it.activityInfo.name }
            assertTrue(
                "系统按 $mime 查询时未找到 MainActivity：${resolvers}",
                resolvers.contains("com.moyue.reader.MainActivity"),
            )
        }
    }

    @Test
    fun `manifest does not hijack unrelated files`() {
        listOf("image/jpeg", "video/mp4", "application/vnd.android.package-archive").forEach { mime ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(Uri.parse("content://com.android.providers.downloads/document/1"), mime)
            }
            val resolvers = context.packageManager
                .queryIntentActivities(intent, 0)
                .map { it.activityInfo.name }
            assertTrue(
                "不应为 $mime 声明过滤：${resolvers}",
                !resolvers.contains("com.moyue.reader.MainActivity"),
            )
        }
    }
}
