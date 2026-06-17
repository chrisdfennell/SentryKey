package com.example.sentrykey

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic background cloud backup. Catches changes that didn't get synced live
 * (e.g. the app was offline). No-ops when signed out or when the vault hasn't
 * changed since the last upload (AutoSyncManager hashes the vault).
 *
 * Watch push is intentionally NOT attempted here — the Connect IQ BLE bridge
 * isn't reliable from a background worker; that stays a foreground, on-change action.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val vault = VaultStorage(applicationContext)
            if (!vault.isCloudSignedIn()) return Result.success()
            // GarminSyncManager is constructed but never used here (cloud-only path).
            val auto = AutoSyncManager(vault, GarminSyncManager(applicationContext))
            auto.backupToCloud(vault.getAccounts())
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
