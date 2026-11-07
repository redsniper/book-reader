package com.moyue.reader.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.moyue.reader.core.DispatcherProvider
import com.moyue.reader.domain.model.FontFamilyOption
import com.moyue.reader.domain.model.PageMode
import com.moyue.reader.domain.model.ReaderSettings
import com.moyue.reader.domain.model.ReaderTheme
import com.moyue.reader.domain.repository.ReadingSettingsRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * DataStore 阅读设置仓库。
 *
 * 读取时做容错：枚举值损坏、数值越界都会回退默认值，避免坏数据导致阅读器无法打开。
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val dispatchers: DispatcherProvider,
) : ReadingSettingsRepository {

    override val settings: Flow<ReaderSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { prefs -> decode(prefs) }
        .flowOn(dispatchers.io)

    override suspend fun update(settings: ReaderSettings) {
        withContext(dispatchers.io) {
            val safe = settings.sanitized()
            dataStore.edit { prefs ->
                prefs[KEY_THEME] = safe.theme.name
                prefs[KEY_FONT_SIZE] = safe.fontSizeSp
                prefs[KEY_LINE_HEIGHT] = safe.lineHeightMultiplier
                prefs[KEY_FONT_FAMILY] = safe.fontFamily.name
                prefs[KEY_PAGE_MODE] = safe.pageMode.name
                prefs[KEY_PADDING] = safe.horizontalPaddingDp
            }
        }
    }

    private fun decode(prefs: Preferences): ReaderSettings {
        val defaults = ReaderSettings()
        val theme = prefs[KEY_THEME]
            ?.let { value -> runCatching { ReaderTheme.valueOf(value) }.getOrNull() }
            ?: defaults.theme
        val fontFamily = prefs[KEY_FONT_FAMILY]
            ?.let { value -> runCatching { FontFamilyOption.valueOf(value) }.getOrNull() }
            ?: defaults.fontFamily
        val pageMode = prefs[KEY_PAGE_MODE]
            ?.let { value -> runCatching { PageMode.valueOf(value) }.getOrNull() }
            ?: defaults.pageMode
        return ReaderSettings(
            theme = theme,
            fontSizeSp = prefs[KEY_FONT_SIZE] ?: defaults.fontSizeSp,
            lineHeightMultiplier = prefs[KEY_LINE_HEIGHT] ?: defaults.lineHeightMultiplier,
            fontFamily = fontFamily,
            pageMode = pageMode,
            horizontalPaddingDp = prefs[KEY_PADDING] ?: defaults.horizontalPaddingDp,
        ).sanitized()
    }

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_FONT_SIZE = floatPreferencesKey("font_size_sp")
        private val KEY_LINE_HEIGHT = floatPreferencesKey("line_height")
        private val KEY_FONT_FAMILY = stringPreferencesKey("font_family")
        private val KEY_PAGE_MODE = stringPreferencesKey("page_mode")
        private val KEY_PADDING = intPreferencesKey("horizontal_padding_dp")
    }
}
