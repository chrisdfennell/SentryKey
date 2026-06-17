package com.example.sentrykey

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Standards-based (otpauth://) export & import so accounts move freely between
 * SentryKey and any other authenticator (Google Authenticator, Authy, 1Password…).
 */
object ExportImport {

    /** Builds a standard `otpauth://totp/...` URI for one account. */
    fun accountToOtpauthUri(account: TwoFactorAccount): String {
        val issuer = account.label.substringBefore(":").substringBefore(" (").trim()
        val base = "otpauth://totp/${Uri.encode(account.label)}?secret=${Uri.encode(account.secret)}"
        return if (issuer.isNotEmpty()) "$base&issuer=${Uri.encode(issuer)}" else base
    }

    /** Serializes the vault to a pretty JSON backup (includes otpauth URIs). */
    fun accountsToJson(accounts: List<TwoFactorAccount>): String {
        val arr = JSONArray()
        for (a in accounts) {
            arr.put(JSONObject().apply {
                put("label", a.label)
                put("secret", a.secret)
                put("otpauth", accountToOtpauthUri(a))
            })
        }
        return JSONObject().apply {
            put("app", "SentryKey")
            put("version", 1)
            put("accounts", arr)
        }.toString(2)
    }

    /**
     * Serializes the vault to a passphrase-encrypted backup. The inner plaintext
     * is the same JSON as [accountsToJson]; it's sealed with AES-256-GCM under a
     * key derived from [password] via PBKDF2. Useless without the passphrase.
     */
    fun accountsToEncryptedJson(accounts: List<TwoFactorAccount>, password: String): String =
        BackupCrypto.encrypt(accountsToJson(accounts), password)

    /** True if [text] is an encrypted SentryKey backup (needs a passphrase to import). */
    fun isEncryptedBackup(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return false
        return try {
            JSONObject(trimmed).optBoolean("encrypted", false)
        } catch (e: Exception) {
            false
        }
    }

    /** Parses an imported file: either our JSON backup or a blob of otpauth URIs. */
    fun parseImport(text: String): List<TwoFactorAccount> {
        val trimmed = text.trim()
        return try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) parseJson(trimmed)
            else parseOtpauthBlob(trimmed)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Decrypts an encrypted backup (see [accountsToEncryptedJson]) with [password]
     * and parses the inner plaintext backup. Throws [BadPasswordException] if the
     * passphrase is wrong (GCM tag fails) or the envelope is malformed.
     */
    fun parseEncryptedImport(text: String, password: String): List<TwoFactorAccount> {
        val plaintext = BackupCrypto.decrypt(text.trim(), password)
        return parseImport(plaintext)
    }

    private fun parseJson(text: String): List<TwoFactorAccount> {
        val arr = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            JSONObject(text).optJSONArray("accounts") ?: JSONArray()
        }
        val out = ArrayList<TwoFactorAccount>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val secret = obj.optString("secret")
            if (secret.isNotEmpty()) {
                val label = obj.optString("label").ifEmpty { "Account" }
                out.add(TwoFactorAccount(label, secret.uppercase()))
            } else {
                val otp = obj.optString("otpauth")
                if (otp.isNotEmpty()) parseOtpauthUri(otp)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun parseOtpauthBlob(text: String): List<TwoFactorAccount> =
        text.split(Regex("\\s+"))
            .filter { it.startsWith("otpauth://", ignoreCase = true) }
            .mapNotNull { parseOtpauthUri(it) }

    /**
     * Writes a backup to the cache dir and opens the system share sheet. When
     * [password] is non-null the file is encrypted (recommended); otherwise it's
     * the plaintext JSON backup.
     */
    fun shareBackup(context: Context, accounts: List<TwoFactorAccount>, password: String? = null) {
        val (name, body) = if (password != null) {
            "sentrykey-vault.skbackup" to accountsToEncryptedJson(accounts, password)
        } else {
            "sentrykey-vault.json" to accountsToJson(accounts)
        }
        val file = File(context.cacheDir, name)
        file.writeText(body)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export SentryKey vault"))
    }

    /** Renders an otpauth URI as a scannable QR bitmap. */
    fun generateQrBitmap(content: String, size: Int = 600): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        return bmp
    }
}

/** Thrown when an encrypted backup can't be decrypted (wrong passphrase or corrupt file). */
class BadPasswordException(message: String) : Exception(message)

/**
 * Passphrase-based encryption for backup files. Key = PBKDF2WithHmacSHA256 over
 * the passphrase + a random 16-byte salt; payload = AES-256-GCM. The envelope is
 * self-describing JSON so the parameters travel with the ciphertext.
 */
object BackupCrypto {
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    fun encrypt(plaintext: String, password: String): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return JSONObject().apply {
            put("app", "SentryKey")
            put("version", 1)
            put("encrypted", true)
            put("kdf", KDF)
            put("iterations", ITERATIONS)
            put("salt", b64(salt))
            put("iv", b64(iv))
            put("ciphertext", b64(ciphertext))
        }.toString(2)
    }

    fun decrypt(envelope: String, password: String): String {
        val obj = try {
            JSONObject(envelope)
        } catch (e: Exception) {
            throw BadPasswordException("Not a valid SentryKey backup file.")
        }
        try {
            val salt = unb64(obj.getString("salt"))
            val iv = unb64(obj.getString("iv"))
            val ciphertext = unb64(obj.getString("ciphertext"))
            val iterations = obj.optInt("iterations", ITERATIONS)
            val key = deriveKey(password, salt, iterations)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: BadPasswordException) {
            throw e
        } catch (e: Exception) {
            // AEADBadTagException (wrong passphrase) and any malformed-field error land here.
            throw BadPasswordException("Wrong passphrase or corrupt backup.")
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
