package dev.sn.app.ui

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

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB2FF),
    onPrimary = Color(0xFF12183A),
    surface = Color(0xFF14151A),
    background = Color(0xFF0F1014),
    surfaceVariant = Color(0xFF23252E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3A4E9E),
    surface = Color(0xFFFAFAFD),
    background = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7E9F2),
)

@Composable
fun SnTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Material You on the S23 means the app picks up the wallpaper palette,
    // which is the point of running natively rather than in a terminal.
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
