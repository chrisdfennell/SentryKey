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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast

private val brandOrange = Color(0xFFFFA500)
private val chip = Color(0xFF222533)

/** Export / Import button row, self-contained (owns its dialogs + file picker). */
@Composable
fun VaultActionsRow(
    accounts: List<TwoFactorAccount>,
    onImported: (List<TwoFactorAccount>) -> Unit
) {
    val context = LocalContext.current
    var showExportWarning by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            val imported = if (text != null) ExportImport.parseImport(text) else emptyList()
            if (imported.isEmpty()) {
                Toast.makeText(context, "Nothing importable found", Toast.LENGTH_SHORT).show()
            } else {
                onImported(imported)
                Toast.makeText(context, "Imported ${imported.size} account(s)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Save-to-device via the system document picker (writes wherever the user picks)
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(ExportImport.accountsToJson(accounts).toByteArray())
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
            onClick = { if (accounts.isNotEmpty()) showExportWarning = true },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = chip),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("⬆ Export", color = Color.White, fontSize = 13.sp)
        }
        Button(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = chip),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("⬇ Import", color = Color.White, fontSize = 13.sp)
        }
    }

    if (showExportWarning) {
        AlertDialog(
            onDismissRequest = { showExportWarning = false },
            title = { Text("Export vault?") },
            text = {
                Text(
                    "This creates an UNENCRYPTED file with your 2FA secrets — anyone " +
                    "with it can generate your codes. Only send it somewhere you trust."
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        showExportWarning = false
                        saveLauncher.launch("sentrykey-vault.json")
                    }) { Text("Save to file") }
                    TextButton(onClick = {
                        showExportWarning = false
                        ExportImport.shareBackup(context, accounts)
                    }) { Text("Share") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportWarning = false }) { Text("Cancel") }
            }
        )
    }
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
