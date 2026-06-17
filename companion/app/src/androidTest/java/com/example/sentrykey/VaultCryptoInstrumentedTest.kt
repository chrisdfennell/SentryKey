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
 * Runtime verification of the encryption work. Needs a device/emulator because
 * it exercises the Android Keystore (CryptoManager) and android.util.Base64.
 *
 * Run with: ./gradlew connectedDebugAndroidTest
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

    // ---- Passphrase-encrypted backups ----

    @Test
    fun backup_encryptDecryptRoundTrip() {
        val accounts = listOf(TwoFactorAccount("GitHub", "KRSXG5CTMVRXEZLU"))
        val envelope = ExportImport.accountsToEncryptedJson(accounts, "correct horse battery")
        assertTrue(ExportImport.isEncryptedBackup(envelope))
        assertFalse("backup must not contain the raw secret", envelope.contains("KRSXG5CTMVRXEZLU"))

        val restored = ExportImport.parseEncryptedImport(envelope, "correct horse battery")
        assertEquals(accounts, restored)
    }

    @Test(expected = BadPasswordException::class)
    fun backup_wrongPassphraseThrows() {
        val envelope = ExportImport.accountsToEncryptedJson(
            listOf(TwoFactorAccount("GitHub", "KRSXG5CTMVRXEZLU")), "right-pass"
        )
        ExportImport.parseEncryptedImport(envelope, "wrong-pass")
    }

    @Test
    fun isEncryptedBackup_falseForPlaintextBackup() {
        val plain = ExportImport.accountsToJson(listOf(TwoFactorAccount("X", "AAAA")))
        assertFalse(ExportImport.isEncryptedBackup(plain))
    }

    // ---- BLE sync crypto (cross-platform contract) ----

    // Canonical vector computed independently (.NET PBKDF2/HMAC). The watch
    // (Monkey C) and iOS (Swift) implementations MUST reproduce this exact wire
    // string for the same inputs, or cross-platform sync will silently fail.
    private val vecPass = "test-pass"
    private val vecPlain = "GitHub:JBSWY3DPEHPK3PXP"
    private val vecSalt = ByteArray(16) { it.toByte() }
    private val vecNonce = byteArrayOf(
        0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(),
        0xA4.toByte(), 0xA5.toByte(), 0xA6.toByte(), 0xA7.toByte()
    )
    private val vecWire =
        "SKENC1:AAECAwQFBgcICQoLDA0OD6ChoqOkpaanAMqKldiMz3e/git9BdiyOnVodURmahH/V2HnuJdkGraUkLOzv6HzilcSlw=="

    @Test
    fun syncCrypto_matchesCanonicalVector() {
        assertEquals(vecWire, SyncCrypto.encryptWith(vecPlain, vecPass, vecSalt, vecNonce))
    }

    @Test
    fun syncCrypto_decryptsCanonicalVector() {
        assertEquals(vecPlain, SyncCrypto.decrypt(vecWire, vecPass))
    }

    @Test
    fun syncCrypto_roundTripsRandom() {
        val plain = "Discord:user:JBSWY3DPEHPK3PXP,GitHub:KRSXG5CTMVRXEZLU"
        val wire = SyncCrypto.encrypt(plain, "hunter2pass")
        assertTrue(SyncCrypto.isEncrypted(wire))
        assertFalse("payload must not leak plaintext", wire.contains("JBSWY3DPEHPK3PXP"))
        assertEquals(plain, SyncCrypto.decrypt(wire, "hunter2pass"))
    }

    @Test(expected = BadPasswordException::class)
    fun syncCrypto_wrongPassphraseThrows() {
        SyncCrypto.decrypt(SyncCrypto.encrypt("Acct:AAAA", "right"), "wrong")
    }

    @Test
    fun syncCrypto_isEncryptedFalseForPlaintextSyncString() {
        assertFalse(SyncCrypto.isEncrypted("GitHub:JBSWY3DPEHPK3PXP,Discord:KRSXG5CTMVRXEZLU"))
    }

    // ---- helpers ----

    private fun prefs() = ctx.getSharedPreferences("sentry_key_vault", android.content.Context.MODE_PRIVATE)
    private fun clearPrefs() = prefs().edit().clear().commit()
    private fun assertNull(message: String, o: Any?) = assertTrue(message, o == null)
    private fun assertNull(o: Any?) = assertTrue(o == null)
}
