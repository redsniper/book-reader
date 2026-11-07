package com.moyue.reader.data.repository

import android.content.SharedPreferences
import com.moyue.reader.domain.crypto.HashedPassword
import com.moyue.reader.domain.service.HiddenPasswordStore
import java.util.Base64

/**
 * 隐藏书架密码的 SharedPreferences 存储。
 *
 * 只保存 PBKDF2 参数、盐和哈希；不保存明文，也不写入日志。
 * 如果本地数据损坏（Base64 解析失败、字段缺失），视为"未设置密码"，
 * 由 UI 引导重新设置，而不是崩溃。
 */
@android.annotation.SuppressLint("ApplySharedPref")
class SharedPreferencesHiddenPasswordStore(
    private val preferences: SharedPreferences,
) : HiddenPasswordStore {

    override fun load(): HashedPassword? {
        if (!preferences.getBoolean(KEY_PRESENT, false)) return null
        return try {
            val algorithm = preferences.getString(KEY_ALGORITHM, null) ?: return null
            val iterations = preferences.getInt(KEY_ITERATIONS, 0)
            val salt = preferences.getString(KEY_SALT, null)
                ?.let(Base64.getDecoder()::decode) ?: return null
            val hash = preferences.getString(KEY_HASH, null)
                ?.let(Base64.getDecoder()::decode) ?: return null
            if (iterations <= 0 || salt.isEmpty() || hash.isEmpty()) return null
            HashedPassword(
                algorithm = algorithm,
                iterations = iterations,
                salt = salt,
                hash = hash,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun save(password: HashedPassword) {
        preferences.edit()
            .putBoolean(KEY_PRESENT, true)
            .putInt(KEY_VERSION, FORMAT_VERSION)
            .putString(KEY_ALGORITHM, password.algorithm)
            .putInt(KEY_ITERATIONS, password.iterations)
            .putString(KEY_SALT, Base64.getEncoder().encodeToString(password.salt))
            .putString(KEY_HASH, Base64.getEncoder().encodeToString(password.hash))
            .commit()
    }

    override fun clear() {
        preferences.edit().clear().commit()
    }

    companion object {
        const val PREFERENCES_NAME = "hidden_shelf_security"
        private const val FORMAT_VERSION = 1
        private const val KEY_PRESENT = "present"
        private const val KEY_VERSION = "version"
        private const val KEY_ALGORITHM = "algorithm"
        private const val KEY_ITERATIONS = "iterations"
        private const val KEY_SALT = "salt_b64"
        private const val KEY_HASH = "hash_b64"
    }
}
