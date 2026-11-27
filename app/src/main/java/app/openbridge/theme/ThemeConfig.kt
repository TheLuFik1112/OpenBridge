package app.openbridge.theme

import androidx.annotation.Keep
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/**
 * A theme plugin. Everything here is data: the UI reads geometry, font choice and
 * both palettes at runtime, so an imported JSON/OBP file can change the whole look.
 */
@Keep
@Serializable
data class ThemeConfig(
    val id: String,
    val name: String,
    val author: String = "Unknown",
    val version: String = "1.0",
    val controlsStyle: String = STYLE_FLAT,
    val cornerRadiusDp: Int = 0,
    val borderWidthDp: Int = 1,
    val font: String = FONT_MONO,
    val dark: ThemePalette,
    val light: ThemePalette,
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

const val STYLE_FLAT = "flat"
const val STYLE_MATERIAL3 = "material3"
const val FONT_MONO = "mono"
const val FONT_SANS = "sans"

val THEME_STYLES = setOf(STYLE_FLAT, STYLE_MATERIAL3)
val THEME_FONTS = setOf(FONT_MONO, FONT_SANS)

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
