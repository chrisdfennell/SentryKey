package com.example.sentrykey

import android.content.Context
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private const val AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_WEAK or
    BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** Persisted app-lock preference + biometric availability. */
object AppLock {
    private const val PREFS = "sentry_key_settings"
    private const val KEY = "app_lock_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()

    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
}

/** Shows the system biometric / device-credential prompt. */
fun promptBiometric(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        override fun onAuthenticationError(code: Int, msg: CharSequence) = onError(msg.toString())
    }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock SentryKey")
        .setSubtitle("Authenticate to view your codes")
        .setAllowedAuthenticators(AUTHENTICATORS)
        .build()
    prompt.authenticate(info)
}

/** Gates [content] behind biometric auth when app lock is enabled. Re-locks on background. */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val lockEnabled = remember { AppLock.isEnabled(context) && activity != null }
    var unlocked by remember { mutableStateOf(!lockEnabled) }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && lockEnabled) unlocked = false
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    if (unlocked) {
        content()
    } else {
        LockScreen(
            onUnlock = {
                activity?.let { promptBiometric(it, onSuccess = { unlocked = true }, onError = {}) }
            }
        )
        LaunchedEffect(Unit) {
            activity?.let { promptBiometric(it, onSuccess = { unlocked = true }, onError = {}) }
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🔒", fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text("SentryKey is locked", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onUnlock,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500))
        ) {
            Text("Unlock", color = Color(0xFF07080B), fontWeight = FontWeight.Bold)
        }
    }
}

/** Settings sheet — currently just the App Lock toggle. */
@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var locked by remember { mutableStateOf(AppLock.isEnabled(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("App lock", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            "Require fingerprint, face, or screen lock to open",
                            color = Color(0xFF8F93A3),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = locked,
                        onCheckedChange = { want ->
                            if (want && !AppLock.canAuthenticate(context)) {
                                Toast.makeText(
                                    context,
                                    "Set up a fingerprint, face, or screen lock first",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                locked = want
                                AppLock.setEnabled(context, want)
                            }
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Takes effect next time you open the app.", color = Color(0xFF5A5F7A), fontSize = 11.sp)
            }
        }
    )
}
