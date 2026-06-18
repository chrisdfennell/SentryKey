package com.example.sentrykey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val rOrange = Color(0xFFFFA500)
private val rBg = Color(0xFF07080B)
private val rCard = Color(0xFF10121A)
private val rBorder = Color(0xFF222533)
private val rMuted = Color(0xFF8F93A3)

/**
 * In-dialog "Set up account recovery": generate a one-time recovery key, wrap the
 * encryption key under it, and enroll with the server. Optional email/phone are
 * stored for a future OTP second factor.
 */
@Composable
fun RecoverySetupSection(
    vaultStorage: VaultStorage,
    encKey: ByteArray,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val recoveryKey = remember { CloudCrypto.generateRecoveryKey() }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🛟 Account recovery", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            "Zero-knowledge means no one can reset your master password. Save this recovery key — it's your only way back in.",
            color = rMuted, fontSize = 12.sp
        )
        Text(
            recoveryKey,
            color = rOrange,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(rCard, RoundedCornerShape(10.dp))
                .padding(14.dp)
        )
        OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(recoveryKey))
            Toast.makeText(context, "Recovery key copied", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("📋 Copy recovery key", color = rOrange) }

        RecoveryField("Email (optional, for a code 2nd factor later)", email, enabled = !busy) { email = it }
        RecoveryField("Phone (optional, e.g. +15551234567)", phone, enabled = !busy) { phone = it }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = saved, onCheckedChange = { saved = it })
            Text("I've saved my recovery key somewhere safe.", color = rMuted, fontSize = 12.sp)
        }

        Button(
            onClick = {
                busy = true; status = null
                scope.launch {
                    try {
                        val salt = CloudCrypto.randomSalt()
                        val rec = withContext(Dispatchers.Default) { CloudCrypto.deriveRecovery(recoveryKey, salt) }
                        val blob = CloudCrypto.wrapBytes(encKey, rec.wrapKey)
                        CloudBackupClient.setupRecovery(
                            vaultStorage.getCloudServerUrl(), vaultStorage.getCloudToken(),
                            CloudCrypto.toBase64(salt), blob, rec.authKey, email.trim(), phone.trim()
                        )
                        status = "Recovery enabled."
                        onDone()
                    } catch (e: Exception) {
                        status = e.message ?: "Couldn't enable recovery."
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = saved && !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = rOrange),
            shape = RoundedCornerShape(8.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(16.dp), color = rBg, strokeWidth = 2.dp)
            else Text("Enable recovery", color = rBg, fontWeight = FontWeight.Bold)
        }
        status?.let { Text(it, color = if (it == "Recovery enabled.") Color(0xFF22C55E) else Color(0xFFEF4444), fontSize = 12.sp) }
    }
}

/**
 * Full-screen "Forgot master password?" recovery: prove the recovery key, unlock
 * the vault, set a new master password. Mirrors the web login recovery flow.
 */
