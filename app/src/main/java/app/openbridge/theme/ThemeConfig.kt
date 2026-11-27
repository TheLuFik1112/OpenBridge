package app.openbridge.theme

import androidx.annotation.Keep
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/**
 * A theme file. Everything the UI draws is described by data: shapes, component
 * variants, typography and both palettes. Old files that only had `controlsStyle`
 * are converted by [legacyToThemeConfig] and keep working.
 */
@Keep
@Serializable
data class ThemeConfig(
    val id: String,
    val name: String,
    val author: String = "Unknown",
    val version: String = "1.0",
    val shapes: ThemeShapes = ThemeShapes(),
    val components: ThemeComponents = ThemeComponents(),
    val typography: ThemeTypography = ThemeTypography(),
    val dark: ThemePalette,
    val light: ThemePalette,
)

@Keep
@Serializable
data class ThemeShapes(
    val cardRadiusDp: Int = 0,
    val buttonRadiusDp: Int = 0,
    val badgeRadiusDp: Int = 0,
)

@Keep
@Serializable
data class ThemeComponents(
    val buttonStyle: String = BUTTON_FILLED,
    val switchStyle: String = SWITCH_FLAT,
    val navBarStyle: String = NAV_TOP_LINE,
    val cardBorderWidthDp: Int = 1,
    val cardElevationDp: Int = 0,
)

@Keep
@Serializable
data class ThemeTypography(
    val font: String = FONT_MONO,
    /** File name inside the `.obp` archive, used only when [font] is [FONT_CUSTOM]. */
    val fontFile: String? = null,
    val uppercaseLabels: Boolean = true,
    val letterSpacingSp: Float = 1.2f,
)

@Keep
@Serializable
data class ThemePalette(
    val background: String,
    val surface: String,
    val surfaceElevated: String,
    val border: String,
    val accent: String,
    val textPrimary: String,
    val textSecondary: String,
)

const val BUTTON_FILLED = "filled"
const val BUTTON_OUTLINED = "outlined"
const val BUTTON_TONAL = "tonal"

const val SWITCH_FLAT = "flat"
const val SWITCH_PILL = "pill"
const val SWITCH_IOS = "ios"

const val NAV_TOP_LINE = "top_line"
const val NAV_PILL = "pill"
const val NAV_FLOATING = "floating"
const val NAV_MINIMAL = "minimal"

const val FONT_MONO = "mono"
const val FONT_SANS = "sans"
const val FONT_SERIF = "serif"
const val FONT_CUSTOM = "custom"

val THEME_BUTTON_STYLES = setOf(BUTTON_FILLED, BUTTON_OUTLINED, BUTTON_TONAL)
val THEME_SWITCH_STYLES = setOf(SWITCH_FLAT, SWITCH_PILL, SWITCH_IOS)
val THEME_NAV_STYLES = setOf(NAV_TOP_LINE, NAV_PILL, NAV_FLOATING, NAV_MINIMAL)
val THEME_FONTS = setOf(FONT_MONO, FONT_SANS, FONT_SERIF, FONT_CUSTOM)

const val MIN_CARD_RADIUS = 0
const val MAX_CARD_RADIUS = 28
const val MAX_BUTTON_RADIUS = 50
const val MAX_BADGE_RADIUS = 28
const val MAX_CARD_BORDER = 3
const val MAX_CARD_ELEVATION = 8
const val MAX_LETTER_SPACING = 4f

/** `theme.ttf`, `my-font.otf` — letters, digits, dot, dash and underscore only. */
val FONT_FILE_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.(ttf|otf)$", RegexOption.IGNORE_CASE)

private val HEX_COLOR = Regex("^#?([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

/** Returns a `#RRGGBB` / `#AARRGGBB` string, or null when the value is not a hex color. */
fun normalizeHexColor(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (!HEX_COLOR.matches(raw)) return null
    return "#" + raw.removePrefix("#").uppercase()
}

/** Parses a validated hex color. Unknown values fall back to [fallback]. */
fun parseThemeColor(value: String, fallback: Color = Color.Transparent): Color {
    val hex = normalizeHexColor(value)?.removePrefix("#") ?: return fallback
    val packed = hex.toLongOrNull(16) ?: return fallback
    val argb = if (hex.length == 6) 0xFF000000L or packed else packed
    return Color(argb.toInt())
}

fun ThemePalette.isValid(): Boolean =
    listOf(
        background,
        surface,
        surfaceElevated,
        border,
        accent,
        textPrimary,
        textSecondary,
    ).all { normalizeHexColor(it) != null }

fun ThemePalette.normalized(): ThemePalette = ThemePalette(
    background = normalizeHexColor(background) ?: background,
    surface = normalizeHexColor(surface) ?: surface,
    surfaceElevated = normalizeHexColor(surfaceElevated) ?: surfaceElevated,
    border = normalizeHexColor(border) ?: border,
    accent = normalizeHexColor(accent) ?: accent,
    textPrimary = normalizeHexColor(textPrimary) ?: textPrimary,
    textSecondary = normalizeHexColor(textSecondary) ?: textSecondary,
)

// --- v1 compatibility -------------------------------------------------------

/** Schema used before the granular theme system; kept only for migration. */
@Keep
@Serializable
internal data class LegacyThemeConfig(
    val id: String,
    val name: String,
    val author: String = "Unknown",
    val version: String = "1.0",
    val controlsStyle: String = "flat",
    val cornerRadiusDp: Int = 0,
    val borderWidthDp: Int = 1,
    val font: String = FONT_MONO,
    val dark: ThemePalette,
    val light: ThemePalette,
)

internal const val LEGACY_STYLE_MATERIAL3 = "material3"

internal fun legacyToThemeConfig(legacy: LegacyThemeConfig): ThemeConfig {
    val material = legacy.controlsStyle.equals(LEGACY_STYLE_MATERIAL3, ignoreCase = true)
    val radius = legacy.cornerRadiusDp.coerceIn(MIN_CARD_RADIUS, MAX_CARD_RADIUS)
    val border = legacy.borderWidthDp.coerceIn(0, MAX_CARD_BORDER)
    val legacyFont = when (legacy.font.trim().lowercase()) {
        FONT_SANS -> FONT_SANS
        FONT_MONO -> FONT_MONO
        else -> FONT_MONO
    }

    return ThemeConfig(
        id = legacy.id,
        name = legacy.name,
        author = legacy.author,
        version = legacy.version,
        shapes = ThemeShapes(
            cardRadiusDp = radius,
            buttonRadiusDp = radius,
            badgeRadiusDp = if (material) 8 else radius,
        ),
        components = ThemeComponents(
            buttonStyle = BUTTON_FILLED,
            switchStyle = if (material) SWITCH_PILL else SWITCH_FLAT,
            navBarStyle = if (material) NAV_PILL else NAV_TOP_LINE,
            cardBorderWidthDp = border,
            cardElevationDp = 0,
        ),
        typography = ThemeTypography(
            font = legacyFont,
            fontFile = null,
            uppercaseLabels = !material,
            letterSpacingSp = if (material) 0f else 1.2f,
        ),
        dark = legacy.dark,
        light = legacy.light,
    )
}
