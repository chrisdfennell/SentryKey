package com.fennell.sentrykey.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    var accounts by remember { mutableStateOf(WearVaultStore.getAccounts(context)) }
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }

    // 1 Hz tick; also re-read the store so freshly-synced accounts appear live.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            accounts = WearVaultStore.getAccounts(context)
            delay(1000)
        }
    }

    MaterialTheme {
        if (accounts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No codes yet.\nSync from your phone.",
                    textAlign = TextAlign.Center,
                    color = Color(0xFF8F93A3),
                    fontSize = 13.sp
                )
            }
        } else {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(accounts) { acc -> AccountCard(acc, now) }
            }
        }
    }
}

@Composable
private fun AccountCard(acc: WearAccount, now: Long) {
    val code = getTOTPCode(acc.secret, now)
    val remaining = 30 - (now % 30)
    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(2.dp)) {
            Text(text = acc.label, maxLines = 1, color = Color.White, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = code.take(3) + " " + code.drop(3),
                    color = Color(0xFFFFA500),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(text = "${remaining}s", color = Color(0xFF8F93A3), fontSize = 11.sp)
            }
        }
    }
}
