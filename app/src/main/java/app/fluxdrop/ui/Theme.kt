package app.fluxdrop.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3D6BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF5A5F71),
    secondaryContainer = Color(0xFFDFE2F3),
    onSecondaryContainer = Color(0xFF171B2B),
    tertiary = Color(0xFF00696E),
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFF7F8FC),
    surfaceVariant = Color(0xFFE2E2EC),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB4C4FF),
    onPrimary = Color(0xFF002B75),
    primaryContainer = Color(0xFF1B3F9E),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFC3C6D8),
    secondaryContainer = Color(0xFF3F4352),
    onSecondaryContainer = Color(0xFFDFE2F3),
    tertiary = Color(0xFF4CD9E0),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF44464F),
)

@Composable
fun FluxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // dynamic*ColorScheme читает системную палитру и создаёт новый объект.
    // Без remember это происходило на каждую рекомпозицию темы, а от неё
    // зависит вся цветовая схема приложения.
    val colors = remember(darkTheme) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> DarkScheme
            else -> LightScheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
