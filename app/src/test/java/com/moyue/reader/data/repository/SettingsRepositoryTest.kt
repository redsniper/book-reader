package com.moyue.reader.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.moyue.reader.FakeDispatcherProvider
import com.moyue.reader.domain.model.FontFamilyOption
import com.moyue.reader.domain.model.PageMode
import com.moyue.reader.domain.model.ReaderSettings
import com.moyue.reader.domain.model.ReaderTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var repository: DataStoreSettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.root, "settings.preferences_pb")
        }
        repository = DataStoreSettingsRepository(
            dataStore = dataStore,
            dispatchers = FakeDispatcherProvider(UnconfinedTestDispatcher()),
        )
        // 避免 ApplicationProvider 未使用告警（Context 由测试框架提供）。
        check(context.packageName.isNotBlank())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `returns defaults when empty`() = runTest {
        assertEquals(ReaderSettings(), repository.settings.first())
    }

    @Test
    fun `persists and reads updated settings`() = runTest {
        val custom = ReaderSettings(
            theme = ReaderTheme.NIGHT,
            fontSizeSp = 24f,
            lineHeightMultiplier = 2.1f,
            fontFamily = FontFamilyOption.SANS,
            pageMode = PageMode.VERTICAL,
            horizontalPaddingDp = 32,
        )
        repository.update(custom)
        assertEquals(custom, repository.settings.first())
    }

    @Test
    fun `sanitizes invalid settings before writing`() = runTest {
        repository.update(
            ReaderSettings(
                fontSizeSp = 999f,
                lineHeightMultiplier = -1f,
                horizontalPaddingDp = 0,
            ),
        )
        val loaded = repository.settings.first()
        assertEquals(32f, loaded.fontSizeSp, 0.0001f)
        assertEquals(1.2f, loaded.lineHeightMultiplier, 0.0001f)
        assertEquals(16, loaded.horizontalPaddingDp)
    }
}
