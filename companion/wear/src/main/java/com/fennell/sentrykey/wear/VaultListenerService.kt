package com.fennell.sentrykey.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Receives the vault string the phone pushes over the Wearable Data Layer. */
class VaultListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == VAULT_PATH) {
            WearVaultStore.save(applicationContext, String(event.data, Charsets.UTF_8))
        }
    }

    companion object {
        const val VAULT_PATH = "/sentrykey/vault"
    }
}
