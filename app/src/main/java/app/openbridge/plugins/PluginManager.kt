package app.openbridge.plugins

import android.content.Context
import android.net.Uri
import app.openbridge.theme.BuiltInThemes
import app.openbridge.theme.FONT_MONO
import app.openbridge.theme.FONT_SANS
import app.openbridge.theme.STYLE_FLAT
import app.openbridge.theme.STYLE_MATERIAL3
import app.openbridge.theme.THEME_FONTS
import app.openbridge.theme.THEME_STYLES
import app.openbridge.theme.ThemeConfig
import app.openbridge.theme.isValid
import app.openbridge.theme.normalized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Stores imported theme plugins as normalised JSON under
 * `filesDir/plugins/themes/<id>.json` and validates them before they reach the UI.
 */
class PluginManager(context: Context) {
    private val appContext = context.applicationContext
    private val themesDir = File(appContext.filesDir, "plugins/themes")

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
        val imported = runCatching {
            themesDir.listFiles()?.toList().orEmpty()
        }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { file ->
                runCatching {
                    if (file.length() > MAX_THEME_BYTES) return@runCatching null
                    val text = file.inputStream().use { readLimited(it, MAX_THEME_BYTES) }
                        .toString(Charsets.UTF_8)
                    validate(parseTheme(text))
                }.getOrNull()
            }
            .filterNot { BuiltInThemes.isBuiltIn(it.id) }
            .distinctBy { it.id }
            .toList()

        return BuiltInThemes.all + imported
    }

    fun importThemeFromUri(uri: Uri): Result<ThemeConfig> = runCatching {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use {
            readLimited(it, MAX_SOURCE_BYTES)
        } ?: throw IllegalArgumentException("Cannot read the selected file")

        val payload = if (looksLikeZip(bytes)) {
            extractThemeFromZip(bytes)
        } else {
            if (bytes.size > MAX_THEME_BYTES) {
                throw IllegalArgumentException("The theme file is too large")
            }
            bytes
        }
        val text = payload.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw IllegalArgumentException("The theme file is empty")

        val theme = validate(parseTheme(text))
        save(theme)
        theme
    }

    fun deleteTheme(id: String): Boolean {
        if (BuiltInThemes.isBuiltIn(id)) return false
        val file = File(themesDir, "$id.json")
        return runCatching { file.exists() && file.delete() }.getOrDefault(false)
    }

    fun isBuiltIn(id: String?): Boolean = BuiltInThemes.isBuiltIn(id)

    private fun save(theme: ThemeConfig) {
        themesDir.mkdirs()
        val target = File(themesDir, "${theme.id}.json")
        val temp = File(themesDir, "${theme.id}.json.tmp")
        val body = storeJson.encodeToString(ThemeConfig.serializer(), theme)
        temp.writeText(body, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(body, Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun parseTheme(text: String): ThemeConfig {
        val element = runCatching { parseJson.parseToJsonElement(text) }
            .getOrElse { throw IllegalArgumentException("Invalid JSON: ${it.message}") }
        val root = element as? JsonObject
            ?: throw IllegalArgumentException("The theme file must contain a JSON object")
        return parseJson.decodeFromJsonElement(
            ThemeConfig.serializer(),
            (root["theme"] as? JsonObject) ?: root,
        )
    }

    private fun validate(raw: ThemeConfig): ThemeConfig {
        val id = raw.id.trim()
        require(ID_PATTERN.matches(id)) {
            "id must be 1–64 characters: letters, digits, dot, dash or underscore"
        }
        require(!BuiltInThemes.isBuiltIn(id)) { "id \"$id\" is reserved by a built-in theme" }

        val name = raw.name.trim().take(MAX_NAME)
        require(name.isNotEmpty()) { "name is required" }

        val style = raw.controlsStyle.trim().lowercase()
        require(style in THEME_STYLES) {
            "controlsStyle must be \"$STYLE_FLAT\" or \"$STYLE_MATERIAL3\""
        }

        val font = raw.font.trim().lowercase()
        require(font in THEME_FONTS) {
            "font must be \"${FONT_MONO}\" or \"${FONT_SANS}\""
        }

        require(raw.cornerRadiusDp in 0..MAX_CORNER_RADIUS) {
            "cornerRadiusDp must be between 0 and $MAX_CORNER_RADIUS"
        }
        require(raw.borderWidthDp in 0..MAX_BORDER_WIDTH) {
            "borderWidthDp must be between 0 and $MAX_BORDER_WIDTH"
        }
        require(raw.dark.isValid()) { "dark palette contains an invalid hex color" }
        require(raw.light.isValid()) { "light palette contains an invalid hex color" }

        return raw.copy(
            id = id,
            name = name,
            author = raw.author.trim().take(MAX_NAME).ifBlank { "Unknown" },
            version = raw.version.trim().take(MAX_VERSION).ifBlank { "1.0" },
            controlsStyle = style,
            font = font,
            dark = raw.dark.normalized(),
            light = raw.light.normalized(),
        )
    }

    private fun extractThemeFromZip(bytes: ByteArray): ByteArray {
        var theme: ByteArray? = null
        var manifest: ByteArray? = null
        var total = 0
        var entries = 0

        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ZIP_ENTRIES) {
                        throw IllegalArgumentException("The package has too many entries")
                    }
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfterLast('/').lowercase()
                        if (name == "theme.json" || name == "manifest.json") {
                            val data = readLimited(zip, MAX_THEME_BYTES)
                            total += data.size
                            if (total > MAX_ZIP_BYTES) {
                                throw IllegalArgumentException("The package is too large")
                            }
                            if (name == "theme.json" && theme == null) {
                                theme = data
                            } else if (name == "manifest.json" && manifest == null) {
                                manifest = data
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }.onFailure {
            throw IllegalArgumentException("Invalid .obp package: ${it.message}")
        }

        return theme ?: manifest
            ?: throw IllegalArgumentException("The package has no theme.json")
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
        const val MAX_NAME = 64
        const val MAX_VERSION = 24
        const val MAX_CORNER_RADIUS = 48
        const val MAX_BORDER_WIDTH = 8
        const val MAX_THEME_BYTES = 512 * 1024
        const val MAX_ZIP_BYTES = 2 * 1024 * 1024
        const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 2000
    }
}
