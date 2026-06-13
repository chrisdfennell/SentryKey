package com.example.sentrykey

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.sentrykey.ui.theme.SentryKeyTheme
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ----------------------------------------------------------------------------------
// File-level TOTP Encryption Engine
// ----------------------------------------------------------------------------------

fun decodeBase32(base32: String): ByteArray {
    val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val clean = base32.uppercase().replace(" ", "").replace("-", "").replace("=", "")
    val out = ByteArrayOutputStream()
    var bits = 0
    var value = 0
    for (i in 0 until clean.length) {
        val char = clean[i]
        val lookup = allowedChars.indexOf(char)
        if (lookup < 0) continue
        value = (value shl 5) or lookup
        bits += 5
        if (bits >= 8) {
            bits -= 8
            out.write((value shr bits) and 0xFF)
        }
    }
    return out.toByteArray()
}

fun getTOTPCode(secret: String, timeSeconds: Long): String {
    try {
        val key = decodeBase32(secret)
        if (key.isEmpty()) return "000000"
        val epoch30 = timeSeconds / 30
        val data = ByteArray(8)
        var temp = epoch30
        for (i in 7 downTo 0) {
            data[i] = (temp and 0xFF).toByte()
            temp = temp shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        val keySpec = SecretKeySpec(key, "HmacSHA1")
        mac.init(keySpec)
        val hash = mac.doFinal(data)
        val offset = (hash[hash.size - 1].toInt() and 0x0F)
        val binary = (((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                ((hash[offset + 3].toInt() and 0xFF)))
        val otp = binary % 1000000
        var result = otp.toString()
        while (result.length < 6) {
            result = "0$result"
        }
        return result
    } catch (e: Exception) {
        return "000000"
    }
}

// ----------------------------------------------------------------------------------
// URI Parser & Service Domain Resolver
// ----------------------------------------------------------------------------------

fun parseOtpauthUri(uriString: String): TwoFactorAccount? {
    try {
        if (!uriString.startsWith("otpauth://", ignoreCase = true)) return null
        val cleanUri = uriString.trim()
        
        // 1. Extract secret
        val secretKey = "secret="
        val secretIndex = cleanUri.indexOf(secretKey)
        if (secretIndex == -1) return null
        var secretEnd = cleanUri.indexOf('&', secretIndex)
        if (secretEnd == -1) secretEnd = cleanUri.length
        val rawSecret = cleanUri.substring(secretIndex + secretKey.length, secretEnd)
        val secret = URLDecoder.decode(rawSecret, "UTF-8").uppercase().replace(" ", "").replace("-", "")
        
        // 2. Extract label (path segment)
        val totpKey = "totp/"
        val totpIndex = cleanUri.indexOf(totpKey)
        if (totpIndex == -1) return null
        val labelStart = totpIndex + totpKey.length
        var labelEnd = cleanUri.indexOf('?', labelStart)
        if (labelEnd == -1) labelEnd = cleanUri.length
        var rawLabel = cleanUri.substring(labelStart, labelEnd)
        rawLabel = URLDecoder.decode(rawLabel, "UTF-8")
        
        // 3. Extract optional issuer parameter
        val issuerKey = "issuer="
        val issuerIndex = cleanUri.indexOf(issuerKey)
        val issuer = if (issuerIndex != -1) {
            var issuerEnd = cleanUri.indexOf('&', issuerIndex)
            if (issuerEnd == -1) issuerEnd = cleanUri.length
            val rawIssuer = cleanUri.substring(issuerIndex + issuerKey.length, issuerEnd)
            URLDecoder.decode(rawIssuer, "UTF-8")
        } else {
            ""
        }
        
        // 4. Clean up format
        var label = rawLabel
        if (issuer.isNotEmpty() && !label.startsWith(issuer, ignoreCase = true)) {
            label = "$issuer ($label)"
        }
        
        return TwoFactorAccount(label.trim(), secret)
    } catch (e: Exception) {
        return null
    }
}

fun getServiceDomain(label: String): String {
    val clean = label.lowercase()
    return when {
        clean.contains("github") -> "github.com"
        clean.contains("google") -> "google.com"
        clean.contains("slack") -> "slack.com"
        clean.contains("discord") -> "discord.com"
        clean.contains("microsoft") -> "microsoft.com"
        clean.contains("aws") || clean.contains("amazon") -> "amazon.com"
        clean.contains("facebook") || clean.contains("meta") -> "facebook.com"
        clean.contains("instagram") -> "instagram.com"
        clean.contains("twitter") || clean.contains(" x ") || clean.equals("x") -> "x.com"
        clean.contains("reddit") -> "reddit.com"
        clean.contains("twitch") -> "twitch.tv"
        clean.contains("bitbucket") -> "bitbucket.org"
        clean.contains("gitlab") -> "gitlab.com"
        clean.contains("dropbox") -> "dropbox.com"
        clean.contains("heroku") -> "heroku.com"
        clean.contains("cloudflare") -> "cloudflare.com"
        clean.contains("digitalocean") -> "digitalocean.com"
        clean.contains("proton") -> "proton.me"
        clean.contains("spotify") -> "spotify.com"
        clean.contains("zoom") -> "zoom.us"
        clean.contains("epic") -> "epicgames.com"
        clean.contains("steam") -> "steampowered.com"
        clean.contains("coinbase") -> "coinbase.com"
        clean.contains("binance") -> "binance.com"
        clean.contains("paypal") -> "paypal.com"
        clean.contains("stripe") -> "stripe.com"
        clean.contains("nintendo") -> "nintendo.com"
        clean.contains("playstation") -> "playstation.com"
        else -> ""
    }
}

// ----------------------------------------------------------------------------------
// MainActivity Entry Point
// ----------------------------------------------------------------------------------

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
                    containerColor = Color(0xFF07080B)
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

// ----------------------------------------------------------------------------------
// SentryKey Dashboard UI
// ----------------------------------------------------------------------------------

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
    var searchQuery by remember { mutableStateOf("") }
    
    // Add Account Dialog state
    var isAddDialogOpen by remember { mutableStateOf(false) }
    var newLabel by remember { mutableStateOf("") }
    var newSecret by remember { mutableStateOf("") }

    // Sync status states
    var syncStatus by remember { mutableStateOf("Ready to sync") }
    var syncStatusColor by remember { mutableStateOf(Color(0xFF8F93A3)) }

    // Live clock timer for 1Hz updates
    var currentUnixTime by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            currentUnixTime = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }

    // --- TEMP in-app auto-update (testing). Polls GitHub Releases. ---
    val scope = rememberCoroutineScope()
    var updateTag by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    if (AUTO_UPDATE_TEST_MODE) {
        LaunchedEffect(Unit) {
            while (true) {
                val info = UpdateManager.fetchLatest()
                if (info?.apkUrl != null && info.tag != BuildConfig.RELEASE_TAG) {
                    updateTag = info.tag
                    updateUrl = info.apkUrl
                }
                delay(UPDATE_POLL_SECONDS * 1000)
            }
        }
    }

    // Filter accounts based on query
    val filteredAccounts = remember(accounts, searchQuery) {
        accounts.filter { it.label.contains(searchQuery, ignoreCase = true) }
    }

    // Initialize Google Play Services Code Scanner
    val scanner = remember {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options)
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { isAddDialogOpen = true },
                containerColor = Color(0xFFFFA500),
                contentColor = Color(0xFF07080B),
                shape = CircleShape
            ) {
                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // In-app update banner (testing)
            val tag = updateTag
            val url = updateUrl
            if (tag != null && url != null) {
                UpdateBanner(tag = tag, busy = updateBusy) {
                    if (!canInstallApks(context)) {
                        requestInstallPermission(context)
                    } else {
                        updateBusy = true
                        scope.launch {
                            val file = UpdateManager.downloadApk(context, url)
                            updateBusy = false
                            if (file != null) {
                                UpdateManager.installApk(context, file)
                            } else {
                                Toast.makeText(context, "Update download failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SENTRYKEY VAULT",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Companion App",
                        fontSize = 12.sp,
                        color = Color(0xFFFFA500),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
                
                // Active count badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF222533))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${accounts.size} SEEDS",
                        fontSize = 11.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Sync Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF222533), RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10121A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Connection dot indicator
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(syncStatusColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = syncStatus,
                            fontSize = 13.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }

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
                                        syncStatusColor = Color(0xFF22C55E)
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    }

                                    override fun onError(error: String) {
                                        syncStatus = error
                                        syncStatusColor = Color(0xFFEF4444)
                                        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                    }

                                    override fun onStatusUpdate(status: String) {
                                        syncStatus = status
                                        syncStatusColor = Color(0xFFF97316)
                                    }
                                })
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("🔄 Sync Watch", color = Color(0xFF07080B), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                val vaultStr = vaultStorage.toVaultString(accounts)
                                val clip = ClipData.newPlainText("SentryKey Vault", vaultStr)
                                clipboardManager.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied vault string to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222533)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("📋 Copy String", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Search bar & Scan direct
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("🔍 Search accounts...", color = Color(0xFF5A5F7A)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF10121A),
                        unfocusedContainerColor = Color(0xFF10121A),
                        focusedBorderColor = Color(0xFFFFA500),
                        unfocusedBorderColor = Color(0xFF222533),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )

                // Quick Scan QR Button
                Button(
                    onClick = {
                        scanner.startScan()
                            .addOnSuccessListener { barcode ->
                                val rawValue = barcode.rawValue ?: ""
                                val parsedAcc = parseOtpauthUri(rawValue)
                                if (parsedAcc != null) {
                                    val updatedList = accounts + parsedAcc
                                    accounts = updatedList
                                    vaultStorage.saveAccounts(updatedList)
                                    Toast.makeText(context, "Added: ${parsedAcc.label}", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Invalid 2FA QR Code", Toast.LENGTH_LONG).show()
                                }
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(context, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222533)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(54.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("📷", fontSize = 20.sp)
                }
            }

            // Accounts List
            if (filteredAccounts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (accounts.isEmpty()) "No accounts stored. Tap '+' below." else "No matches found.",
                        color = Color(0xFF5A5F7A),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredAccounts) { account ->
                        AccountCard(
                            account = account,
                            currentUnixTime = currentUnixTime,
                            clipboardManager = clipboardManager,
                            context = context,
                            onDelete = {
                                val updatedList = accounts.filter { it != account }
                                accounts = updatedList
                                vaultStorage.saveAccounts(updatedList)
                                Toast.makeText(context, "Deleted account", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    // Add Account Dialog (supports Scan + Manual fields)
    if (isAddDialogOpen) {
        AlertDialog(
            onDismissRequest = {
                isAddDialogOpen = false
                newLabel = ""
                newSecret = ""
            },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Add 2FA Account",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    
                    // Scan inside dialog
                    TextButton(
                        onClick = {
                            scanner.startScan()
                                .addOnSuccessListener { barcode ->
                                    val rawValue = barcode.rawValue ?: ""
                                    val parsedAcc = parseOtpauthUri(rawValue)
                                    if (parsedAcc != null) {
                                        newLabel = parsedAcc.label
                                        newSecret = parsedAcc.secret
                                        Toast.makeText(context, "QR parsed successfully!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Invalid 2FA QR Code", Toast.LENGTH_LONG).show()
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(context, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                        }
                    ) {
                        Text("📷 Scan QR", color = Color(0xFFFFA500), fontWeight = FontWeight.Bold)
                    }
                }
            },
            containerColor = Color(0xFF10121A),
            shape = RoundedCornerShape(16.dp),
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text("Account Label (e.g. GitHub)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFFA500),
                            unfocusedBorderColor = Color(0xFF222533),
                            focusedLabelColor = Color(0xFFFFA500),
                            unfocusedLabelColor = Color(0xFF8F93A3),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newSecret,
                        onValueChange = { newSecret = it.uppercase().replace(" ", "") },
                        label = { Text("Base32 Secret Seed") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFFA500),
                            unfocusedBorderColor = Color(0xFF222533),
                            focusedLabelColor = Color(0xFFFFA500),
                            unfocusedLabelColor = Color(0xFF8F93A3),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
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
                            isAddDialogOpen = false
                            newLabel = ""
                            newSecret = ""
                            Toast.makeText(context, "Account added", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Add Account", color = Color(0xFF07080B), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        isAddDialogOpen = false
                        newLabel = ""
                        newSecret = ""
                    }
                ) {
                    Text("Cancel", color = Color(0xFF8F93A3))
                }
            }
        )
    }
}

// ----------------------------------------------------------------------------------
// Account Card Composable
// ----------------------------------------------------------------------------------

@Composable
fun AccountCard(
    account: TwoFactorAccount,
    currentUnixTime: Long,
    clipboardManager: ClipboardManager,
    context: Context,
    onDelete: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    
    val timeRemaining = 30 - (currentUnixTime % 30)
    val totpCode = getTOTPCode(account.secret, currentUnixTime)
    val formattedCode = totpCode.take(3) + " " + totpCode.drop(3)

    // Calculate dynamic fallback avatar letter and background color
    val avatarLetter = account.label.firstOrNull()?.uppercase() ?: "?"
    val avatarBgColor = remember(account.label) {
        val hash = account.label.hashCode()
        val colors = listOf(
            Color(0xFF3F51B5), // Indigo
            Color(0xFFE91E63), // Pink
            Color(0xFF009688), // Teal
            Color(0xFFFF9800), // Orange
            Color(0xFF9C27B0), // Purple
            Color(0xFF4CAF50)  // Green
        )
        colors[abs(hash) % colors.size]
    }

    // Determine domain for brand favicon URL
    val serviceDomain = remember(account.label) { getServiceDomain(account.label) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .border(1.dp, Color(0xFF222533), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10121A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Brand Logo or Fallback Avatar initial
                if (serviceDomain.isNotEmpty() && !loadFailed) {
                    AsyncImage(
                        model = "https://www.google.com/s2/favicons?sz=64&domain=$serviceDomain",
                        contentDescription = "${account.label} Logo",
                        onError = { loadFailed = true },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1B1D2A))
                            .padding(4.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(avatarBgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = avatarLetter,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Account Name & Info Label
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.label,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Text(
                        text = "Tap to manage",
                        fontSize = 10.sp,
                        color = Color(0xFF5A5F7A)
                    )
                }

                // Spaced TOTP 2FA Code
                Text(
                    text = formattedCode,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1B1D2A))
                        .clickable {
                            val clip = ClipData.newPlainText("SentryKey OTP", totpCode)
                            clipboardManager.setPrimaryClip(clip)
                            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Canvas Countdown circle
                Canvas(modifier = Modifier.size(20.dp)) {
                    val sweepAngle = (timeRemaining.toFloat() / 30f) * 360f
                    drawArc(
                        color = Color(0xFF222533),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                    drawArc(
                        color = if (timeRemaining <= 5) Color(0xFFEF4444) else Color(0xFFFFA500),
                        startAngle = -90f,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }
            }

            // Expanded seed management panel
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(150))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .border(1.dp, Color(0xFF222533), RoundedCornerShape(8.dp))
                        .background(Color(0xFF0B0D13))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "SECRET SEED KEY",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8F93A3),
                        letterSpacing = 1.sp
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Display Base32 seed in clean groups of 4
                    val spacedSecret = account.secret.chunked(4).joinToString(" ")
                    Text(
                        text = spacedSecret,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val clip = ClipData.newPlainText("SentryKey Seed", account.secret)
                                clipboardManager.setPrimaryClip(clip)
                                Toast.makeText(context, "Seed key copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222533)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            Text("📋 Copy Seed", fontSize = 11.sp, color = Color.White)
                        }

                        Button(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x22EF4444)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            Text("🗑 Delete", fontSize = 11.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}