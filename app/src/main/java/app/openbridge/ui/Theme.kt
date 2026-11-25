package app.openbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextTransform
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
// TODO: заменить FontFamily.Monospace fallback на сам шрифт Ioskeley Mono —
// файлы ожидаются в res/font/ioskeylemonnl_regular.ttf,
// res/font/ioskeylemonnl_medium.ttf, res/font/ioskeylemonnl_bold.ttf.
private val FlatFont = FontFamily.Monospace

private val FlatTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Uppercase,
    ),
    displayMedium = TextStyle(
        fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Uppercase,
    ),
    displaySmall = TextStyle(
        fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Uppercase,
    ),
    headlineLarge = TextStyle(
        fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Uppercase,
    ),
    headlineMedium = TextStyle(
        fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Uppercase,
    ),
    headlineSmall = TextStyle(
        fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Uppercase,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Uppercase,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Uppercase,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Uppercase,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Uppercase,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Uppercase,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        fontFamily = FlatFont, textTransform = TextTransform.Uppercase,
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
