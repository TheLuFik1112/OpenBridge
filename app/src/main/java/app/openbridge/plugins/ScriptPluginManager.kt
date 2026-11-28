package app.openbridge.plugins

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class ScriptPluginManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("openbridge.plugins", Context.MODE_PRIVATE)

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

    fun loadInstalled(): List<ScriptPlugin> {
        val root = runCatching { scriptsRoot(appContext) }.getOrNull() ?: return emptyList()
        return runCatching { root.listFiles()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { readPlugin(it) }
            .distinctBy { it.manifest.id }
            .toList()
    }

    fun enabledPlugins(): List<ScriptPlugin> = loadInstalled().filter { it.enabled }

    fun importFromUri(uri: Uri): Result<ScriptPlugin> = runCatching {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use {
            readLimited(it, MAX_PACKAGE_BYTES)
        } ?: throw IllegalArgumentException("Cannot read the selected file")

        if (!looksLikeZip(bytes)) {
            throw IllegalArgumentException("A plugin must be a .obp ZIP archive")
        }

        val manifestBytes = extractEntry(bytes, MANIFEST_FILE)
            ?: throw IllegalArgumentException("The package has no manifest.json")
        val manifestText = manifestBytes.toString(Charsets.UTF_8)
            .trim()
            .removePrefix("\uFEFF")
            .trim()
        if (manifestText.isEmpty()) throw IllegalArgumentException("manifest.json is empty")

        val manifest = validate(parseManifest(manifestText))
        val entryBytes = extractEntry(bytes, manifest.entry)
            ?: throw IllegalArgumentException("The package has no ${manifest.entry}")

        save(manifest, entryBytes)
        val plugin = readPlugin(scriptDir(appContext, manifest.id))
            ?: throw IllegalStateException("The plugin was saved but cannot be read")
        plugin
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        if (!ID_PATTERN.matches(id)) return false
        if (!scriptDir(appContext, id).isDirectory) return false
        prefs.edit().putBoolean(enabledKey(id), enabled).apply()
        return true
    }

    fun deletePlugin(id: String): Boolean {
        if (!ID_PATTERN.matches(id)) return false
        val dir = scriptDir(appContext, id)
        val removed = runCatching { dir.exists() && dir.deleteRecursively() }.getOrDefault(false)
        prefs.edit().remove(enabledKey(id)).apply()
        return removed
    }

    fun isEnabled(id: String): Boolean = prefs.getBoolean(enabledKey(id), true)

    private fun readPlugin(dir: File): ScriptPlugin? = runCatching {
        val manifestFile = File(dir, MANIFEST_FILE)
        if (!manifestFile.isFile || manifestFile.length() > MAX_MANIFEST_BYTES) return null
        val text = manifestFile.inputStream().use { readLimited(it, MAX_MANIFEST_BYTES) }
            .toString(Charsets.UTF_8)
        val manifest = validate(parseManifest(text))
        if (manifest.id != dir.name) return null
        if (!File(dir, manifest.entry).isFile) return null
        ScriptPlugin(manifest = manifest, dir = dir, enabled = isEnabled(manifest.id))
    }.getOrNull()

    private fun save(manifest: PluginManifest, entryBytes: ByteArray) {
        val root = scriptsRoot(appContext).apply { mkdirs() }
        val target = scriptDir(appContext, manifest.id)
        val temp = File(root, ".${manifest.id}.tmp")
        temp.deleteRecursively()
        temp.mkdirs()

        val body = storeJson.encodeToString(PluginManifest.serializer(), manifest)
        File(temp, MANIFEST_FILE).writeText(body, Charsets.UTF_8)
        File(temp, manifest.entry).writeBytes(entryBytes)

        target.deleteRecursively()
        if (!temp.renameTo(target)) {
            temp.copyRecursively(target, overwrite = true)
            temp.deleteRecursively()
        }

        if (!prefs.contains(enabledKey(manifest.id))) {
            prefs.edit().putBoolean(enabledKey(manifest.id), true).apply()
        }
    }

    private fun parseManifest(text: String): PluginManifest = runCatching {
        parseJson.decodeFromString(PluginManifest.serializer(), text)
    }.getOrElse {
        throw IllegalArgumentException("Invalid manifest.json: ${it.message}")
    }

    private fun validate(raw: PluginManifest): PluginManifest {
        val id = raw.id.trim()
        require(ID_PATTERN.matches(id)) {
            "id must be 1–64 characters: letters, digits, dot, dash or underscore"
        }

        val name = raw.name.trim().take(MAX_NAME)
        require(name.isNotEmpty()) { "name is required" }

        val entry = raw.entry.trim().ifBlank { DEFAULT_ENTRY }
        require(ENTRY_PATTERN.matches(entry)) {
            "entry must be a .js file name, for example main.js"
        }

        val permissions = raw.permissions
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it in KNOWN_PLUGIN_PERMISSIONS }
            .distinct()
            .take(MAX_PERMISSIONS)
            .toList()

        return raw.copy(
            id = id,
            name = name,
            description = raw.description.trim().take(MAX_DESCRIPTION),
            author = raw.author.trim().take(MAX_AUTHOR).ifBlank { "Unknown" },
            version = raw.version.trim().take(MAX_VERSION).ifBlank { "1.0" },
            entry = entry,
            permissions = permissions,
        )
    }

    private fun extractEntry(bytes: ByteArray, fileName: String): ByteArray? {
        val wanted = fileName.lowercase()
        var entries = 0
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ZIP_ENTRIES) {
                        throw IllegalArgumentException("The package has too many entries")
                    }
                    if (!entry.isDirectory && entry.name.substringAfterLast('/').lowercase() == wanted) {
                        val limit = if (wanted == MANIFEST_FILE) MAX_MANIFEST_BYTES else MAX_ENTRY_BYTES
                        return readLimited(zip, limit)
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

    private fun enabledKey(id: String) = "enabled_$id"

    private companion object {
        val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        val ENTRY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.js$", RegexOption.IGNORE_CASE)
        const val MANIFEST_FILE = "manifest.json"
        const val MAX_NAME = 64
        const val MAX_DESCRIPTION = 300
        const val MAX_AUTHOR = 64
        const val MAX_VERSION = 24
        const val MAX_PERMISSIONS = 16
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_ENTRY_BYTES = 1024 * 1024
        const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 2000
    }
}
