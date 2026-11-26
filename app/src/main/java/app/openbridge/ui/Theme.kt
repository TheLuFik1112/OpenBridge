package app.openbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
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
// Две палитры с одинаковыми ролями: экраны берут цвета только отсюда,
// поэтому светлая тема переключается целиком, без тёмных закоулков.

@Immutable
data class FlatPalette(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val borderActive: Color,
    /** Только интерактив: активная вкладка, включённый переключатель, главная кнопка. */
    val accent: Color,
    /** Приглушённый акцент для микрозаголовков разделов. */
    val accentMuted: Color,
    val error: Color,
    val accentDimBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
)

val FlatDarkPalette = FlatPalette(
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF111111),
    surfaceElevated = Color(0xFF161616),
    border = Color(0xFF242424),
    borderActive = Color(0xFF3A3A3A),
    // Матовая терракота вместо неоново-красного: спокойнее на OLED.
    accent = Color(0xFFC95D48),
    accentMuted = Color(0xFF9E6B60),
    error = Color(0xFFD9705C),
    accentDimBg = Color(0xFF181211),
    textPrimary = Color(0xFFF2F2F2),
    textSecondary = Color(0xFF8A8A8A),
    textMuted = Color(0xFF555555),
)

val FlatLightPalette = FlatPalette(
    background = Color(0xFFF4F4F4),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFEDEDED),
    border = Color(0xFFD4D4D4),
    borderActive = Color(0xFFB0B0B0),
    accent = Color(0xFFC95D48),
    accentMuted = Color(0xFF9E6B60),
    error = Color(0xFFB94A38),
    accentDimBg = Color(0xFFF3E7E3),
    textPrimary = Color(0xFF121212),
    textSecondary = Color(0xFF5C5C5C),
    textMuted = Color(0xFF9A9A9A),
)

val LocalFlatPalette = staticCompositionLocalOf { FlatDarkPalette }

val FlatBackground: Color
    @Composable @ReadOnlyComposable get() = LocalFlatPalette.current.background

val FlatSurface: Color
    @Composable @ReadOnlyComposable get() = LocalFlatPalette.current.surface

val FlatSurfaceElevated: Color
    @Composable @ReadOnlyComposable get() = LocalFlatPalette.current.surfaceElevated

val FlatBorder: Color
    @Composable @ReadOnlyComposable get() = LocalFlatPalette.current.border

val FlatBorderActive: Color
    @Composable @ReadOnlyComposable get() = LocalFlatPalette.current.borderActive

val FlatAccent: Color
    @Composable @ReadOnlyComposable get() = LocalFlatPalette.current.accent

val FlatAccentMuted: Color
    @Composable @ReadOnlyComposable get() = LocalFlatPalette.current.accentMuted

val FlatError: Color
    @Composable @ReadOnlyComposable get() = LocalFlatPalette.current.error

val FlatAccentDimBg: Color
    @Composable @ReadOnlyComposable get() = LocalFlatPalette.current.accentDimBg

val FlatTextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalFlatPalette.current.textPrimary

val FlatTextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalFlatPalette.current.textSecondary

val FlatTextMuted: Color
    @Composable @ReadOnlyComposable get() = LocalFlatPalette.current.textMuted

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
    primary = FlatDarkPalette.accent,
    onPrimary = FlatDarkPalette.background,
    primaryContainer = FlatDarkPalette.accentDimBg,
    onPrimaryContainer = FlatDarkPalette.accent,
    secondary = FlatDarkPalette.borderActive,
    onSecondary = FlatDarkPalette.textPrimary,
    secondaryContainer = FlatDarkPalette.surfaceElevated,
    onSecondaryContainer = FlatDarkPalette.textPrimary,
    tertiary = FlatDarkPalette.borderActive,
    onTertiary = FlatDarkPalette.textPrimary,
    tertiaryContainer = FlatDarkPalette.surfaceElevated,
    onTertiaryContainer = FlatDarkPalette.textPrimary,
    background = FlatDarkPalette.background,
    onBackground = FlatDarkPalette.textPrimary,
    surface = FlatDarkPalette.surface,
    onSurface = FlatDarkPalette.textPrimary,
    surfaceVariant = FlatDarkPalette.surfaceElevated,
    onSurfaceVariant = FlatDarkPalette.textSecondary,
    surfaceContainerLowest = FlatDarkPalette.surface,
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = FlatDarkPalette.surface,
    surfaceContainerHigh = FlatDarkPalette.surfaceElevated,
    surfaceContainerHighest = Color(0xFF1C1C1C),
    surfaceTint = FlatDarkPalette.accent,
    outline = FlatDarkPalette.border,
    outlineVariant = FlatDarkPalette.border,
    error = FlatDarkPalette.accent,
    onError = FlatDarkPalette.background,
    errorContainer = FlatDarkPalette.accentDimBg,
    onErrorContainer = FlatDarkPalette.accent,
)

private val FlatLightColors = lightColorScheme(
    primary = FlatLightPalette.accent,
    onPrimary = FlatLightPalette.surface,
    primaryContainer = FlatLightPalette.accentDimBg,
    onPrimaryContainer = FlatLightPalette.accent,
    secondary = FlatLightPalette.borderActive,
    onSecondary = FlatLightPalette.textPrimary,
    secondaryContainer = FlatLightPalette.surfaceElevated,
    onSecondaryContainer = FlatLightPalette.textPrimary,
    tertiary = FlatLightPalette.borderActive,
    onTertiary = FlatLightPalette.textPrimary,
    tertiaryContainer = FlatLightPalette.surfaceElevated,
    onTertiaryContainer = FlatLightPalette.textPrimary,
    background = FlatLightPalette.background,
    onBackground = FlatLightPalette.textPrimary,
    surface = FlatLightPalette.surface,
    onSurface = FlatLightPalette.textPrimary,
    surfaceVariant = FlatLightPalette.surfaceElevated,
    onSurfaceVariant = FlatLightPalette.textSecondary,
    surfaceContainerLowest = FlatLightPalette.surface,
    surfaceContainerLow = FlatLightPalette.background,
    surfaceContainer = FlatLightPalette.surface,
    surfaceContainerHigh = FlatLightPalette.surfaceElevated,
    surfaceContainerHighest = Color(0xFFE3E3E3),
    surfaceTint = FlatLightPalette.accent,
    outline = FlatLightPalette.border,
    outlineVariant = FlatLightPalette.border,
    error = FlatLightPalette.error,
    onError = FlatLightPalette.surface,
    errorContainer = FlatLightPalette.accentDimBg,
    onErrorContainer = FlatLightPalette.error,
)

@Composable
fun isDarkTheme(mode: String): Boolean = when (mode) {
    THEME_LIGHT -> false
    THEME_SYSTEM -> isSystemInDarkTheme()
    else -> true
}

/**
 * Flat technical UI: deep matte black surfaces, 1.dp outlines, red-orange accent.
 * Светлая тема — та же геометрия, но светлые поверхности и тёмный текст.
 */
@Composable
fun OpenBridgeTheme(mode: String = THEME_SYSTEM, content: @Composable () -> Unit) {
    val dark = isDarkTheme(mode)
    CompositionLocalProvider(
        LocalFlatPalette provides if (dark) FlatDarkPalette else FlatLightPalette,
    ) {
        MaterialTheme(
            colorScheme = if (dark) FlatDarkColors else FlatLightColors,
            typography = FlatTypography,
            content = content,
        )
    }
}
