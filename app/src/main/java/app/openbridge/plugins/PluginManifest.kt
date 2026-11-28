package app.openbridge.plugins

import android.content.Context
import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import java.io.File

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

data class ScriptPlugin(
    val manifest: PluginManifest,
    val dir: File,
    val enabled: Boolean,
)

const val DEFAULT_ENTRY = "main.js"

const val PERMISSION_OPEN_URL = "open_url"
const val PERMISSION_CLIPBOARD = "clipboard"
const val PERMISSION_EFFECTS = "effects"
const val PERMISSION_VIBRATE = "vibrate"
const val PERMISSION_UI = "ui"

val KNOWN_PLUGIN_PERMISSIONS = setOf(
    PERMISSION_OPEN_URL,
    PERMISSION_CLIPBOARD,
    PERMISSION_EFFECTS,
    PERMISSION_VIBRATE,
    PERMISSION_UI,
    "toast",
    "log",
)

const val SCRIPTS_DIR = "plugins/scripts"

fun scriptsRoot(context: Context): File = File(context.filesDir, SCRIPTS_DIR)

fun scriptDir(context: Context, id: String): File = File(scriptsRoot(context), id)
