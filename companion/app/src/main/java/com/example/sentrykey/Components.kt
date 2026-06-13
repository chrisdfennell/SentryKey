package com.example.sentrykey

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Reusable on-brand modal (dark surface, orange accent) used across the app for
 * warnings/confirmations/results, instead of the default Material AlertDialog.
 */
@Composable
fun SentryModal(
    icon: String,
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String? = "Cancel",
    confirmColor: Color = Color(0xFFFFA500)
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF10121A),
            border = BorderStroke(1.dp, Color(0xFF222533))
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, fontSize = 22.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text(message, color = Color(0xFF8F93A3), fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dismissText != null) {
                        TextButton(onClick = onDismiss) {
                            Text(dismissText, color = Color(0xFF8F93A3))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = confirmColor),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(confirmText, color = Color(0xFF07080B), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
