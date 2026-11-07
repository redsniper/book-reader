package com.moyue.reader.domain.service

import com.moyue.reader.core.DispatcherProvider
import com.moyue.reader.domain.crypto.PasswordHasher
import com.moyue.reader.domain.crypto.PasswordRules
import com.moyue.reader.domain.repository.ShelfRepository
import kotlinx.coroutines.withContext

/** 密码设置/修改的结果。UI 根据类型展示具体文案。 */
sealed interface PasswordActionResult {
    data object Success : PasswordActionResult
    data object NotSet : PasswordActionResult
    data object AlreadySet : PasswordActionResult
    data object InvalidLength : PasswordActionResult
    data object WrongOldPassword : PasswordActionResult
}

/**
 * 隐藏书架密码管理用例。
 *
 * 所有密码哈希计算在后台调度器执行；密码字符数组不会被持久化。
 * 忘记密码时只能通过 [resetHiddenShelf] 清空隐藏书籍并删除密码（已与需求方确认）。
 */
class HiddenShelfManager(
    private val passwordStore: HiddenPasswordStore,
    private val hasher: PasswordHasher,
    private val shelfRepository: ShelfRepository,
    private val dispatchers: DispatcherProvider,
) {
    fun isPasswordSet(): Boolean = passwordStore.load() != null

    suspend fun verify(password: CharArray): Boolean = withContext(dispatchers.default) {
        val stored = passwordStore.load() ?: return@withContext false
        hasher.verify(password, stored)
    }

    /** 首次设置密码；已设置时必须走修改流程，避免绕过旧密码。 */
    suspend fun setInitialPassword(newPassword: CharArray): PasswordActionResult {
        if (isPasswordSet()) return PasswordActionResult.AlreadySet
        if (!PasswordRules.isValid(newPassword)) return PasswordActionResult.InvalidLength
        return withContext(dispatchers.default) {
            passwordStore.save(hasher.hash(newPassword))
            PasswordActionResult.Success
        }
    }

    suspend fun changePassword(
        oldPassword: CharArray,
        newPassword: CharArray,
    ): PasswordActionResult {
        if (!isPasswordSet()) return PasswordActionResult.NotSet
        if (!PasswordRules.isValid(newPassword)) return PasswordActionResult.InvalidLength
        return withContext(dispatchers.default) {
            val stored = passwordStore.load() ?: return@withContext PasswordActionResult.NotSet
            if (!hasher.verify(oldPassword, stored)) {
                return@withContext PasswordActionResult.WrongOldPassword
            }
            passwordStore.save(hasher.hash(newPassword))
            PasswordActionResult.Success
        }
    }

    /**
     * 清空隐藏书架并删除密码。
     *
     * @return 删除的隐藏书籍数量。文件与数据库级联清理由仓库完成。
     */
    suspend fun resetHiddenShelf(): Int = withContext(dispatchers.io) {
        val deleted = shelfRepository.deleteAllHidden()
        passwordStore.clear()
        deleted
    }
}
