package app.openbridge.theme

/**
 * Built-in presets. They use exactly the same [ThemeConfig] schema as imported
 * plugins, so the plugin engine can be tested by switching between them.
 */
object BuiltInThemes {
    const val DEFAULT_ID = "default_flat"
    const val MATERIAL3_ID = "builtin_m3"

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

    val Material3 = ThemeConfig(
        id = MATERIAL3_ID,
        name = "Material 3",
        author = "OpenBridge",
        version = "1.0",
        controlsStyle = STYLE_MATERIAL3,
        cornerRadiusDp = 20,
        borderWidthDp = 0,
        font = FONT_SANS,
        dark = ThemePalette(
            background = "#101014",
            surface = "#1C1B1F",
            surfaceElevated = "#2B2930",
            border = "#49454F",
            accent = "#D0BCFF",
            textPrimary = "#E6E1E5",
            textSecondary = "#CAC4D0",
        ),
        light = ThemePalette(
            background = "#FEF7FF",
            surface = "#FFFFFF",
            surfaceElevated = "#F3EDF7",
            border = "#CAC4D0",
            accent = "#6750A4",
            textPrimary = "#1D1B20",
            textSecondary = "#49454F",
        ),
    )

    val all: List<ThemeConfig> = listOf(DefaultFlat, Material3)

    fun byId(id: String?): ThemeConfig? = all.firstOrNull { it.id == id }

    fun isBuiltIn(id: String?): Boolean = byId(id) != null
}
