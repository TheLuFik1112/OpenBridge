package app.openbridge.plugins

import android.content.Context
import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import java.io.File

/**
 * `manifest.json` inside a script plugin package. The fields are intentionally
 * small: a plugin only needs an id, a name and the JavaScript entry point.
 */
@Keep
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "Unknown",
    val version: String = "1.0",
    val entry: String = DEFAULT_ENTRY,
    val permissions: List<String> = emptyList(),
)

/** An installed script plugin on disk. */
data class ScriptPlugin(
    val manifest: PluginManifest,
    val dir: File,
    val enabled: Boolean,
)

const val DEFAULT_ENTRY = "main.js"

const val PERMISSION_OPEN_URL = "open_url"
const val PERMISSION_CLIPBOARD = "clipboard"

/** Permissions the sandbox understands. Unknown names are ignored. */
val KNOWN_PLUGIN_PERMISSIONS = setOf(
    PERMISSION_OPEN_URL,
    PERMISSION_CLIPBOARD,
    "toast",
    "log",
)

const val SCRIPTS_DIR = "plugins/scripts"

/** `filesDir/plugins/scripts` — one folder per installed script plugin. */
fun scriptsRoot(context: Context): File = File(context.filesDir, SCRIPTS_DIR)

/** `filesDir/plugins/scripts/<id>` — holds `manifest.json` and the entry file. */
fun scriptDir(context: Context, id: String): File = File(scriptsRoot(context), id)
