package com.example.sentrykey

import android.content.Context
import com.google.android.gms.wearable.Wearable

/**
 * Pushes the vault to connected Wear OS watches over the Wearable Data Layer
 * (the SentryKey Wear app shares this app's applicationId, so messages route to
 * it). Best-effort and fire-and-forget — no paired watch is the normal case.
 *
 * Sends the plaintext "label:secret,…" string; the Data Layer channel is itself
 * encrypted by Google's transport between the paired phone and watch.
 */
object WearSyncManager {
    private const val VAULT_PATH = "/sentrykey/vault"

    fun push(context: Context, vaultString: String) {
        try {
            val messageClient = Wearable.getMessageClient(context)
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
                val data = vaultString.toByteArray(Charsets.UTF_8)
                for (node in nodes) {
                    messageClient.sendMessage(node.id, VAULT_PATH, data)
                }
            }
        } catch (e: Exception) {
            // No Google Play services / no paired watch — ignore.
        }
    }
}
