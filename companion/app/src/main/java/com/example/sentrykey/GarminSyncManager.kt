package com.example.sentrykey

import android.content.Context
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus
import com.garmin.android.connectiq.ConnectIQ.IQSendMessageListener
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.ServiceUnavailableException

class GarminSyncManager(private val context: Context) {
    private var connectIQ: ConnectIQ? = null
    private val appUuid = "a8d3e91b-cf52-4a87-bb4b-9800d0c85467" // SentryKey manifest ID
    private val app = IQApp(appUuid)

    // Sentinel that asks the watch to send its vault back (watch -> phone
    // recovery). MUST match PULL_REQUEST in the watch app (source/SentryKeyApp.mc)
    // and the iOS companion.
    private val pullRequest = "__SENTRYKEY_PULL__"

    interface SyncCallback {
        fun onSuccess(message: String)
        fun onError(error: String)
        fun onStatusUpdate(status: String)
    }

    interface RecoveryCallback {
        fun onVaultReceived(vaultString: String)
        fun onError(error: String)
        fun onStatusUpdate(status: String)
    }

    fun syncVault(vaultString: String, callback: SyncCallback) {
        callback.onStatusUpdate("Initializing Garmin ConnectIQ SDK...")

        try {
            // Retrieve instance for Wireless BLE connection
            connectIQ = ConnectIQ.getInstance(context, IQConnectType.WIRELESS)

            // Initialize the SDK asynchronously
            connectIQ?.initialize(context, true, object : ConnectIQListener {
                override fun onSdkReady() {
                    Log.d("SentryKeySync", "SDK initialized, searching devices...")
                    findDevicesAndSync(vaultString, callback)
                }

                override fun onSdkShutDown() {
                    callback.onError("Garmin SDK shut down unexpectedly.")
                }

                override fun onInitializeError(status: IQSdkErrorStatus) {
                    callback.onError("Garmin ConnectIQ Error: $status. Make sure Garmin Connect app is installed.")
                }
            })
        } catch (e: Exception) {
            callback.onError("Failed to initialize ConnectIQ SDK: ${e.message}")
        }
    }

    private fun findDevicesAndSync(vaultString: String, callback: SyncCallback) {
        val iq = connectIQ ?: return

        val devices: List<IQDevice>?
        try {
            devices = iq.knownDevices
        } catch (e: ServiceUnavailableException) {
            callback.onError("Garmin Connect Service unavailable. Ensure Garmin Connect app is running.")
            return
        }

        if (devices.isNullOrEmpty()) {
            callback.onError("No paired Garmin devices found. Pair your watch in Garmin Connect first.")
            return
        }

        callback.onStatusUpdate("Paired device found: ${devices[0].friendlyName}. Checking watch app...")
        val targetDevice = devices[0]

        try {
            iq.getApplicationInfo(appUuid, targetDevice, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(appInfo: IQApp) {
                    callback.onStatusUpdate("App found. Sending vault message...")
                    sendMessageToWatch(targetDevice, vaultString, callback)
                }

                override fun onApplicationNotInstalled(uuid: String) {
                    callback.onError("SentryKey watch-app is not installed. Deploy it to your watch first.")
                }
            })
        } catch (e: Exception) {
            callback.onError("Failed to query watch app: ${e.message}")
        }
    }

    // ---- Watch -> phone recovery (pull) ----

    fun requestVaultFromWatch(callback: RecoveryCallback) {
        callback.onStatusUpdate("Initializing Garmin ConnectIQ SDK...")

        try {
            connectIQ = ConnectIQ.getInstance(context, IQConnectType.WIRELESS)
            connectIQ?.initialize(context, true, object : ConnectIQListener {
                override fun onSdkReady() {
                    findDeviceAndRequest(callback)
                }

                override fun onSdkShutDown() {
                    callback.onError("Garmin SDK shut down unexpectedly.")
                }

                override fun onInitializeError(status: IQSdkErrorStatus) {
                    callback.onError("Garmin ConnectIQ Error: $status. Make sure Garmin Connect app is installed.")
                }
            })
        } catch (e: Exception) {
            callback.onError("Failed to initialize ConnectIQ SDK: ${e.message}")
        }
    }

    private fun findDeviceAndRequest(callback: RecoveryCallback) {
        val iq = connectIQ ?: return

        val devices: List<IQDevice>?
        try {
            devices = iq.knownDevices
        } catch (e: ServiceUnavailableException) {
            callback.onError("Garmin Connect Service unavailable. Ensure Garmin Connect app is running.")
            return
        }

        if (devices.isNullOrEmpty()) {
            callback.onError("No paired Garmin devices found. Pair your watch in Garmin Connect first.")
            return
        }

        val targetDevice = devices[0]
        callback.onStatusUpdate("Paired device found: ${targetDevice.friendlyName}. Checking watch app...")

        try {
            iq.getApplicationInfo(appUuid, targetDevice, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(appInfo: IQApp) {
                    listenAndRequest(targetDevice, callback)
                }

                override fun onApplicationNotInstalled(uuid: String) {
                    callback.onError("SentryKey watch-app is not installed. Deploy it to your watch first.")
                }
            })
        } catch (e: Exception) {
            callback.onError("Failed to query watch app: ${e.message}")
        }
    }

    private fun listenAndRequest(device: IQDevice, callback: RecoveryCallback) {
        val iq = connectIQ ?: return

        // Register for the watch's reply BEFORE asking, so we don't miss it.
        try {
            iq.registerForAppEvents(device, app, object : ConnectIQ.IQApplicationEventListener {
                override fun onMessageReceived(
                    dev: IQDevice,
                    ap: IQApp,
                    message: MutableList<Any>,
                    status: IQMessageStatus
                ) {
                    try { iq.unregisterForApplicationEvents(device, app) } catch (_: Exception) {}
                    val vaultString = message.firstOrNull()?.toString() ?: ""
                    callback.onVaultReceived(vaultString)
                }
            })
        } catch (e: Exception) {
            callback.onError("Failed to listen for watch reply: ${e.message}")
            return
        }

        callback.onStatusUpdate("Confirm the recovery prompt on your watch…")

        try {
            iq.sendMessage(device, app, pullRequest, object : IQSendMessageListener {
                override fun onMessageStatus(dev: IQDevice, ap: IQApp, status: IQMessageStatus) {
                    if (status != IQMessageStatus.SUCCESS) {
                        try { iq.unregisterForApplicationEvents(device, app) } catch (_: Exception) {}
                        callback.onError("Couldn't reach watch: $status")
                    }
                }
            })
        } catch (e: Exception) {
            try { iq.unregisterForApplicationEvents(device, app) } catch (_: Exception) {}
            callback.onError("Failed to send recovery request: ${e.message}")
        }
    }

    private fun sendMessageToWatch(device: IQDevice, vaultString: String, callback: SyncCallback) {
        val iq = connectIQ ?: return

        try {
            iq.sendMessage(device, app, vaultString, object : IQSendMessageListener {
                override fun onMessageStatus(dev: IQDevice, ap: IQApp, status: IQMessageStatus) {
                    if (status == IQMessageStatus.SUCCESS) {
                        callback.onSuccess("Vault successfully synced to ${dev.friendlyName}!")
                    } else {
                        callback.onError("Sync failed with status: $status")
                    }
                }
            })
        } catch (e: Exception) {
            callback.onError("Failed to send message: ${e.message}")
        }
    }
}
