package app.openbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.openbridge.R

/** Значения совпадают с app.openbridge.data.SettingsRepository. */
const val THEME_DARK = "dark"
const val THEME_LIGHT = "light"
const val THEME_SYSTEM = "system"

// ── Flat technical palette ────────────────────────────────────────────────
val FlatBackground: Color = Color(0xFF0A0A0A)
val FlatSurface: Color = Color(0xFF111111)
val FlatSurfaceElevated: Color = Color(0xFF161616)
val FlatBorder: Color = Color(0xFF242424)
val FlatBorderActive: Color = Color(0xFF3A3A3A)
val FlatAccent: Color = Color(0xFFFF3B1D)
val FlatError: Color = Color(0xFFFF4A26)
val FlatAccentDimBg: Color = Color(0xFF1F100D)
val FlatTextPrimary: Color = Color(0xFFF2F2F2)
val FlatTextSecondary: Color = Color(0xFF8A8A8A)
val FlatTextMuted: Color = Color(0xFF555555)

// Flat тема. Минимум Material 3: вся поверхность плоская, без скруглений и теней.
// Ioskeley Mono — основной шрифт всего интерфейса.
private val FlatFont = FontFamily(
    Font(R.font.ioskeylemonnl_regular, FontWeight.Normal),
    Font(R.font.ioskeylemonnl_medium, FontWeight.Medium),
    Font(R.font.ioskeylemonnl_bold, FontWeight.Bold),
)

private val FlatTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont,
    ),
    displayMedium = TextStyle(
        fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont,
    ),
    displaySmall = TextStyle(
        fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont,
    ),
    headlineLarge = TextStyle(
        fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        fontFamily = FlatFont,
    ),
    headlineMedium = TextStyle(
        fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont,
    ),
    headlineSmall = TextStyle(
        fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
        fontFamily = FlatFont,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
        fontFamily = FlatFont,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.5.sp,
        fontFamily = FlatFont,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        fontFamily = FlatFont,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont,
    ),
)

private val FlatDarkColors = darkColorScheme(
    primary = FlatAccent,
    onPrimary = FlatBackground,
    primaryContainer = FlatAccentDimBg,
    onPrimaryContainer = FlatAccent,
    secondary = FlatBorderActive,
    onSecondary = FlatTextPrimary,
    secondaryContainer = FlatSurfaceElevated,
    onSecondaryContainer = FlatTextPrimary,
    tertiary = FlatBorderActive,
    onTertiary = FlatTextPrimary,
    tertiaryContainer = FlatSurfaceElevated,
    onTertiaryContainer = FlatTextPrimary,
    background = FlatBackground,
    onBackground = FlatTextPrimary,
    surface = FlatSurface,
    onSurface = FlatTextPrimary,
    surfaceVariant = FlatSurfaceElevated,
    onSurfaceVariant = FlatTextSecondary,
    surfaceContainerLowest = FlatSurface,
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = FlatSurface,
    surfaceContainerHigh = FlatSurfaceElevated,
    surfaceContainerHighest = Color(0xFF1C1C1C),
    surfaceTint = FlatAccent,
    outline = FlatBorder,
    outlineVariant = FlatBorder,
    error = FlatAccent,
    onError = FlatBackground,
    errorContainer = FlatAccentDimBg,
    onErrorContainer = FlatAccent,
)

private val FlatLightColors = lightColorScheme(
    primary = Color(0xFFFF3B1D),
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFF1F100D),
    onPrimaryContainer = Color(0xFFFF3B1D),
    secondary = Color(0xFF3A3A3A),
    onSecondary = Color(0xFFF2F2F2),
    secondaryContainer = Color(0xFF161616),
    onSecondaryContainer = Color(0xFFF2F2F2),
    tertiary = Color(0xFF3A3A3A),
    onTertiary = Color(0xFFF2F2F2),
    tertiaryContainer = Color(0xFF161616),
    onTertiaryContainer = Color(0xFFF2F2F2),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFF8A8A8A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE3E3E3),
    surfaceTint = Color(0xFFFF3B1D),
    outline = Color(0xFF242424),
    outlineVariant = Color(0xFF242424),
    error = Color(0xFFFF3B1D),
    onError = Color(0xFF0A0A0A),
    errorContainer = Color(0xFF1F100D),
    onErrorContainer = Color(0xFFFF3B1D),
)

@Composable
fun isDarkTheme(mode: String): Boolean = when (mode) {
    THEME_LIGHT -> false
    THEME_SYSTEM -> isSystemInDarkTheme()
    else -> true
}

/**
 * Flat technical UI: deep matte black surfaces, 1.dp outlines, red-orange accent.
 */
@Composable
fun OpenBridgeTheme(mode: String = THEME_DARK, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDarkTheme(mode)) FlatDarkColors else FlatLightColors,
        typography = FlatTypography,
        content = content,
    )
}
