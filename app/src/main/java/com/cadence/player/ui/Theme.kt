package com.cadence.player.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val BitChordDarkSurface = Color(0xFF0F0F11)
val BitChordCardSurface = Color(0xFF1C1C1E)
val BitChordPillBackground = Color(0xFF2C2C2E)
val BitChordPrimaryWhite = Color(0xFFFFFFFF)
val BitChordTextSecondary = Color(0xFF98989F)
val BitChordAccentGreen = Color(0xFF34C759)
val BitChordCyanGradient = Color(0xFF0A4E5C)

private val FallbackDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA0C4FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF004881),
    onPrimaryContainer = Color(0xFFD1E4FF),
    surface = BitChordDarkSurface,
    onSurface = BitChordPrimaryWhite,
    surfaceVariant = BitChordCardSurface,
    onSurfaceVariant = BitChordTextSecondary,
    secondaryContainer = BitChordPillBackground,
    onSecondaryContainer = BitChordPrimaryWhite
)

private val FallbackLightColorScheme = lightColorScheme(
    primary = Color(0xFF0061A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    secondaryContainer = Color(0xFFD5E3F7),
    onSecondaryContainer = Color(0xFF101C2B)
)

@Composable
fun CadenceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDarkColorScheme
        else -> FallbackLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
