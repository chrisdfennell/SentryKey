package com.example.sentrykey

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast

private val brandOrange = Color(0xFFFFA500)
private val chip = Color(0xFF222533)

/** What the passphrase, once entered, should be used for. */
private enum class ExportAction { SAVE, SHARE }

/** Export / Import button row, self-contained (owns its dialogs + file picker). */
@Composable
fun VaultActionsRow(
    accounts: List<TwoFactorAccount>,
    onImported: (List<TwoFactorAccount>) -> Unit
) {
    val context = LocalContext.current
    var showExportChoice by remember { mutableStateOf(false) }
    var showPlaintextWarning by remember { mutableStateOf(false) }

    // Encrypted-export flow: collect a passphrase, then save or share.
    var exportAction by remember { mutableStateOf(ExportAction.SAVE) }
    var showExportPassphrase by remember { mutableStateOf(false) }
    // Passphrase the save launcher should use (set just before launching).
    var pendingSavePassword by remember { mutableStateOf<String?>(null) }

    // Encrypted-import flow: hold the picked ciphertext until a passphrase arrives.
    var encryptedImportText by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            when {
                text == null -> Toast.makeText(context, "Couldn't read file", Toast.LENGTH_SHORT).show()
                ExportImport.isEncryptedBackup(text) -> encryptedImportText = text
                else -> {
                    val imported = ExportImport.parseImport(text)
                    if (imported.isEmpty()) {
                        Toast.makeText(context, "Nothing importable found", Toast.LENGTH_SHORT).show()
                    } else {
                        onImported(imported)
                        Toast.makeText(context, "Imported ${imported.size} account(s)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Save-to-device via the system document picker. Encrypts when a passphrase
    // was collected first; otherwise writes the plaintext JSON backup.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val pw = pendingSavePassword
        pendingSavePassword = null
        if (uri != null) {
            try {
                val body = if (pw != null) {
                    ExportImport.accountsToEncryptedJson(accounts, pw)
                } else {
                    ExportImport.accountsToJson(accounts)
                }
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(body.toByteArray())
                }
                Toast.makeText(context, "Vault saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { if (accounts.isNotEmpty()) showExportChoice = true },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = chip),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("⬆ Export", color = Color.White, fontSize = 13.sp)
        }
        Button(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = chip),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("⬇ Import", color = Color.White, fontSize = 13.sp)
        }
    }

    // Step 1: encrypted (recommended) vs plaintext.
    if (showExportChoice) {
        AlertDialog(
            onDismissRequest = { showExportChoice = false },
            title = { Text("Export vault") },
            text = {
                Text(
                    "An encrypted backup is locked with a passphrase — useless to anyone " +
                    "without it. Plaintext is readable by anyone who gets the file."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportChoice = false
                    exportAction = ExportAction.SAVE
                    showExportPassphrase = true
                }) { Text("Encrypted") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportChoice = false
                    showPlaintextWarning = true
                }) { Text("Plaintext") }
            }
        )
    }

    // Step 2a (encrypted): collect a passphrase, then choose save or share.
    if (showExportPassphrase) {
        PassphraseDialog(
            title = "Set a backup passphrase",
            confirmLabel = "Continue",
            requireConfirmation = true,
            onDismiss = { showExportPassphrase = false },
            onConfirm = { password ->
                showExportPassphrase = false
                if (exportAction == ExportAction.SHARE) {
                    ExportImport.shareBackup(context, accounts, password)
                } else {
                    pendingSavePassword = password
                    saveLauncher.launch("sentrykey-vault.skbackup")
                }
            },
            extraAction = "Share instead" to {
                exportAction = ExportAction.SHARE
            }
        )
    }

    // Step 2b (plaintext): the original loud warning.
    if (showPlaintextWarning) {
        AlertDialog(
            onDismissRequest = { showPlaintextWarning = false },
            title = { Text("Plaintext export?") },
            text = {
                Text(
                    "This creates an UNENCRYPTED file with your 2FA secrets — anyone " +
                    "with it can generate your codes. Only send it somewhere you trust."
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        showPlaintextWarning = false
                        pendingSavePassword = null
                        saveLauncher.launch("sentrykey-vault.json")
                    }) { Text("Save to file") }
                    TextButton(onClick = {
                        showPlaintextWarning = false
                        ExportImport.shareBackup(context, accounts)
                    }) { Text("Share") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPlaintextWarning = false }) { Text("Cancel") }
            }
        )
    }

    // Import: an encrypted backup was picked — ask for the passphrase.
    encryptedImportText?.let { cipherText ->
        PassphraseDialog(
            title = "Enter backup passphrase",
            confirmLabel = "Import",
            requireConfirmation = false,
            onDismiss = { encryptedImportText = null },
            onConfirm = { password ->
                try {
                    val imported = ExportImport.parseEncryptedImport(cipherText, password)
                    encryptedImportText = null
                    if (imported.isEmpty()) {
                        Toast.makeText(context, "Nothing importable found", Toast.LENGTH_SHORT).show()
                    } else {
                        onImported(imported)
                        Toast.makeText(context, "Imported ${imported.size} account(s)", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: BadPasswordException) {
                    Toast.makeText(context, e.message ?: "Wrong passphrase", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

/**
 * Passphrase entry dialog. When [requireConfirmation] is true the user must type
 * the passphrase twice and they must match before [onConfirm] is enabled.
 * [extraAction] adds a secondary button (label to side-effect) run just before confirm.
 */
@Composable
private fun PassphraseDialog(
    title: String,
    confirmLabel: String,
    requireConfirmation: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    extraAction: Pair<String, () -> Unit>? = null
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val mismatch = requireConfirmation && confirm.isNotEmpty() && password != confirm
    val valid = password.length >= 6 && (!requireConfirmation || password == confirm)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (requireConfirmation) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = mismatch,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (mismatch) "Passphrases don't match" else "At least 6 characters. There's no way to recover a lost passphrase.",
                        color = if (mismatch) Color(0xFFE25555) else Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(password) }
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (extraAction != null) {
                    TextButton(
                        enabled = valid,
                        onClick = {
                            extraAction.second.invoke()
                            // The extra action flips intent; reuse onConfirm with current password.
                            onConfirm(password)
                        }
                    ) { Text(extraAction.first) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** Dialog showing a scannable otpauth QR for one account. */
@Composable
fun AccountQrDialog(account: TwoFactorAccount, onDismiss: () -> Unit) {
    val uri = ExportImport.accountToOtpauthUri(account)
    val bitmap = remember(uri) { ExportImport.generateQrBitmap(uri) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(account.label) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "otpauth QR for ${account.label}",
                    modifier = Modifier.size(220.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("Scan in another authenticator app", color = Color.Gray, fontSize = 11.sp)
            }
        }
    )
}
