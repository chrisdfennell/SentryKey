package com.example.sentrykey

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Headless (Robolectric) tests for SentryKey's password-based crypto: cloud
 * zero-knowledge (CloudCrypto), BLE sync (SyncCrypto), passphrase backups
 * (ExportImport), and account recovery.
 *
 * These use android.util.Base64 (shadowed by Robolectric) + standard JCA, so they
 * run on the JVM in CI — unlike the AndroidKeyStore tests, which require a device
 * and stay in VaultCryptoInstrumentedTest.
 *
 * The pinned vectors are CROSS-PLATFORM CONTRACTS: if they drift, phone backups
 * won't open in the web dashboard and BLE sync to the watch / iOS breaks.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CloudCryptoRobolectricTest {

    // ---- Cloud zero-knowledge crypto (must match the web dashboard crypto.js) ----

    @Test
    fun cloudCrypto_keyDerivationMatchesWebVector() {
        val keys = CloudCrypto.deriveUserKeys("alice", "supersecuremasterpassword")
        assertEquals("68c6d3429e6d6b8025aa38fc281779b1cf308a90e6422888c94aceeb621bc0f4", keys.authKey)
        assertEquals(
            "3294dbf0fca8ca2c526b1397fb9bd6c1175caf5674fb5ccbf99af4956a9dbb03",
            keys.encKey.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun cloudCrypto_usernameIsCaseInsensitive() {
        val a = CloudCrypto.deriveUserKeys("Alice", "supersecuremasterpassword")
        val b = CloudCrypto.deriveUserKeys("alice", "supersecuremasterpassword")
        assertEquals(a.authKey, b.authKey)
    }

    @Test
    fun cloudCrypto_encryptDecryptRoundTrip() {
        val keys = CloudCrypto.deriveUserKeys("bob", "hunter2hunter2")
        val vault = """{"app":"SentryKey","version":1,"accounts":[{"label":"GitHub","secret":"KRSXG5CTMVRXEZLU"}]}"""
        val envelope = CloudCrypto.encryptWithKey(vault, keys.encKey)
        assertTrue(ExportImport.isEncryptedBackup(envelope))
        assertFalse(envelope.contains("KRSXG5CTMVRXEZLU"))
        assertEquals(vault, CloudCrypto.decryptWithKey(envelope, keys.encKey))
    }

    @Test(expected = BadPasswordException::class)
    fun cloudCrypto_wrongKeyThrows() {
        val a = CloudCrypto.deriveUserKeys("carol", "passwordone")
        val b = CloudCrypto.deriveUserKeys("carol", "passwordtwo")
        val envelope = CloudCrypto.encryptWithKey("{\"accounts\":[]}", a.encKey)
        CloudCrypto.decryptWithKey(envelope, b.encKey)
    }

    // ---- Recovery key derivation (must match web crypto.js) ----

    @Test
    fun recovery_derivationMatchesWebVector() {
        val salt = ByteArray(16) { it.toByte() }
        val rec = CloudCrypto.deriveRecovery("ABCDE-FGHJK-LMNPQ-RSTUV", salt)
        assertEquals(
            "a9cea55a66214d39ef29e2fefa1872272b37fce3adb862af7320f01e0a77dcbf",
            rec.wrapKey.joinToString("") { "%02x".format(it) }
        )
        assertEquals("1732ab38068ceca8ecb3b11472e503cf0c23e1841cc3f71e46a82b3c0b729a0d", rec.authKey)
    }

    @Test
    fun recovery_normalizesKeyFormatting() {
        val salt = ByteArray(16) { it.toByte() }
        val a = CloudCrypto.deriveRecovery("abcde fghjk lmnpq rstuv", salt)
        val b = CloudCrypto.deriveRecovery("ABCDE-FGHJK-LMNPQ-RSTUV", salt)
        assertEquals(a.authKey, b.authKey)
    }

    @Test
    fun recovery_wrapUnwrapRoundTrip() {
        val keys = CloudCrypto.deriveUserKeys("dave", "masterpass")
        val rec = CloudCrypto.deriveRecovery(CloudCrypto.generateRecoveryKey(), CloudCrypto.randomSalt())
        val blob = CloudCrypto.wrapBytes(keys.encKey, rec.wrapKey)
        assertEquals(
            keys.encKey.joinToString("") { "%02x".format(it) },
            CloudCrypto.unwrapBytes(blob, rec.wrapKey).joinToString("") { "%02x".format(it) }
        )
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

    // ---- Passphrase-encrypted backups (ExportImport) ----

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
}
