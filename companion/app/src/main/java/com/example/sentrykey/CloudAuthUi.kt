package com.example.sentrykey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val orange = Color(0xFFFFA500)
private val bg = Color(0xFF07080B)
private val card = Color(0xFF10121A)
private val chipBorder = Color(0xFF222533)
private val mutedText = Color(0xFF8F93A3)

/** Shared connect routine used by both the startup gate and the cloud dialog. */
object CloudAuth {
    /**
     * Derives keys, registers if [isRegister], logs in, and persists the session
     * (including the encKey, so the user stays signed in). CPU + network heavy —
     * call from a background dispatcher. Throws on failure.
     */
    suspend fun connect(
        vaultStorage: VaultStorage,
        url: String,
        username: String,
        password: String,
        isRegister: Boolean
    ) {
        val keys = CloudCrypto.deriveUserKeys(username, password)
        if (isRegister) CloudBackupClient.register(url, username, keys.authKey)
        val token = CloudBackupClient.login(url, username, keys.authKey)
        vaultStorage.setCloudServerUrl(url)
        vaultStorage.setCloudUsername(username)
        vaultStorage.setCloudToken(token)
        vaultStorage.setCloudEncKey(keys.encKey)
        vaultStorage.setCloudGateDismissed(false)
    }
}

/**
 * Startup gate: if the user isn't signed into the cloud (and hasn't chosen to go
 * offline), show a login screen first so the app opens already connected. Sits
 * INSIDE AppLockGate, so biometrics (when enabled) come first.
 */
@Composable
fun CloudAuthGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val vaultStorage = remember { VaultStorage(context) }
    var signedIn by remember { mutableStateOf(vaultStorage.isCloudSignedIn()) }
    var dismissed by remember { mutableStateOf(vaultStorage.isCloudGateDismissed()) }

    if (signedIn || dismissed) {
        content()
    } else {
        CloudAuthScreen(
            vaultStorage = vaultStorage,
            onSignedIn = { signedIn = true },
            onUseOffline = {
                vaultStorage.setCloudGateDismissed(true)
                dismissed = true
            }
        )
    }
}

@Composable
private fun CloudAuthScreen(
    vaultStorage: VaultStorage,
    onSignedIn: () -> Unit,
    onUseOffline: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(vaultStorage.getCloudServerUrl()) }
    var username by remember { mutableStateOf(vaultStorage.getCloudUsername()) }
    var password by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recovering by remember { mutableStateOf(false) }

    if (recovering) {
        RecoveryScreen(
            vaultStorage = vaultStorage,
            onRecovered = onSignedIn,
            onBack = { recovering = false }
        )
        return
    }

    fun submit() {
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            error = "Enter server, username, and master password."
            return
        }
        busy = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    CloudAuth.connect(vaultStorage, serverUrl, username, password, isRegister)
                }
                onSignedIn()
            } catch (e: Exception) {
                error = e.message ?: "Sign in failed."
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🛡️", fontSize = 44.sp)
        Spacer(Modifier.height(8.dp))
        Text("SentryKey", color = orange, fontWeight = FontWeight.Black, fontSize = 24.sp)
        Text(
            if (isRegister) "Create your encrypted cloud account" else "Sign in to sync your vault",
            color = mutedText, fontSize = 13.sp
        )
        Spacer(Modifier.height(20.dp))

        AuthField("Server URL", serverUrl, enabled = !busy) { serverUrl = it }
        Spacer(Modifier.height(10.dp))
        AuthField("Username", username, enabled = !busy) { username = it }
        Spacer(Modifier.height(10.dp))
        AuthField("Master password", password, isPassword = true, enabled = !busy) { password = it }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Color(0xFFEF4444), fontSize = 12.sp)
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { submit() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = orange),
            shape = RoundedCornerShape(10.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp), color = bg, strokeWidth = 2.dp)
            else Text(if (isRegister) "Create account" else "Sign in", color = bg, fontWeight = FontWeight.Bold)
        }

        TextButton(onClick = { isRegister = !isRegister }, enabled = !busy) {
            Text(
                if (isRegister) "Have an account? Sign in" else "New here? Create an account",
                color = orange, fontSize = 13.sp
            )
        }

        if (!isRegister) {
            TextButton(onClick = { recovering = true }, enabled = !busy) {
                Text("Forgot your master password?", color = mutedText, fontSize = 13.sp)
            }
        }

        TextButton(onClick = onUseOffline, enabled = !busy) {
            Text("Use without cloud", color = mutedText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AuthField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = mutedText, fontSize = 12.sp) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = card,
            unfocusedContainerColor = card,
            focusedBorderColor = orange,
            unfocusedBorderColor = chipBorder,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = mutedText,
            disabledBorderColor = chipBorder
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
