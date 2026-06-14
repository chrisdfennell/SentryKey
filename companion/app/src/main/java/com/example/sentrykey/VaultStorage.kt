package com.example.sentrykey

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class TwoFactorAccount(val label: String, val secret: String)

class VaultStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sentry_key_vault", Context.MODE_PRIVATE)

    fun getAccounts(): List<TwoFactorAccount> {
        val jsonStr = prefs.getString("accounts", "[]") ?: "[]"
        val accounts = ArrayList<TwoFactorAccount>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                accounts.add(TwoFactorAccount(obj.getString("label"), obj.getString("secret")))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return accounts
    }

    fun saveAccounts(accounts: List<TwoFactorAccount>) {
        val arr = JSONArray()
        for (acc in accounts) {
            val obj = JSONObject()
            obj.put("label", acc.label)
            obj.put("secret", acc.secret)
            arr.put(obj)
        }
        prefs.edit().putString("accounts", arr.toString()).apply()
    }

    fun toVaultString(accounts: List<TwoFactorAccount>): String {
        return accounts.joinToString(",") { "${it.label}:${it.secret}" }
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
