package com.moyue.reader

import com.moyue.reader.domain.model.ReaderSettings
import com.moyue.reader.domain.repository.ReadingSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 内存阅读设置仓库。 */
class FakeSettingsRepository(
    initial: ReaderSettings = ReaderSettings(),
) : ReadingSettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: Flow<ReaderSettings> = state

    val current: ReaderSettings get() = state.value

    override suspend fun update(settings: ReaderSettings) {
        state.value = settings.sanitized()
    }
}
