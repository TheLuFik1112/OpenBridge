package app.openbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Значения совпадают с app.openbridge.data.SettingsRepository. */
const val THEME_DARK = "dark"
const val THEME_LIGHT = "light"
const val THEME_SYSTEM = "system"

/**
 * Полностью чёрная тема по умолчанию — OLED-экранам так проще, а интерфейс
 * выглядит собраннее. Светлая — ровно белая. Dynamic color (Material You) не
 * используется: цвета приложения не должны зависеть от обоев пользователя.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CA2FF),
    onPrimary = Color(0xFF00174A),
    primaryContainer = Color(0xFF14284F),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFB7C2D6),
    onSecondary = Color(0xFF1F2736),
    secondaryContainer = Color(0xFF1B2333),
    onSecondaryContainer = Color(0xFFDCE2F0),
    tertiary = Color(0xFF7BE0D2),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF0E2F2B),
    onTertiaryContainer = Color(0xFFB4F2E9),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEDEFF4),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEDEFF4),
    surfaceVariant = Color(0xFF2A2E38),
    onSurfaceVariant = Color(0xFFA9AFBD),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0B0E),
    surfaceContainer = Color(0xFF101115),
    surfaceContainerHigh = Color(0xFF17191F),
    surfaceContainerHighest = Color(0xFF1F222A),
    surfaceTint = Color(0xFF7CA2FF),
    outline = Color(0xFF3A404C),
    outlineVariant = Color(0xFF262A33),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5BDF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF565D6D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E6F1),
    onSecondaryContainer = Color(0xFF141A26),
    tertiary = Color(0xFF00736A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB6F2EA),
    onTertiaryContainer = Color(0xFF00201D),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF11141A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11141A),
    surfaceVariant = Color(0xFFE0E3EA),
    onSurfaceVariant = Color(0xFF565C69),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FB),
    surfaceContainer = Color(0xFFF1F3F7),
    surfaceContainerHigh = Color(0xFFECEEF4),
    surfaceContainerHighest = Color(0xFFE5E8EF),
    surfaceTint = Color(0xFF2E5BDF),
    outline = Color(0xFFC3C7D1),
    outlineVariant = Color(0xFFE3E5EC),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun isDarkTheme(mode: String): Boolean = when (mode) {
    THEME_LIGHT -> false
    THEME_SYSTEM -> isSystemInDarkTheme()
    else -> true
}

@Composable
fun OpenBridgeTheme(mode: String = THEME_DARK, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDarkTheme(mode)) DarkColors else LightColors,
        content = content,
    )
}
