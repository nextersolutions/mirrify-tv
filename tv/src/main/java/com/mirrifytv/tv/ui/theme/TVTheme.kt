package com.mirrifytv.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TVAccent = Color(0xFF00BCD4)
val TVBgDark = Color(0xFF080808)
val TVSurface = Color(0xFF141414)
val TVTextPrimary = Color(0xFFFFFFFF)
val TVTextSecondary = Color(0xFF9E9E9E)
val TVTextMuted = Color(0xFF616161)
val TVColorError = Color(0xFFE53935)
val TVColorSuccess = Color(0xFF4CAF50)

private val TVDarkColors = darkColorScheme(
    primary = TVAccent,
    background = TVBgDark,
    surface = TVSurface,
    onPrimary = Color.Black,
    onBackground = TVTextPrimary,
    onSurface = TVTextPrimary,
    error = TVColorError,
)

@Composable
fun TVMirrifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TVDarkColors,
        content = content,
    )
}
