package com.moyue.reader.domain.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 本地保存的密码哈希。
 *
 * 注意：salt/hash 是二进制数组，data class 的 equals 无法做内容比较；
 * 测试与业务代码应逐字段比较，不要依赖 data class 自动生成的 equals。
 */
data class HashedPassword(
    val algorithm: String,
    val iterations: Int,
    val salt: ByteArray,
    val hash: ByteArray,
)

/** 密码哈希策略。实现必须使用随机盐、慢哈希与常量时间比较。 */
interface PasswordHasher {
    fun hash(password: CharArray): HashedPassword
    fun verify(password: CharArray, stored: HashedPassword): Boolean
}

/**
 * PBKDF2WithHmacSHA256 实现。
 *
 * 参数选择：120,000 次迭代、16 字节盐、256 位输出。
 * 在目标设备上耗时约百毫秒级，足以显著提高离线暴力破解成本。
 */
class Pbkdf2PasswordHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val saltSizeBytes: Int = 16,
    private val keyLengthBits: Int = 256,
    private val secureRandom: SecureRandom = SecureRandom(),
) : PasswordHasher {

    override fun hash(password: CharArray): HashedPassword {
        val salt = ByteArray(saltSizeBytes).also(secureRandom::nextBytes)
        val derived = derive(password, salt, iterations, keyLengthBits)
        return HashedPassword(
            algorithm = ALGORITHM,
            iterations = iterations,
            salt = salt,
            hash = derived,
        )
    }

    override fun verify(password: CharArray, stored: HashedPassword): Boolean {
        if (!stored.algorithm.equals(ALGORITHM, ignoreCase = true)) return false
        if (stored.iterations <= 0 || stored.salt.isEmpty() || stored.hash.isEmpty()) return false
        val actual = derive(password, stored.salt, stored.iterations, stored.hash.size * 8)
        // 常量时间比较，避免按字节提前返回的时序侧信道。
        return MessageDigest.isEqual(actual, stored.hash)
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBits: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        return try {
            val factory = SecretKeyFactory.getInstance(ALGORITHM)
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val DEFAULT_ITERATIONS = 120_000
    }
}

/** 密码格式校验：4–64 个字符。 */
object PasswordRules {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 64

    fun isValid(password: CharArray): Boolean =
        password.size in MIN_LENGTH..MAX_LENGTH

    /** 仅用于 UI 提示，不用于存储。 */
    fun isValid(password: String): Boolean = isValid(password.toCharArray())
}
