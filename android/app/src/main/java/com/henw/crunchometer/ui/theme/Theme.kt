package com.henw.crunchometer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette for Android < 12, where wallpaper-based colour isn't available.
private val LightFallback = lightColorScheme(
    primary = Color(0xFF00658D),
    secondary = Color(0xFF4E616D),
    tertiary = Color(0xFF615A7D),
)
private val DarkFallback = darkColorScheme(
    primary = Color(0xFF84CFFF),
    secondary = Color(0xFFB6C9D8),
    tertiary = Color(0xFFCBC1E9),
)

@Composable
fun CrunchOMeterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(colorScheme = colors, content = content)
}
