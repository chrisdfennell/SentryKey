package com.example.sentrykey

import android.util.Log
import androidx.activity.ComponentActivity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Google Play In-App Updates (play flavor). Play owns the entire UX and the
 * install; this just asks Play whether an update exists and launches the flow.
 * No APK is downloaded or installed by the app, so no install permission.
 */
object PlayUpdater {
    private const val REQUEST_CODE = 4711

    fun checkForUpdate(activity: ComponentActivity) {
        val manager = AppUpdateManagerFactory.create(activity)
        manager.appUpdateInfo.addOnSuccessListener { info ->
            val availableNow = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
            // Also resume an update that Play already started but was interrupted.
            val resuming = info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
            if ((availableNow || resuming) && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                try {
                    // Deprecated overload, but still functional and far simpler than
                    // wiring an ActivityResult launcher; the IMMEDIATE flow is fully
                    // driven by Play, so we don't act on the result code ourselves.
                    @Suppress("DEPRECATION")
                    manager.startUpdateFlowForResult(info, AppUpdateType.IMMEDIATE, activity, REQUEST_CODE)
                } catch (e: Exception) {
                    Log.w("PlayUpdater", "startUpdateFlowForResult failed", e)
                }
            }
        }.addOnFailureListener { e ->
            Log.w("PlayUpdater", "appUpdateInfo failed", e)
        }
    }
}
