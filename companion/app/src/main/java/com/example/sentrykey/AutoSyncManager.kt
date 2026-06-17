package com.example.sentrykey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Makes sync invisible: whenever the vault changes, this debounces briefly and
 * then (a) silently encrypts + uploads to the cloud if signed in, and (b) pushes
 * to the watch if one is reachable. No manual "Sync"/"Back up" taps needed.
 *
 * Cloud uploads are skipped when the vault hasn't actually changed (hash match),
 * so repeated triggers / background runs don't spam the server.
 */
class AutoSyncManager(
    private val vaultStorage: VaultStorage,
    private val syncManager: GarminSyncManager,
    private val onStatus: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounce: Job? = null
    private val debounceMs = 1500L

    /** Call on every vault mutation. Debounced so rapid edits coalesce into one sync. */
    fun onVaultChanged(accounts: List<TwoFactorAccount>) {
        debounce?.cancel()
        debounce = scope.launch {
            delay(debounceMs)
            backupToCloud(accounts)
            pushToWatch(accounts)
        }
    }

    /**
     * Encrypts + uploads the vault if signed in and it changed (or [force]).
     * Returns true if an upload happened. Auto signs-out on an auth failure.
     */
    suspend fun backupToCloud(accounts: List<TwoFactorAccount>, force: Boolean = false): Boolean {
        if (!vaultStorage.isCloudSignedIn()) return false
        val encKey = vaultStorage.getCloudEncKey() ?: return false
        val token = vaultStorage.getCloudToken()
        val url = vaultStorage.getCloudServerUrl()

        val json = ExportImport.accountsToJson(accounts)
        val hash = sha256(json)
        if (!force && hash == vaultStorage.getLastSyncHash()) return false

        return try {
            val envelope = CloudCrypto.encryptWithKey(json, encKey)
            CloudBackupClient.uploadBackup(url, token, envelope)
            vaultStorage.setLastSyncHash(hash)
            onStatus("Backed up to cloud")
            true
        } catch (e: CloudException) {
            val msg = e.message ?: ""
            if (msg.contains("Unauthorized", true) || msg.contains("session", true)) {
                // Token expired/invalid — drop the session so the UI re-prompts.
                vaultStorage.clearCloudSession()
                onStatus("Cloud session expired — sign in again to resume backups")
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /** Best-effort push to the watch (silent in auto mode; encrypted if a sync passphrase is set). */
    private fun pushToWatch(accounts: List<TwoFactorAccount>) {
        if (accounts.isEmpty()) return
        val raw = vaultStorage.toVaultString(accounts)
        val pass = vaultStorage.getSyncPassphrase()
        val payload = if (pass.isNotEmpty()) SyncCrypto.encrypt(raw, pass) else raw
        try {
            syncManager.syncVault(payload, object : GarminSyncManager.SyncCallback {
                override fun onSuccess(message: String) { onStatus("Synced to watch") }
                override fun onError(error: String) { /* silent: no watch nearby is normal */ }
                override fun onStatusUpdate(status: String) {}
            })
        } catch (e: Exception) {
            // ignore — auto mode is best-effort
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
