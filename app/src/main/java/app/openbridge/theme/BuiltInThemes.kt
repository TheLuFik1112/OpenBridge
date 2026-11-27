package app.openbridge.theme

/**
 * Built-in presets. Only the Flat Technical default ships inside the app;
 * every other style is a plugin file imported by the user.
 */
object BuiltInThemes {
    const val DEFAULT_ID = "default_flat"

    val DefaultFlat = ThemeConfig(
        id = DEFAULT_ID,
        name = "Flat Technical",
        author = "OpenBridge",
        version = "1.0",
        controlsStyle = STYLE_FLAT,
        cornerRadiusDp = 0,
        borderWidthDp = 1,
        font = FONT_MONO,
        dark = ThemePalette(
            background = "#0A0A0A",
            surface = "#111111",
            surfaceElevated = "#161616",
            border = "#242424",
            accent = "#C95D48",
            textPrimary = "#F2F2F2",
            textSecondary = "#8A8A8A",
        ),
        light = ThemePalette(
            background = "#F4F4F4",
            surface = "#FFFFFF",
            surfaceElevated = "#EDEDED",
            border = "#D4D4D4",
            accent = "#C95D48",
            textPrimary = "#121212",
            textSecondary = "#5C5C5C",
        ),
    )

    val all: List<ThemeConfig> = listOf(DefaultFlat)

    fun byId(id: String?): ThemeConfig? = all.firstOrNull { it.id == id }

    fun isBuiltIn(id: String?): Boolean = byId(id) != null
}
