package com.example.sentrykey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val brandOrange = Color(0xFFFFA500)
private val bgCard = Color(0xFF10121A)
private val chip = Color(0xFF222533)
private val muted = Color(0xFF8F93A3)

/**
 * Zero-knowledge cloud backup dialog. Derives the account keys locally, logs in
 * (or registers), then encrypts the vault with the local encKey before upload —
 * the server only ever sees the auth hash and the encrypted envelope.
 */
@Composable
fun CloudBackupDialog(
    accounts: List<TwoFactorAccount>,
    vaultStorage: VaultStorage,
    onImported: (List<TwoFactorAccount>) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(vaultStorage.getCloudServerUrl()) }
    var username by remember { mutableStateOf(vaultStorage.getCloudUsername()) }
    var password by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var showRecoverySetup by remember { mutableStateOf(false) }

    // Initialize from the persisted (stay-signed-in) session, if any.
    var token by remember { mutableStateOf(vaultStorage.getCloudToken()) }
    var encKey by remember { mutableStateOf(vaultStorage.getCloudEncKey()) }
    var backups by remember { mutableStateOf<List<CloudBackupClient.BackupMeta>>(emptyList()) }
    val connected = encKey != null && token.isNotEmpty()

    fun setStatus(msg: String, isError: Boolean) {
        status = msg; statusError = isError
    }

    // If already signed in when the dialog opens, load the backup list.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (connected) {
            try { backups = CloudBackupClient.listBackups(serverUrl, token) } catch (_: Exception) {}
        }
    }

    fun signOut() {
        vaultStorage.clearCloudSession()
        token = ""; encKey = null; backups = emptyList(); password = ""
        setStatus("Signed out.", false)
    }

    fun connect() {
        if (serverUrl.isBlank()) { setStatus("Enter the server URL first.", true); return }
        if (username.isBlank() || password.isBlank()) { setStatus("Username and master password are required.", true); return }
        busy = true; setStatus("Connecting…", false)
        scope.launch {
            try {
                val keys = withContext(Dispatchers.Default) { CloudCrypto.deriveUserKeys(username, password) }
                if (isRegister) {
                    CloudBackupClient.register(serverUrl, username, keys.authKey)
                }
                val tok = CloudBackupClient.login(serverUrl, username, keys.authKey)
                token = tok
                encKey = keys.encKey
                vaultStorage.setCloudServerUrl(serverUrl)
                vaultStorage.setCloudUsername(username)
                vaultStorage.setCloudToken(tok)
                vaultStorage.setCloudEncKey(keys.encKey)   // stay signed in for silent auto-backup
                backups = CloudBackupClient.listBackups(serverUrl, tok)
                setStatus("Connected — auto-backup is now on.", false)
            } catch (e: Exception) {
                setStatus(e.message ?: "Connection failed.", true)
            } finally {
                busy = false
            }
        }
    }

    fun backUp() {
        val key = encKey ?: return
        if (accounts.isEmpty()) { setStatus("Vault is empty — nothing to back up.", true); return }
        busy = true; setStatus("Encrypting and uploading…", false)
        scope.launch {
            try {
                val envelope = withContext(Dispatchers.Default) {
                    CloudCrypto.encryptWithKey(ExportImport.accountsToJson(accounts), key)
                }
                CloudBackupClient.uploadBackup(serverUrl, token, envelope)
                backups = CloudBackupClient.listBackups(serverUrl, token)
                setStatus("Backed up ${accounts.size} account(s) to the cloud.", false)
            } catch (e: Exception) {
                setStatus(e.message ?: "Backup failed.", true)
            } finally {
                busy = false
            }
        }
    }

    fun restore(filename: String) {
        val key = encKey ?: return
        busy = true; setStatus("Downloading and decrypting…", false)
        scope.launch {
            try {
                val envelope = CloudBackupClient.downloadBackup(serverUrl, token, filename)
                val plaintext = withContext(Dispatchers.Default) { CloudCrypto.decryptWithKey(envelope, key) }
                val imported = ExportImport.parseImport(plaintext)
                if (imported.isEmpty()) {
                    setStatus("Backup contained no accounts.", true)
                } else {
                    onImported(imported)
                    setStatus("Restored ${imported.size} account(s) from backup.", false)
                }
            } catch (e: Exception) {
                setStatus(e.message ?: "Restore failed.", true)
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bgCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("☁ Cloud Backup", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "End-to-end encrypted. Your master password never leaves this device — the server only stores an encrypted vault.",
                    color = muted, fontSize = 12.sp
                )

                CloudField("Server URL", serverUrl, enabled = !connected && !busy) { serverUrl = it }
                CloudField("Username", username, enabled = !connected && !busy) { username = it }
                if (!connected) {
                    CloudField("Master password", password, isPassword = true, enabled = !busy) { password = it }
                }

                if (!connected) {
                    Button(
                        onClick = { connect() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = brandOrange),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.height(16.dp), color = Color(0xFF07080B), strokeWidth = 2.dp)
                        } else {
                            Text(if (isRegister) "Register & Connect" else "Sign In", color = Color(0xFF07080B), fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = { isRegister = !isRegister }, enabled = !busy) {
                        Text(
                            if (isRegister) "Have an account? Sign in" else "Need an account? Register",
                            color = brandOrange, fontSize = 13.sp
                        )
                    }
                } else {
                    // Connected: backup + restore actions
                    Button(
                        onClick = { backUp() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = brandOrange),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("⬆ Back up vault now", color = Color(0xFF07080B), fontWeight = FontWeight.Bold) }

                    Text(
                        "✓ Signed in — your vault auto-backs-up after every change.",
                        color = Color(0xFF22C55E), fontSize = 11.sp
                    )

                    val curEncKey = encKey
                    if (showRecoverySetup && curEncKey != null) {
                        RecoverySetupSection(
                            vaultStorage = vaultStorage,
                            encKey = curEncKey,
                            onDone = { showRecoverySetup = false }
                        )
                    } else {
                        TextButton(onClick = { showRecoverySetup = true }) {
                            Text("🛟 Set up account recovery", color = brandOrange, fontSize = 13.sp)
                        }
                    }

                    Text("Backups on server", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (backups.isEmpty()) {
                        Text("No backups yet.", color = muted, fontSize = 12.sp)
                    } else {
                        Column(
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            backups.forEachIndexed { idx, b ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        b.timestamp + if (idx == 0) "  (latest)" else "",
                                        color = if (idx == 0) brandOrange else Color.White,
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(
                                        onClick = { restore(b.filename) },
                                        enabled = !busy,
                                        shape = RoundedCornerShape(8.dp)
                                    ) { Text("Restore", color = brandOrange, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }

                status?.let {
                    Text(it, color = if (statusError) Color(0xFFEF4444) else Color(0xFF22C55E), fontSize = 12.sp)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (connected) {
                        TextButton(onClick = { if (!busy) signOut() }) { Text("Sign out", color = Color(0xFFEF4444)) }
                    }
                    TextButton(onClick = { if (!busy) onDismiss() }) { Text("Close", color = muted) }
                }
            }
        }
    }
}

@Composable
private fun CloudField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = muted, fontSize = 12.sp) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = bgCard,
            unfocusedContainerColor = bgCard,
            focusedBorderColor = brandOrange,
            unfocusedBorderColor = chip,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = muted,
            disabledBorderColor = chip
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
