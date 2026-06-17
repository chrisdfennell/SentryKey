package com.example.sentrykey

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class TwoFactorAccount(val label: String, val secret: String)

class VaultStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sentry_key_vault", Context.MODE_PRIVATE)

    // Encrypted-at-rest key. The legacy plaintext key ("accounts") is read once
    // for migration, then cleared the next time the vault is saved.
    private val encKey = "accounts_enc"
    private val legacyKey = "accounts"

    fun getAccounts(): List<TwoFactorAccount> {
        val plaintextJson = readDecryptedJson() ?: return emptyList()
        val accounts = ArrayList<TwoFactorAccount>()
        try {
            val arr = JSONArray(plaintextJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                accounts.add(TwoFactorAccount(obj.getString("label"), obj.getString("secret")))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return accounts
    }

    // Returns the accounts JSON, decrypting the encrypted blob if present, else
    // falling back to the legacy plaintext entry (pre-encryption installs).
    private fun readDecryptedJson(): String? {
        prefs.getString(encKey, null)?.let { blob ->
            return try {
                CryptoManager.decrypt(blob)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
        return prefs.getString(legacyKey, null)
    }

    fun saveAccounts(accounts: List<TwoFactorAccount>) {
        val arr = JSONArray()
        for (acc in accounts) {
            val obj = JSONObject()
            obj.put("label", acc.label)
            obj.put("secret", acc.secret)
            arr.put(obj)
        }
        val encrypted = CryptoManager.encrypt(arr.toString())
        // Write the encrypted blob and drop any legacy plaintext in one commit.
        prefs.edit()
            .putString(encKey, encrypted)
            .remove(legacyKey)
            .apply()
    }

    fun toVaultString(accounts: List<TwoFactorAccount>): String {
        return accounts.joinToString(",") { "${it.label}:${it.secret}" }
    }

    // ---- Optional BLE sync passphrase (Keystore-encrypted, like the vault) ----

    private val syncPassKey = "sync_pass_enc"

    /** The configured sync passphrase, or "" if none. Must match the watch setting. */
    fun getSyncPassphrase(): String {
        val blob = prefs.getString(syncPassKey, null) ?: return ""
        return try {
            CryptoManager.decrypt(blob)
        } catch (e: Exception) {
            ""
        }
    }

    fun setSyncPassphrase(passphrase: String) {
        val edit = prefs.edit()
        if (passphrase.isEmpty()) {
            edit.remove(syncPassKey)
        } else {
            edit.putString(syncPassKey, CryptoManager.encrypt(passphrase))
        }
        edit.apply()
    }

    // Parses the watch vault string "label:secret,label:secret". Each entry is
    // split on its LAST colon (labels may contain colons; a Base32 secret never
    // does), mirroring the watch's parseVaultString. Used by watch -> phone
    // recovery.
    fun fromVaultString(vaultString: String): List<TwoFactorAccount> {
        val out = ArrayList<TwoFactorAccount>()
        if (vaultString.isBlank()) return out
        for (rawPair in vaultString.split(",")) {
            val pair = rawPair.trim()
            val colon = pair.lastIndexOf(':')
            if (colon <= 0) continue
            val label = pair.substring(0, colon).trim()
            val secret = pair.substring(colon + 1).trim()
            if (label.isNotEmpty() && secret.isNotEmpty()) {
                out.add(TwoFactorAccount(label, secret))
            }
        }
        return out
    }
}
