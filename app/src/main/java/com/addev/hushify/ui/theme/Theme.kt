package com.addev.hushify.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = SpotifyGreen,
    onPrimary = White,
    secondary = Color(0xFF8AB89A),
    onSecondary = Black,
    tertiary = Color(0xFF6FC98A),
    background = Color(0xFF121412),
    surface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFF2A322D),
    onBackground = Color(0xFFE2E3E2),
    onSurface = Color(0xFFE2E3E2),
    onSurfaceVariant = Color(0xFFC0C7C2),
    outline = Color(0xFF8A938E),
    outlineVariant = Color(0xFF3F4742)
)

private val LightColorScheme = lightColorScheme(
    primary = SpotifyGreen,
    onPrimary = White,
    secondary = Color(0xFF2E6D47),
    onSecondary = White,
    tertiary = Color(0xFF146B3A),
    background = Color(0xFFF6FBF8),
    surface = Color(0xFFFCFEFD),
    surfaceVariant = Color(0xFFDCE8E0),
    onBackground = Color(0xFF1A1C1B),
    onSurface = Color(0xFF1A1C1B),
    onSurfaceVariant = Color(0xFF3F4842),
    outline = Color(0xFF6F7972),
    outlineVariant = Color(0xFFBFC9C2)
)

@Composable
fun HushifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
