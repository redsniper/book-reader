package com.moyue.reader.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** 阅读设置 DataStore 的进程级单例。 */
private const val READER_SETTINGS_NAME = "reader_settings"

val Context.readerSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = READER_SETTINGS_NAME,
)