@Composable
fun RecoveryScreen(
    vaultStorage: VaultStorage,
    onRecovered: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(vaultStorage.getCloudServerUrl()) }
    var username by remember { mutableStateOf(vaultStorage.getCloudUsername()) }
    var recoveryKey by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var newPass2 by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var otpRequired by remember { mutableStateOf(false) }
    var otpStarted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun recover() {
        if (username.isBlank()) { error = "Enter your username."; return }
        busy = true; error = null
        scope.launch {
            try {
                // 1. Start — sends an OTP if a channel is configured.
                if (!otpStarted) {
                    val start = CloudBackupClient.recoveryStart(serverUrl, username)
                    otpStarted = true
                    otpRequired = start.otpRequired
                    if (otpRequired) {
                        error = "Code sent${if (start.sentTo.isNotEmpty()) " to ${start.sentTo.joinToString()}" else ""}. Enter it, then tap Recover."
                        busy = false
                        return@launch
                    }
                }
                if (recoveryKey.isBlank()) { error = "Enter your recovery key."; busy = false; return@launch }
                if (newPass.length < 8) { error = "New password must be at least 8 characters."; busy = false; return@launch }
                if (newPass != newPass2) { error = "Passwords don't match."; busy = false; return@launch }
                val otpVal = if (otpRequired) otp.trim() else null

                // 2. Fetch wrapped material + vault.
                val mat = CloudBackupClient.recoveryFetch(serverUrl, username, otpVal)

                // 3-5. Unwrap, decrypt, re-derive, re-encrypt, re-wrap (CPU work off-main).
                val result = withContext(Dispatchers.Default) {
                    val rec = CloudCrypto.deriveRecovery(recoveryKey, Base64Decode(mat.salt))
                    val oldEncKey = try {
                        CloudCrypto.unwrapBytes(mat.blob, rec.wrapKey)
                    } catch (e: Exception) {
                        throw BadPasswordException("Invalid recovery key.")
                    }
                    val vaultPlain = if (mat.vault != null)
                        CloudCrypto.decryptWithKey(mat.vault, oldEncKey)
                    else """{"app":"SentryKey","version":1,"accounts":[]}"""
                    val nk = CloudCrypto.deriveUserKeys(username, newPass)
                    val newVaultEnv = CloudCrypto.encryptWithKey(vaultPlain, nk.encKey)
                    val newSalt = CloudCrypto.randomSalt()
                    val newRec = CloudCrypto.deriveRecovery(recoveryKey, newSalt)
                    val newBlob = CloudCrypto.wrapBytes(nk.encKey, newRec.wrapKey)
                    RecoveryComputation(rec.authKey, nk, CloudCrypto.toBase64(newSalt), newBlob, newRec.authKey, newVaultEnv, vaultPlain)
                }

                // 6. Reset on the server.
                val token = CloudBackupClient.recoveryReset(
                    serverUrl, username, result.recoveryAuthKey, otpVal,
                    result.newKeys.authKey, result.newRecSalt, result.newRecBlob, result.newRecAuthKey, result.newVaultEnv
                )

                // 7. Persist the new session + restore the vault locally.
                vaultStorage.setCloudServerUrl(serverUrl)
                vaultStorage.setCloudUsername(username)
                vaultStorage.setCloudToken(token)
                vaultStorage.setCloudEncKey(result.newKeys.encKey)
                vaultStorage.setCloudGateDismissed(false)
                ExportImport.parseImport(result.vaultPlain).takeIf { it.isNotEmpty() }?.let { imported ->
                    val merged = vaultStorage.getAccounts() + imported.filter { new ->
                        vaultStorage.getAccounts().none { it.label == new.label && it.secret == new.secret }
                    }
                    vaultStorage.saveAccounts(merged)
                }
                onRecovered()
            } catch (e: BadPasswordException) {
                error = "Invalid recovery key."; busy = false
            } catch (e: Exception) {
                error = e.message ?: "Recovery failed."; busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(rBg).verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🛟", fontSize = 40.sp)
        Spacer(Modifier.height(6.dp))
        Text("Recover account", color = rOrange, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Text("Use the recovery key you saved.", color = rMuted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))

        RecoveryField("Server URL", serverUrl, enabled = !busy) { serverUrl = it }
        Spacer(Modifier.height(8.dp))
        RecoveryField("Username", username, enabled = !busy) { username = it }
        if (otpRequired) {
            Spacer(Modifier.height(8.dp))
            RecoveryField("Verification code", otp, enabled = !busy) { otp = it }
        }
        Spacer(Modifier.height(8.dp))
        RecoveryField("Recovery key", recoveryKey, enabled = !busy) { recoveryKey = it }
        Spacer(Modifier.height(8.dp))
        RecoveryField("New master password", newPass, isPassword = true, enabled = !busy) { newPass = it }
        Spacer(Modifier.height(8.dp))
        RecoveryField("Confirm new password", newPass2, isPassword = true, enabled = !busy) { newPass2 = it }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Color(0xFFEF4444), fontSize = 12.sp)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { recover() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = rOrange),
            shape = RoundedCornerShape(10.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp), color = rBg, strokeWidth = 2.dp)
            else Text("Recover", color = rBg, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onBack, enabled = !busy) { Text("Back to sign in", color = rMuted, fontSize = 13.sp) }
    }
}

private data class RecoveryComputation(
    val recoveryAuthKey: String,
    val newKeys: CloudCrypto.UserKeys,
    val newRecSalt: String,
    val newRecBlob: String,
    val newRecAuthKey: String,
    val newVaultEnv: String,
    val vaultPlain: String
)

private fun Base64Decode(b64: String): ByteArray =
    android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)

@Composable
private fun RecoveryField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = rMuted, fontSize = 12.sp) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = rCard,
            unfocusedContainerColor = rCard,
            focusedBorderColor = rOrange,
            unfocusedBorderColor = rBorder,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = rMuted,
            disabledBorderColor = rBorder
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
