package com.fennell.sentrykey.wear

import android.content.Context

/** Local persistence for the vault synced from the phone (survives reboots). */
object WearVaultStore {
    private const val PREFS = "wear_vault"
    private const val KEY = "vault_string"

    fun save(context: Context, vaultString: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, vaultString).apply()
    }

    fun getAccounts(context: Context): List<WearAccount> =
        parseVaultString(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: "")
}

// "label:secret,label:secret" — split each entry on its LAST colon (labels may
// contain colons; a Base32 secret never does), mirroring the phone/watch.
// Top-level so it can be unit-tested without an Android Context.
fun parseVaultString(s: String): List<WearAccount> {
    if (s.isBlank()) return emptyList()
    val out = ArrayList<WearAccount>()
    for (raw in s.split(",")) {
        val pair = raw.trim()
        val colon = pair.lastIndexOf(':')
        if (colon <= 0) continue
        val label = pair.substring(0, colon).trim()
        val secret = pair.substring(colon + 1).trim()
        if (label.isNotEmpty() && secret.isNotEmpty()) out.add(WearAccount(label, secret))
    }
    return out
}
