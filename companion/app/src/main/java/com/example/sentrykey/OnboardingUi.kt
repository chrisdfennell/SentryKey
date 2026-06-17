package com.example.sentrykey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val brandOrange = Color(0xFFFFA500)
private val bgCard = Color(0xFF10121A)
private val muted = Color(0xFF8F93A3)

/**
 * One-time first-run guide. Three short cards; the cloud step can jump straight
 * into setup. Calling [onOpenCloud] or [onFinish] should mark onboarding done.
 */
@Composable
fun OnboardingDialog(
    onOpenCloud: () -> Unit,
    onFinish: () -> Unit
) {
    var step by remember { mutableStateOf(0) }

    data class Page(val icon: String, val title: String, val body: String)
    val pages = listOf(
        Page("🛡️", "Welcome to SentryKey", "Your 2FA codes live on your wrist and your phone — encrypted everywhere. Add an account by scanning its QR code."),
        Page("☁", "Encrypted cloud backup", "Optionally back up your vault to the cloud, end-to-end encrypted. Only you can decrypt it — set it up now and your codes are safe and synced across devices."),
        Page("🔄", "Everything syncs automatically", "Once you're signed in and your watch is paired, any change backs up to the cloud and pushes to your watch on its own. No buttons to remember.")
    )
    val page = pages[step]
    val isLast = step == pages.lastIndex

    Dialog(onDismissRequest = { onFinish() }) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bgCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(page.icon, fontSize = 40.sp)
                Text(page.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(page.body, color = muted, fontSize = 14.sp)

                Spacer(Modifier.height(4.dp))

                // Progress dots
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pages.indices.forEach { i ->
                        Text(if (i == step) "●" else "○", color = if (i == step) brandOrange else muted, fontSize = 12.sp)
                    }
                }

                if (step == 1) {
                    Button(
                        onClick = onOpenCloud,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = brandOrange),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("Set up cloud backup", color = Color(0xFF07080B), fontWeight = FontWeight.Bold) }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onFinish) { Text("Skip", color = muted) }
                    Button(
                        onClick = { if (isLast) onFinish() else step++ },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222533)),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text(if (isLast) "Done" else "Next", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
