package com.moyue.reader.domain.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {

    private val hasher = Pbkdf2PasswordHasher(iterations = 2_000)

    @Test
    fun `same password verifies`() {
        val stored = hasher.hash("墨阅1234".toCharArray())
        assertTrue(hasher.verify("墨阅1234".toCharArray(), stored))
    }

    @Test
    fun `wrong password fails`() {
        val stored = hasher.hash("墨阅1234".toCharArray())
        assertFalse(hasher.verify("wrong".toCharArray(), stored))
    }

    @Test
    fun `salt is random and hashes differ`() {
        val first = hasher.hash("same-password".toCharArray())
        val second = hasher.hash("same-password".toCharArray())
        assertNotEquals(first.salt.toList(), second.salt.toList())
        assertNotEquals(first.hash.toList(), second.hash.toList())
        assertTrue(hasher.verify("same-password".toCharArray(), first))
        assertTrue(hasher.verify("same-password".toCharArray(), second))
    }

    @Test
    fun `algorithm and iteration metadata are stored`() {
        val stored = hasher.hash("abcd".toCharArray())
        assertEqualsCompat(Pbkdf2PasswordHasher.ALGORITHM, stored.algorithm)
        assertEqualsCompat(2_000, stored.iterations)
        assertEqualsCompat(16, stored.salt.size)
        assertEqualsCompat(32, stored.hash.size)
    }

    @Test
    fun `verify rejects malformed stored hash`() {
        val stored = HashedPassword(
            algorithm = "unknown",
            iterations = 0,
            salt = ByteArray(0),
            hash = ByteArray(0),
        )
        assertFalse(hasher.verify("abcd".toCharArray(), stored))
    }

    @Test
    fun `password rules enforce 4 to 64 chars`() {
        assertFalse(PasswordRules.isValid("123"))
        assertTrue(PasswordRules.isValid("1234"))
        assertTrue(PasswordRules.isValid("密".repeat(64)))
        assertFalse(PasswordRules.isValid("密".repeat(65)))
    }

    private fun assertEqualsCompat(expected: Any, actual: Any) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
