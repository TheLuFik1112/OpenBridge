package app.openbridge.plugins

import android.content.Context
import android.net.Uri
import app.openbridge.theme.BuiltInThemes
import app.openbridge.theme.FONT_CUSTOM
import app.openbridge.theme.FONT_FILE_PATTERN
import app.openbridge.theme.LEGACY_THEMES_DIR
import app.openbridge.theme.LegacyThemeConfig
import app.openbridge.theme.MAX_BADGE_RADIUS
import app.openbridge.theme.MAX_BUTTON_RADIUS
import app.openbridge.theme.MAX_CARD_BORDER
import app.openbridge.theme.MAX_CARD_ELEVATION
import app.openbridge.theme.MAX_CARD_RADIUS
import app.openbridge.theme.MAX_LETTER_SPACING
import app.openbridge.theme.THEME_BUTTON_STYLES
import app.openbridge.theme.THEME_FONTS
import app.openbridge.theme.THEME_NAV_STYLES
import app.openbridge.theme.THEME_SWITCH_STYLES
import app.openbridge.theme.ThemeConfig
import app.openbridge.theme.isValid
import app.openbridge.theme.legacyToThemeConfig
import app.openbridge.theme.normalized
import app.openbridge.theme.themeDir
import app.openbridge.theme.themesRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Stores imported themes as `filesDir/themes/<id>/theme.json` plus an optional
 * bundled `.ttf`/`.otf` file, and validates them before they reach the UI.
 */
class PluginManager(context: Context) {
    private val appContext = context.applicationContext

    private val parseJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val storeJson = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun builtInThemes(): List<ThemeConfig> = BuiltInThemes.all

    fun loadInstalledThemes(): List<ThemeConfig> {
        migrateLegacyThemes()

        val root = runCatching { themesRoot(appContext) }.getOrNull() ?: return BuiltInThemes.all
        val imported = runCatching {
            root.listFiles()?.toList().orEmpty()
        }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { dir -> readTheme(dir) }
            .filterNot { BuiltInThemes.isBuiltIn(it.id) }
            .distinctBy { it.id }
            .toList()

        return BuiltInThemes.all + imported
    }

    fun importThemeFromUri(uri: Uri): Result<ThemeConfig> = runCatching {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use {
            readLimited(it, MAX_SOURCE_BYTES)
        } ?: throw IllegalArgumentException("Cannot read the selected file")

        val zip = looksLikeZip(bytes)
        val jsonBytes = if (zip) {
            extractEntry(bytes, THEME_FILE_NAMES)
                ?: throw IllegalArgumentException("The package has no theme.json")
        } else {
            if (bytes.size > MAX_THEME_BYTES) {
                throw IllegalArgumentException("The theme file is too large")
            }
            bytes
        }

        val text = jsonBytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw IllegalArgumentException("The theme file is empty")

        val theme = validate(parseTheme(text))
        if (!zip && theme.typography.font == FONT_CUSTOM) {
            throw IllegalArgumentException("A custom font must be packed into an .obp archive")
        }
        val fontBytes = if (zip && theme.typography.font == FONT_CUSTOM) {
            val name = theme.typography.fontFile
                ?: throw IllegalArgumentException("fontFile is required for the \"custom\" font")
            extractEntry(bytes, listOf(name))
                ?: throw IllegalArgumentException("The package is missing $name")
        } else {
            null
        }

        save(theme, fontBytes)
        theme
    }

    fun deleteTheme(id: String): Boolean {
        if (BuiltInThemes.isBuiltIn(id)) return false
        val dir = themeDir(appContext, id)
        val legacy = File(File(appContext.filesDir, LEGACY_THEMES_DIR), "$id.json")
        val removedDir = runCatching { dir.exists() && dir.deleteRecursively() }.getOrDefault(false)
        val removedLegacy = runCatching { legacy.exists() && legacy.delete() }.getOrDefault(false)
        return removedDir || removedLegacy
    }

    fun isBuiltIn(id: String?): Boolean = BuiltInThemes.isBuiltIn(id)

