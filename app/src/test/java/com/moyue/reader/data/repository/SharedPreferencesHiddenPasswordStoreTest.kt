package com.moyue.reader.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.moyue.reader.domain.crypto.HashedPassword
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPreferencesHiddenPasswordStoreTest {

    private fun newStore(name: String = "test_security_${System.nanoTime()}") =
        SharedPreferencesHiddenPasswordStore(
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences(name, Context.MODE_PRIVATE),
        )

    @Test
    fun `saves and loads hash fields`() {
        val store = newStore()
        val hash = HashedPassword(
            algorithm = "PBKDF2WithHmacSHA256",
            iterations = 120_000,
            salt = ByteArray(16) { it.toByte() },
            hash = ByteArray(32) { (it + 1).toByte() },
        )
        store.save(hash)
        val loaded = store.load()!!

        assertTrue(loaded.algorithm == hash.algorithm)
        assertTrue(loaded.iterations == hash.iterations)
        assertArrayEquals(hash.salt, loaded.salt)
        assertArrayEquals(hash.hash, loaded.hash)
    }

    @Test
    fun `clear removes password`() {
        val store = newStore()
        store.save(
            HashedPassword(
                algorithm = "PBKDF2WithHmacSHA256",
                iterations = 1_000,
                salt = ByteArray(16),
                hash = ByteArray(32),
            ),
        )
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `corrupted preferences are treated as no password`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test_security_corrupt_${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("present", true)
            .putString("algorithm", "PBKDF2WithHmacSHA256")
            .putInt("iterations", 1_000)
            .putString("salt_b64", "not base64!!!")
            .putString("hash_b64", "also bad")
            .apply()
        assertNull(SharedPreferencesHiddenPasswordStore(prefs).load())
    }

    @Test
    fun `does not store plaintext password`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test_security_plain_${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val password = "my-secret-password"
        val hasher = com.moyue.reader.domain.crypto.Pbkdf2PasswordHasher(iterations = 1_000)
        SharedPreferencesHiddenPasswordStore(prefs).save(hasher.hash(password.toCharArray()))

        val serialized = prefs.all.values.joinToString("|") { it.toString() }
        assertFalse(serialized.contains(password))
    }
}
