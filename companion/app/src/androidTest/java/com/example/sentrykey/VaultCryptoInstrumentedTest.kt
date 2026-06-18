package com.example.sentrykey

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator tests for the parts that need the real Android Keystore
 * (CryptoManager + VaultStorage's at-rest encryption). The AndroidKeyStore
 * provider isn't available under Robolectric, so these must run instrumented:
 *
 *   ./gradlew connectedDebugAndroidTest
 *
 * The password-based crypto (CloudCrypto / SyncCrypto / backups / recovery) and
 * its cross-platform pinned vectors moved to CloudCryptoRobolectricTest, which
 * runs headlessly in CI.
 */
@RunWith(AndroidJUnit4::class)
class VaultCryptoInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ---- Keystore at-rest crypto ----

    @Test
    fun cryptoManager_roundTrips() {
        val plain = "Discord:JBSWY3DPEHPK3PXP,GitHub:KRSXG5CTMVRXEZLU"
        val cipher = CryptoManager.encrypt(plain)
        assertNotEquals("ciphertext must differ from plaintext", plain, cipher)
        assertEquals(plain, CryptoManager.decrypt(cipher))
    }

    @Test
    fun cryptoManager_usesFreshIvPerCall() {
        val plain = "same-input"
        // Random IV per call => identical plaintext encrypts to different blobs.
        assertNotEquals(CryptoManager.encrypt(plain), CryptoManager.encrypt(plain))
    }

    // ---- VaultStorage encrypted persistence + legacy migration ----

    @Test
    fun vaultStorage_persistsEncryptedAndReadsBack() {
        clearPrefs()
        val storage = VaultStorage(ctx)
        val accounts = listOf(
            TwoFactorAccount("Discord:user", "JBSWY3DPEHPK3PXP"),
            TwoFactorAccount("GitHub", "KRSXG5CTMVRXEZLU")
        )
        storage.saveAccounts(accounts)

        // Raw prefs must NOT contain the secret in the clear, and the legacy key is gone.
        val raw = prefs().all.toString()
        assertFalse("secret leaked to plaintext prefs", raw.contains("JBSWY3DPEHPK3PXP"))
        assertNull("legacy plaintext key must be cleared", prefs().getString("accounts", null))

        val reloaded = VaultStorage(ctx).getAccounts()
        assertEquals(accounts, reloaded)
    }

    @Test
    fun vaultStorage_migratesLegacyPlaintext() {
        clearPrefs()
        // Simulate a pre-encryption install: legacy plaintext "accounts" JSON.
        prefs().edit()
            .putString("accounts", """[{"label":"Legacy","secret":"GEZDGNBVGY3TQOJQ"}]""")
            .apply()

        val storage = VaultStorage(ctx)
        val migrated = storage.getAccounts()
        assertEquals(1, migrated.size)
        assertEquals("GEZDGNBVGY3TQOJQ", migrated[0].secret)

        // A save re-encrypts and drops the legacy plaintext.
        storage.saveAccounts(migrated)
        assertNull(prefs().getString("accounts", null))
        assertFalse(prefs().all.toString().contains("GEZDGNBVGY3TQOJQ"))
        assertEquals(migrated, VaultStorage(ctx).getAccounts())
    }

    // ---- helpers ----

    private fun prefs() = ctx.getSharedPreferences("sentry_key_vault", android.content.Context.MODE_PRIVATE)
    private fun clearPrefs() = prefs().edit().clear().commit()
    private fun assertNull(message: String, o: Any?) = assertTrue(message, o == null)
    private fun assertNull(o: Any?) = assertTrue(o == null)
}