    private fun readTheme(dir: File): ThemeConfig? = runCatching {
        val json = File(dir, THEME_FILE)
        if (!json.isFile || json.length() > MAX_THEME_BYTES) return null
        val text = json.inputStream().use { readLimited(it, MAX_THEME_BYTES) }
            .toString(Charsets.UTF_8)
        validate(parseTheme(text))
    }.getOrNull()

    /** Moves `plugins/themes/<id>.json` files from v1 into the new folder layout. */
    private fun migrateLegacyThemes() {
        val legacyDir = File(appContext.filesDir, LEGACY_THEMES_DIR)
        val files = runCatching { legacyDir.listFiles()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
        for (file in files) {
            runCatching {
                if (file.length() > MAX_THEME_BYTES) return@runCatching
                val text = file.inputStream().use { readLimited(it, MAX_THEME_BYTES) }
                    .toString(Charsets.UTF_8)
                save(validate(parseTheme(text)), null)
                file.delete()
            }
        }
    }

    private fun save(theme: ThemeConfig, font: ByteArray?) {
        themesRoot(appContext).mkdirs()
        val target = themeDir(appContext, theme.id)
        val temp = File(themesRoot(appContext), "${theme.id}.tmp")
        temp.deleteRecursively()
        temp.mkdirs()

        val body = storeJson.encodeToString(ThemeConfig.serializer(), theme)
        File(temp, THEME_FILE).writeText(body, Charsets.UTF_8)
        if (font != null) {
            File(temp, theme.typography.fontFile!!).writeBytes(font)
        }

        target.deleteRecursively()
        if (!temp.renameTo(target)) {
            temp.copyRecursively(target, overwrite = true)
            temp.deleteRecursively()
        }
    }

    private fun parseTheme(text: String): ThemeConfig {
        val element = runCatching { parseJson.parseToJsonElement(text) }
            .getOrElse { throw IllegalArgumentException("Invalid JSON: ${it.message}") }
        val container = element as? JsonObject
            ?: throw IllegalArgumentException("The theme file must contain a JSON object")
        val root = (container["theme"] as? JsonObject) ?: container

        if (isV2(root)) {
            return runCatching {
                parseJson.decodeFromJsonElement(ThemeConfig.serializer(), root)
            }.getOrElse { throw IllegalArgumentException("Invalid theme: ${it.message}") }
        }

        return runCatching {
            legacyToThemeConfig(
                parseJson.decodeFromJsonElement(LegacyThemeConfig.serializer(), root),
            )
        }.getOrElse {
            runCatching {
                parseJson.decodeFromJsonElement(ThemeConfig.serializer(), root)
            }.getOrElse { throw IllegalArgumentException("Invalid theme: ${it.message}") }
        }
    }

    private fun isV2(root: JsonObject): Boolean =
        root.containsKey("shapes") || root.containsKey("components") || root.containsKey("typography")

    private fun validate(raw: ThemeConfig): ThemeConfig {
        val id = raw.id.trim()
        require(ID_PATTERN.matches(id)) {
            "id must be 1–64 characters: letters, digits, dot, dash or underscore"
        }
        require(!BuiltInThemes.isBuiltIn(id)) { "id \"$id\" is reserved by a built-in theme" }

        val name = raw.name.trim().take(MAX_NAME)
        require(name.isNotEmpty()) { "name is required" }

        val shapes = raw.shapes
        require(shapes.cardRadiusDp in 0..MAX_CARD_RADIUS) {
            "shapes.cardRadiusDp must be between 0 and $MAX_CARD_RADIUS"
        }
        require(shapes.buttonRadiusDp in 0..MAX_BUTTON_RADIUS) {
            "shapes.buttonRadiusDp must be between 0 and $MAX_BUTTON_RADIUS"
        }
        require(shapes.badgeRadiusDp in 0..MAX_BADGE_RADIUS) {
            "shapes.badgeRadiusDp must be between 0 and $MAX_BADGE_RADIUS"
        }

        val components = raw.components
        val buttonStyle = components.buttonStyle.trim().lowercase()
        require(buttonStyle in THEME_BUTTON_STYLES) {
            "components.buttonStyle must be one of ${THEME_BUTTON_STYLES.joinToString()}"
        }
        val switchStyle = components.switchStyle.trim().lowercase()
        require(switchStyle in THEME_SWITCH_STYLES) {
            "components.switchStyle must be one of ${THEME_SWITCH_STYLES.joinToString()}"
        }
        val navStyle = components.navBarStyle.trim().lowercase()
        require(navStyle in THEME_NAV_STYLES) {
            "components.navBarStyle must be one of ${THEME_NAV_STYLES.joinToString()}"
        }
        require(components.cardBorderWidthDp in 0..MAX_CARD_BORDER) {
            "components.cardBorderWidthDp must be between 0 and $MAX_CARD_BORDER"
        }
        require(components.cardElevationDp in 0..MAX_CARD_ELEVATION) {
            "components.cardElevationDp must be between 0 and $MAX_CARD_ELEVATION"
        }

        val typography = raw.typography
        val font = typography.font.trim().lowercase()
        require(font in THEME_FONTS) {
            "typography.font must be one of ${THEME_FONTS.joinToString()}"
        }
        val fontFile = if (font == FONT_CUSTOM) {
            val file = typography.fontFile?.trim().orEmpty()
            require(FONT_FILE_PATTERN.matches(file)) {
                "typography.fontFile must be a .ttf or .otf file name"
            }
            file
        } else {
            null
        }
        require(typography.letterSpacingSp in 0f..MAX_LETTER_SPACING) {
            "typography.letterSpacingSp must be between 0 and $MAX_LETTER_SPACING"
        }

        require(raw.dark.isValid()) { "dark palette contains an invalid hex color" }
        require(raw.light.isValid()) { "light palette contains an invalid hex color" }

        return raw.copy(
            id = id,
            name = name,
            author = raw.author.trim().take(MAX_NAME).ifBlank { "Unknown" },
            version = raw.version.trim().take(MAX_VERSION).ifBlank { "1.0" },
            shapes = shapes.copy(
                cardRadiusDp = shapes.cardRadiusDp,
                buttonRadiusDp = shapes.buttonRadiusDp,
                badgeRadiusDp = shapes.badgeRadiusDp,
            ),
            components = components.copy(
                buttonStyle = buttonStyle,
                switchStyle = switchStyle,
                navBarStyle = navStyle,
                cardBorderWidthDp = components.cardBorderWidthDp,
                cardElevationDp = components.cardElevationDp,
            ),
            typography = typography.copy(
                font = font,
                fontFile = fontFile,
                uppercaseLabels = typography.uppercaseLabels,
                letterSpacingSp = typography.letterSpacingSp,
            ),
            dark = raw.dark.normalized(),
            light = raw.light.normalized(),
        )
    }

    /** Returns the first entry whose file name matches one of [names], in order. */
    private fun extractEntry(bytes: ByteArray, names: List<String>): ByteArray? {
        val wanted = names.map { it.lowercase() }
        var result: ByteArray? = null
        for (name in wanted) {
            result = readEntry(bytes, name)
            if (result != null) break
        }
        return result
    }

    private fun readEntry(bytes: ByteArray, fileName: String): ByteArray? {
        var entries = 0
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ZIP_ENTRIES) {
                        throw IllegalArgumentException("The package has too many entries")
                    }
                    if (!entry.isDirectory && entry.name.substringAfterLast('/').lowercase() == fileName) {
                        return if (fileName.endsWith(".ttf") || fileName.endsWith(".otf")) {
                            readLimited(zip, MAX_FONT_BYTES)
                        } else {
                            readLimited(zip, MAX_THEME_BYTES)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }.onFailure {
            throw IllegalArgumentException("Invalid .obp package: ${it.message}")
        }
        return null
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte() &&
            (bytes[2] == 3.toByte() || bytes[2] == 5.toByte() || bytes[2] == 7.toByte())

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) throw IllegalArgumentException("The file is too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        val THEME_FILE_NAMES = listOf("theme.json", "manifest.json")
        const val THEME_FILE = "theme.json"
        const val MAX_NAME = 64
        const val MAX_VERSION = 24
        const val MAX_THEME_BYTES = 512 * 1024
        const val MAX_FONT_BYTES = 4 * 1024 * 1024
        const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 2000
    }
}
