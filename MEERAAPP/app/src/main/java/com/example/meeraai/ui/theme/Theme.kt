package com.example.meeraai.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val MeeraDarkColorScheme = darkColorScheme(
    primary = MeeraPurple,
    onPrimary = MeeraOnSurface,
    primaryContainer = MeeraPurpleDark,
    secondary = MeeraPink,
    onSecondary = MeeraOnSurface,
    tertiary = MeeraPinkLight,
    background = MeeraBackground,
    surface = MeeraSurface,
    surfaceVariant = MeeraSurfaceVariant,
    onBackground = MeeraOnSurface,
    onSurface = MeeraOnSurface,
    onSurfaceVariant = MeeraGrayLight,
    error = MeeraRed,
)

@Composable
fun MeeraAITheme(
    content: @Composable () -> Unit
) {
    val colorScheme = MeeraDarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = MeeraBackground.toArgb()
            window.navigationBarColor = MeeraBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}