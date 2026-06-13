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
}
