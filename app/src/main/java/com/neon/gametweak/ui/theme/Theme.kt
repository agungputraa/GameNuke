package com.neon.gametweak.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val NukeDarkColorScheme = darkColorScheme(
    primary = NukeGreen,
    onPrimary = Color(0xFF001B13),
    primaryContainer = Color(0xFF073E2E),
    onPrimaryContainer = Color(0xFFC2FFE9),
    secondary = NukeViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF251F48),
    onSecondaryContainer = Color(0xFFE3DEFF),
    tertiary = NukeOrange,
    background = NukeBackground,
    onBackground = Color(0xFFEAF7F1),
    surface = NukeSurface,
    onSurface = Color(0xFFEAF7F1),
    surfaceVariant = NukeSurfaceHigh,
    onSurfaceVariant = NukeTextSecondary,
    outline = NukeOutline,
    error = NukeDanger,
)

@Composable
fun NukeEnterpriseTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            activity.window.statusBarColor = NukeBackground.toArgb()
            activity.window.navigationBarColor = NukeBackground.toArgb()
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            activity.window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }
    MaterialTheme(
        colorScheme = NukeDarkColorScheme,
        typography = Typography,
        content = content,
    )
}

@Composable
fun MyComposeApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    NukeEnterpriseTheme(content)
}
