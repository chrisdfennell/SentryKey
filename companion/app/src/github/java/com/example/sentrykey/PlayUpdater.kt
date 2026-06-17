package com.example.sentrykey

import androidx.activity.ComponentActivity

/**
 * GitHub (sideload) flavor: no Play In-App Updates. Updates are handled by the
 * GitHub Releases flow in [UpdateManager], so this is a no-op stub that keeps
 * the shared call site in MainActivity compiling for both flavors.
 */
object PlayUpdater {
    fun checkForUpdate(activity: ComponentActivity) { /* no-op — see UpdateManager (GitHub) */ }
}
