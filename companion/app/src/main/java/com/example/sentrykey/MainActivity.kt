package com.example.sentrykey

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sentrykey.ui.theme.SentryKeyTheme

class MainActivity : ComponentActivity() {
    private lateinit var vaultStorage: VaultStorage
    private lateinit var syncManager: GarminSyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        vaultStorage = VaultStorage(this)
        syncManager = GarminSyncManager(this)

        setContent {
            SentryKeyTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0F1016) // Premium dark charcoal
                ) { innerPadding ->
                    SentryKeyDashboard(
                        vaultStorage = vaultStorage,
                        syncManager = syncManager,
                        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager,
                        context = this,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentryKeyDashboard(
    vaultStorage: VaultStorage,
    syncManager: GarminSyncManager,
    clipboardManager: ClipboardManager,
    context: Context,
    modifier: Modifier = Modifier
) {
    var accounts by remember { mutableStateOf(vaultStorage.getAccounts()) }
    var newLabel by remember { mutableStateOf("") }
    var newSecret by remember { mutableStateOf("") }

    var syncStatus by remember { mutableStateOf("Ready to sync") }
    var syncStatusColor by remember { mutableStateOf(Color(0xFF8A90A6)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SentryKey 2FA",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Garmin tactix 8 Companion",
                    fontSize = 14.sp,
                    color = Color(0xFFFFA500), // Tactix amber accent
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Add Account Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF191B26)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Add Authenticator Seed",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                OutlinedTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    label = { Text("Account Name (e.g. GitHub)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFFA500),
                        unfocusedBorderColor = Color(0xFF3F445F),
                        focusedLabelColor = Color(0xFFFFA500),
                        unfocusedLabelColor = Color(0xFF8A90A6),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = newSecret,
                    onValueChange = { newSecret = it.uppercase().replace(" ", "") },
                    label = { Text("Base32 Secret Seed") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFFA500),
                        unfocusedBorderColor = Color(0xFF3F445F),
                        focusedLabelColor = Color(0xFFFFA500),
                        unfocusedLabelColor = Color(0xFF8A90A6),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Button(
                    onClick = {
                        val cleanSecret = newSecret.trim()
                        val cleanLabel = newLabel.trim()
                        val base32Regex = "^[A-Z2-7]+=*$".toRegex()

                        if (cleanLabel.isEmpty()) {
                            Toast.makeText(context, "Please enter an account name", Toast.LENGTH_SHORT).show()
                        } else if (cleanSecret.isEmpty() || !cleanSecret.matches(base32Regex)) {
                            Toast.makeText(context, "Invalid Secret (A-Z, 2-7 allowed)", Toast.LENGTH_LONG).show()
                        } else {
                            val newAcc = TwoFactorAccount(cleanLabel, cleanSecret)
                            val updatedList = accounts + newAcc
                            accounts = updatedList
                            vaultStorage.saveAccounts(updatedList)
                            newLabel = ""
                            newSecret = ""
                            Toast.makeText(context, "Account added locally", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Add Account", color = Color(0xFF0F1016), fontWeight = FontWeight.Bold)
                }
            }
        }

        // Sync Status Panel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF151722))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sync Status:", fontSize = 14.sp, color = Color(0xFF8A90A6))
                Text(
                    text = syncStatus,
                    fontSize = 14.sp,
                    color = syncStatusColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val vaultStr = vaultStorage.toVaultString(accounts)
                    syncManager.syncVault(vaultStr, object : GarminSyncManager.SyncCallback {
                        override fun onSuccess(message: String) {
                            syncStatus = message
                            syncStatusColor = Color(0xFF4CAF50)
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }

                        override fun onError(error: String) {
                            syncStatus = error
                            syncStatusColor = Color(0xFFF44336)
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }

                        override fun onStatusUpdate(status: String) {
                            syncStatus = status
                            syncStatusColor = Color(0xFFFFA500)
                        }
                    })
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(12.dp)
            ) {
                Text("Sync Watch", color = Color.White)
            }

            Button(
                onClick = {
                    val vaultStr = vaultStorage.toVaultString(accounts)
                    val clip = ClipData.newPlainText("SentryKey Vault", vaultStr)
                    clipboardManager.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied vault settings to clipboard", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F445F)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(12.dp)
            ) {
                Text("Copy String", color = Color.White)
            }
        }

        // Vault list header
        Text(
            text = "Active Accounts (${accounts.size})",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        // Stored accounts list
        if (accounts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No accounts stored. Add one above.",
                    color = Color(0xFF5A5F7A),
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(accounts) { account ->
                    AccountItemCard(
                        account = account,
                        onDelete = {
                            val updatedList = accounts.filter { it != account }
                            accounts = updatedList
                            vaultStorage.saveAccounts(updatedList)
                            Toast.makeText(context, "Account deleted", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AccountItemCard(account: TwoFactorAccount, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E202E)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = account.secret,
                    fontSize = 13.sp,
                    color = Color(0xFF8A90A6),
                    fontWeight = FontWeight.Light
                )
            }
            TextButton(onClick = onDelete) {
                Text(
                    text = "DELETE",
                    color = Color(0xFFF44336),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}