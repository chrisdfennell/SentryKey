package com.example.sentrykey

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zero-knowledge cloud-account crypto. Byte-for-byte compatible with the web
 * dashboard's `crypto.js` (deriveUserKeys + encryptWithKey/decryptWithKey), so a
 * vault backed up from the phone can be opened in the browser and vice-versa.
 *
 *   masterKey = PBKDF2-HMAC-SHA256(password, salt = lowercased username, 210k, 256-bit)
 *   authKey   = HMAC-SHA256(masterKey, "auth-key")        -> hex, sent to the server
 *   encKey    = HMAC-SHA256(masterKey, "encryption-key")  -> 32 bytes, NEVER leaves device
 *
 * The vault is sealed with AES-256-GCM using encKey directly (the envelope salt
 * is decorative for this path, matching the web). The server only ever stores
 * the encrypted envelope + the authKey hash; it cannot read secrets.
 */
object CloudCrypto {
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    data class UserKeys(val authKey: String, val encKey: ByteArray)

    /** Derives the server auth key (hex) and the local vault encryption key. */
    fun deriveUserKeys(username: String, password: String): UserKeys {
        val cleanUsername = username.trim().lowercase()
        val spec = PBEKeySpec(password.toCharArray(), cleanUsername.toByteArray(Charsets.UTF_8), ITERATIONS, KEY_BITS)
        val masterKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded

        val authKey = hmacSha256(masterKey, "auth-key").toHex()
        val encKey = hmacSha256(masterKey, "encryption-key")
        return UserKeys(authKey, encKey)
    }

    /** Encrypts [plaintext] with a pre-derived [encKey] into a SentryKey .skbackup envelope. */
    fun encryptWithKey(plaintext: String, encKey: ByteArray): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return JSONObject().apply {
            put("app", "SentryKey")
            put("version", 1)
            put("encrypted", true)
            put("kdf", "PBKDF2WithHmacSHA256")
            put("iterations", ITERATIONS)
            put("salt", b64(salt))
            put("iv", b64(iv))
            put("ciphertext", b64(ciphertext))
        }.toString(2)
    }

    /** Reverses [encryptWithKey]. Throws [BadPasswordException] if the key is wrong. */
    fun decryptWithKey(envelopeJson: String, encKey: ByteArray): String {
        val obj = try {
            JSONObject(envelopeJson)
        } catch (e: Exception) {
            throw BadPasswordException("Not a valid SentryKey backup.")
        }
        if (!obj.optBoolean("encrypted", false)) {
            throw BadPasswordException("Backup is not encrypted.")
        }
        try {
            val iv = unb64(obj.getString("iv"))
            val ciphertext = unb64(obj.getString("ciphertext"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw BadPasswordException("Wrong account password or corrupt backup.")
        }
    }

    // ---- Account recovery (matches web crypto.js generateRecoveryKey/deriveRecovery/wrap) ----

    data class RecoveryKeys(val wrapKey: ByteArray, val authKey: String)

    /** A readable one-time recovery key, e.g. ABCDE-FGHJK-LMNPQ-RSTUV (~100 bits). */
    fun generateRecoveryKey(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no ambiguous 0/O/1/I
        val bytes = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val sb = StringBuilder()
        for (i in bytes.indices) {
            sb.append(alphabet[(bytes[i].toInt() and 0xFF) % alphabet.length])
            if ((i + 1) % 5 == 0 && i < bytes.size - 1) sb.append('-')
        }
        return sb.toString()
    }

    fun randomSalt(): ByteArray = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }

    /** wrapKey (stays on device) + authKey (proves possession to the server). */
    fun deriveRecovery(recoveryKey: String, salt: ByteArray): RecoveryKeys {
        val normalized = recoveryKey.replace(Regex("[\\s-]"), "").uppercase()
        val spec = PBEKeySpec(normalized.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val master = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return RecoveryKeys(hmacSha256(master, "recovery-wrap"), hmacSha256(master, "recovery-auth").toHex())
    }

    /** AES-GCM wrap → base64(iv || ciphertext+tag). */
    fun wrapBytes(plain: ByteArray, wrapKey: ByteArray): String {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return b64(iv + cipher.doFinal(plain))
    }

    /** Reverses [wrapBytes]; throws if the wrap key is wrong. */
    fun unwrapBytes(blobB64: String, wrapKey: ByteArray): ByteArray {
        val combined = unb64(blobB64)
        val iv = combined.copyOfRange(0, IV_LEN)
        val ct = combined.copyOfRange(IV_LEN, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    /** Public base64 helper for callers needing to send raw bytes (e.g. the salt). */
    fun toBase64(bytes: ByteArray): String = b64(bytes)

    // ---- primitives ----

    private fun hmacSha256(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
