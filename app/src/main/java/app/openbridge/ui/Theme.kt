package app.openbridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.openbridge.R
import app.openbridge.theme.BuiltInThemes
import app.openbridge.theme.FONT_SANS
import app.openbridge.theme.STYLE_MATERIAL3
import app.openbridge.theme.ThemeConfig
import app.openbridge.theme.ThemePalette
import app.openbridge.theme.parseThemeColor

const val THEME_DARK = "dark"
const val THEME_LIGHT = "light"
const val THEME_SYSTEM = "system"

@Immutable
data class FlatPalette(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val borderActive: Color,
    val accent: Color,
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

/** Theme plugin currently applied to the UI. Shared components read geometry from it. */
val LocalThemeConfig = staticCompositionLocalOf { BuiltInThemes.DefaultFlat }

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

val FlatCornerRadius: Dp
    @Composable @ReadOnlyComposable get() = LocalThemeConfig.current.cornerRadiusDp.dp

val FlatBorderWidth: Dp
    @Composable @ReadOnlyComposable get() = LocalThemeConfig.current.borderWidthDp.dp

val FlatShape: Shape
    @Composable @ReadOnlyComposable get() = RoundedCornerShape(FlatCornerRadius)

val IsMaterialControls: Boolean
    @Composable @ReadOnlyComposable get() =
        LocalThemeConfig.current.controlsStyle == STYLE_MATERIAL3

val FlatThemeFont: FontFamily
    @Composable @ReadOnlyComposable get() =
        if (LocalThemeConfig.current.font == FONT_SANS) FontFamily.SansSerif else MonoFont

/** Adds the plugin border, unless the active theme asks for borderless controls. */
@Composable
fun Modifier.flatBorder(
    color: Color = FlatBorder,
    shape: Shape = FlatShape,
): Modifier {
    val width = FlatBorderWidth
    return if (width > 0.dp) border(BorderStroke(width, color), shape) else this
}

private val MonoFont = FontFamily(
    Font(R.font.ioskeylemonnl_regular, FontWeight.Normal),
    Font(R.font.ioskeylemonnl_medium, FontWeight.Medium),
    Font(R.font.ioskeylemonnl_bold, FontWeight.Bold),
)

private fun buildTypography(config: ThemeConfig): Typography {
    val family = if (config.font == FONT_SANS) FontFamily.SansSerif else MonoFont
    val flat = config.controlsStyle != STYLE_MATERIAL3
    val wide = if (flat) 1.5.sp else 0.sp
    val medium = if (flat) 0.5.sp else 0.sp

    fun text(size: Int, weight: FontWeight, spacing: androidx.compose.ui.unit.TextUnit) =
        TextStyle(
            fontSize = size.sp,
            fontWeight = weight,
            letterSpacing = spacing,
            fontFamily = family,
        )

    return Typography(
        displayLarge = text(30, FontWeight.Bold, wide),
        displayMedium = text(24, FontWeight.Bold, wide),
        displaySmall = text(20, FontWeight.Bold, wide),
        headlineLarge = text(24, FontWeight.Bold, wide),
        headlineMedium = text(20, FontWeight.Bold, wide),
        headlineSmall = text(16, FontWeight.Bold, wide),
        titleLarge = text(18, FontWeight.Bold, wide),
        titleMedium = text(16, FontWeight.Bold, wide),
        titleSmall = text(14, FontWeight.Bold, wide),
        bodyLarge = text(15, FontWeight.Medium, medium),
        bodyMedium = text(14, FontWeight.Medium, medium),
        bodySmall = text(12, FontWeight.Normal, medium),
        labelLarge = text(14, FontWeight.Bold, if (flat) 1.sp else 0.sp),
        labelMedium = text(12, FontWeight.Bold, if (flat) 1.5.sp else 0.sp),
        labelSmall = text(11, FontWeight.Bold, if (flat) 1.5.sp else 0.sp),
    )
}

private fun ThemePalette.toFlatPalette(): FlatPalette {
    val background = parseThemeColor(background, Color.Black)
    val surface = parseThemeColor(surface, background)
    val elevated = parseThemeColor(surfaceElevated, surface)
    val border = parseThemeColor(border, surface)
    val accent = parseThemeColor(accent, Color(0xFFC95D48))
    val primary = parseThemeColor(textPrimary, Color.White)
    val secondary = parseThemeColor(textSecondary, primary)

    return FlatPalette(
        background = background,
        surface = surface,
        surfaceElevated = elevated,
        border = border,
        borderActive = lerp(border, primary, 0.28f),
        accent = accent,
        accentMuted = lerp(accent, secondary, 0.45f),
        error = accent,
        accentDimBg = lerp(background, accent, 0.14f),
        textPrimary = primary,
        textSecondary = secondary,
        textMuted = lerp(background, secondary, 0.55f),
    )
}

private fun paletteFor(config: ThemeConfig, dark: Boolean): FlatPalette =
    (if (dark) config.dark else config.light).toFlatPalette()

private fun shapesFor(config: ThemeConfig): Shapes {
    val radius = config.cornerRadiusDp.toFloat()
    fun shape(factor: Float) = RoundedCornerShape((radius * factor).dp)
    return Shapes(
        extraSmall = shape(0.35f),
        small = shape(0.6f),
        medium = shape(1f),
        large = shape(1.2f),
        extraLarge = shape(1.5f),
    )
}

private fun darkColors(palette: FlatPalette) = darkColorScheme(
    primary = palette.accent,
    onPrimary = palette.background,
    primaryContainer = palette.accentDimBg,
    onPrimaryContainer = palette.accent,
    secondary = palette.borderActive,
    onSecondary = palette.textPrimary,
    secondaryContainer = palette.surfaceElevated,
    onSecondaryContainer = palette.textPrimary,
    tertiary = palette.borderActive,
    onTertiary = palette.textPrimary,
    tertiaryContainer = palette.surfaceElevated,
    onTertiaryContainer = palette.textPrimary,
    background = palette.background,
    onBackground = palette.textPrimary,
    surface = palette.surface,
    onSurface = palette.textPrimary,
    surfaceVariant = palette.surfaceElevated,
    onSurfaceVariant = palette.textSecondary,
    surfaceContainerLowest = palette.surface,
    surfaceContainerLow = lerp(palette.background, palette.surface, 0.5f),
    surfaceContainer = palette.surface,
    surfaceContainerHigh = palette.surfaceElevated,
    surfaceContainerHighest = lerp(palette.surfaceElevated, palette.textPrimary, 0.06f),
    surfaceTint = palette.accent,
    outline = palette.border,
    outlineVariant = palette.border,
    error = palette.error,
    onError = palette.background,
    errorContainer = palette.accentDimBg,
    onErrorContainer = palette.accent,
)

private fun lightColors(palette: FlatPalette) = lightColorScheme(
    primary = palette.accent,
    onPrimary = palette.surface,
    primaryContainer = palette.accentDimBg,
    onPrimaryContainer = palette.accent,
    secondary = palette.borderActive,
    onSecondary = palette.textPrimary,
    secondaryContainer = palette.surfaceElevated,
    onSecondaryContainer = palette.textPrimary,
    tertiary = palette.borderActive,
    onTertiary = palette.textPrimary,
    tertiaryContainer = palette.surfaceElevated,
    onTertiaryContainer = palette.textPrimary,
    background = palette.background,
    onBackground = palette.textPrimary,
    surface = palette.surface,
    onSurface = palette.textPrimary,
    surfaceVariant = palette.surfaceElevated,
    onSurfaceVariant = palette.textSecondary,
    surfaceContainerLowest = palette.surface,
    surfaceContainerLow = palette.background,
    surfaceContainer = palette.surface,
    surfaceContainerHigh = palette.surfaceElevated,
    surfaceContainerHighest = lerp(palette.surfaceElevated, palette.textPrimary, 0.06f),
    surfaceTint = palette.accent,
    outline = palette.border,
    outlineVariant = palette.border,
    error = palette.error,
    onError = palette.surface,
    errorContainer = palette.accentDimBg,
    onErrorContainer = palette.error,
)

@Composable
fun isDarkTheme(mode: String): Boolean = when (mode) {
    THEME_LIGHT -> false
    THEME_SYSTEM -> isSystemInDarkTheme()
    else -> true
}

@Composable
fun OpenBridgeTheme(
    mode: String = THEME_SYSTEM,
    theme: ThemeConfig = BuiltInThemes.DefaultFlat,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(mode)
    val palette = remember(theme, dark) { paletteFor(theme, dark) }
    val typography = remember(theme.font, theme.controlsStyle) { buildTypography(theme) }
    val shapes = remember(theme.cornerRadiusDp) { shapesFor(theme) }
    val colors = remember(palette) { if (dark) darkColors(palette) else lightColors(palette) }

    CompositionLocalProvider(
        LocalFlatPalette provides palette,
        LocalThemeConfig provides theme,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}
