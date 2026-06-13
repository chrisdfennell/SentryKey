package com.example.sentrykey.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = OrangePrimary,
    secondary = OrangeSecondary,
    background = DarkBg,
    surface = CardBg,
    onPrimary = DarkBg,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun SentryKeyTheme(
    darkTheme: Boolean = true, // Default to true for Premium AMOLED look
    dynamicColor: Boolean = false, // Default to false to preserve brand branding
    content: @Composable () -> Unit
) {
    // Force SentryKey dark color scheme
    val colorScheme = DarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}