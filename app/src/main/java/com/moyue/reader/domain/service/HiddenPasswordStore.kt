package com.moyue.reader.domain.service

/**
 * 隐藏书架密码的本地存取抽象。
 *
 * 实现只保存 [com.moyue.reader.domain.crypto.HashedPassword]，不得保存明文。
 * 存/取均为同步轻量操作，哈希计算由 HiddenShelfManager 放到后台线程。
 */
interface HiddenPasswordStore {
    fun load(): com.moyue.reader.domain.crypto.HashedPassword?

    fun save(password: com.moyue.reader.domain.crypto.HashedPassword)

    fun clear()
}
