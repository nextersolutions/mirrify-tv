package com.mirrifytv.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF00BCD4)
val BgDark = Color(0xFF0D0D0D)
val Surface = Color(0xFF1A1A1A)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF9E9E9E)
val TextMuted = Color(0xFF616161)
val ColorError = Color(0xFFE53935)
val ColorSuccess = Color(0xFF4CAF50)

private val DarkColors = darkColorScheme(
    primary = Accent,
    background = BgDark,
    surface = Surface,
    onPrimary = Color.Black,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = ColorError,
)

@Composable
fun MirrifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
