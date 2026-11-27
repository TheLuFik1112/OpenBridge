package app.openbridge.ui

import android.content.Context
import android.graphics.Typeface
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
import app.openbridge.theme.FONT_CUSTOM
import app.openbridge.theme.FONT_SANS
import app.openbridge.theme.FONT_SERIF
import app.openbridge.theme.NAV_FLOATING
import app.openbridge.theme.ThemeConfig
import app.openbridge.theme.ThemePalette
import app.openbridge.theme.ThemeTypography
import app.openbridge.theme.parseThemeColor
import app.openbridge.theme.themeDir
import java.io.File

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

/** Theme file currently applied to the UI. Shared components read geometry from it. */
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

/** Card radius, used by cards, sheets and dialogs. */
val FlatCornerRadius: Dp
    @Composable @ReadOnlyComposable get() = LocalThemeConfig.current.shapes.cardRadiusDp.dp

/** Card outline width; 0 hides card borders. */
val FlatBorderWidth: Dp
    @Composable @ReadOnlyComposable get() = LocalThemeConfig.current.components.cardBorderWidthDp.dp

val FlatCardElevation: Dp
    @Composable @ReadOnlyComposable get() = LocalThemeConfig.current.components.cardElevationDp.dp

/** Shape for cards, sheets and anything that follows the card geometry. */
val FlatShape: Shape
    @Composable @ReadOnlyComposable get() = RoundedCornerShape(FlatCornerRadius)

val FlatButtonShape: Shape
    @Composable @ReadOnlyComposable get() =
        RoundedCornerShape(LocalThemeConfig.current.shapes.buttonRadiusDp.dp)

val FlatBadgeShape: Shape
    @Composable @ReadOnlyComposable get() =
        RoundedCornerShape(LocalThemeConfig.current.shapes.badgeRadiusDp.dp)

val IsFloatingNav: Boolean
    @Composable @ReadOnlyComposable get() =
        LocalThemeConfig.current.components.navBarStyle == NAV_FLOATING

/** Applies the theme's label casing (technical themes use ALL CAPS). */
@Composable
@ReadOnlyComposable
fun labelCase(text: String): String =
    if (LocalThemeConfig.current.typography.uppercaseLabels) text.uppercase() else text

/** Adds the active theme's border, unless the theme asks for borderless cards. */
@Composable
fun Modifier.flatBorder(
    color: Color = FlatBorder,
    shape: Shape = FlatShape,
    width: Dp = FlatBorderWidth,
): Modifier {
    return if (width > 0.dp) border(BorderStroke(width, color), shape) else this
}

private val MonoFont = FontFamily(
    Font(R.font.ioskeylemonnl_regular, FontWeight.Normal),
    Font(R.font.ioskeylemonnl_medium, FontWeight.Medium),
    Font(R.font.ioskeylemonnl_bold, FontWeight.Bold),
)

/** Fonts shipped with the app (everything except a theme's own `.ttf`/`.otf`). */
fun builtInFontFamily(typography: ThemeTypography): FontFamily = when (typography.font) {
    FONT_SANS -> FontFamily.SansSerif
    FONT_SERIF -> FontFamily.Serif
    else -> MonoFont
}

/**
 * Loads the theme font. A custom font lives in `filesDir/themes/<id>/<fontFile>`;
 * anything unreadable falls back to the platform default instead of crashing.
 */
fun resolveThemeFontFamily(context: Context, config: ThemeConfig): FontFamily {
    val typography = config.typography
    if (typography.font != FONT_CUSTOM) return builtInFontFamily(typography)

    val name = typography.fontFile ?: return FontFamily.Default
    val file = File(themeDir(context, config.id), name)
    if (!file.isFile) return FontFamily.Default

    val readable = runCatching { Typeface.createFromFile(file) }.isSuccess
    if (!readable) return FontFamily.Default

    return runCatching { FontFamily(Font(file)) }.getOrDefault(FontFamily.Default)
}

private fun buildTypography(
    typography: ThemeTypography,
    family: FontFamily,
): Typography {
    val spacing = typography.letterSpacingSp
    fun text(size: Int, weight: FontWeight, letterSpacing: Float) = TextStyle(
        fontSize = size.sp,
        fontWeight = weight,
        letterSpacing = letterSpacing.sp,
        fontFamily = family,
    )

    val bodySpacing = spacing * 0.4f

    return Typography(
        displayLarge = text(30, FontWeight.Bold, spacing),
        displayMedium = text(24, FontWeight.Bold, spacing),
        displaySmall = text(20, FontWeight.Bold, spacing),
        headlineLarge = text(24, FontWeight.Bold, spacing),
        headlineMedium = text(20, FontWeight.Bold, spacing),
        headlineSmall = text(16, FontWeight.Bold, spacing),
        titleLarge = text(18, FontWeight.Bold, spacing),
        titleMedium = text(16, FontWeight.Bold, spacing),
        titleSmall = text(14, FontWeight.Bold, spacing),
        bodyLarge = text(15, FontWeight.Medium, bodySpacing),
        bodyMedium = text(14, FontWeight.Medium, bodySpacing),
        bodySmall = text(12, FontWeight.Normal, bodySpacing),
        labelLarge = text(14, FontWeight.Bold, spacing),
        labelMedium = text(12, FontWeight.Bold, spacing),
        labelSmall = text(11, FontWeight.Bold, spacing),
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
    val radius = config.shapes.cardRadiusDp.toFloat()
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
    fontFamily: FontFamily? = null,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(mode)
    val family = fontFamily ?: builtInFontFamily(theme.typography)
    val palette = remember(theme, dark) { paletteFor(theme, dark) }
    val typography = remember(theme.typography, family) { buildTypography(theme.typography, family) }
    val shapes = remember(theme.shapes) { shapesFor(theme) }
    val colors = remember(palette, dark) { if (dark) darkColors(palette) else lightColors(palette) }

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
