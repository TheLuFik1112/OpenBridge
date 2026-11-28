package app.openbridge.plugins

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import app.openbridge.theme.BUTTON_FILLED
import app.openbridge.theme.MAX_BUTTON_RADIUS
import app.openbridge.theme.THEME_BUTTON_STYLES
import app.openbridge.theme.normalizeHexColor
import app.openbridge.theme.parseThemeColor
import org.json.JSONObject

@Keep
interface ObUiBridge {
    fun overrideButton(id: String, configJson: String)
    fun addButton(slotId: String, configJson: String)
    fun clear()
}

@Keep
class AndroidObUiBridge(
    private val pluginId: String,
    private val permissions: Set<String>,
) : ObUiBridge {
    private val main = Handler(Looper.getMainLooper())

    override fun overrideButton(id: String, configJson: String) {
        if (!allowed()) return
        val buttonId = id.trim()
        if (!ID_PATTERN.matches(buttonId)) {
            log("overrideButton: invalid button id")
            return
        }
        val config = parsePluginJson(configJson) ?: return

        val override = PluginButtonOverride(
            pluginId = pluginId,
            text = label(config),
            style = style(config),
            cornerRadiusDp = radius(config),
            color = color(config),
            hidden = config.optBoolean("hidden", false),
            onClick = config.optBoolean("onClick", false),
        )
        main.post { PluginUiRegistry.setOverride(buttonId, override) }
    }

    override fun addButton(slotId: String, configJson: String) {
        if (!allowed()) return
        val slot = slotId.trim()
        if (!ID_PATTERN.matches(slot)) {
            log("addButton: invalid slot id")
            return
        }
        val config = parsePluginJson(configJson) ?: return
        val id = config.optString("id").trim()
        if (!ID_PATTERN.matches(id)) {
            log("addButton: invalid button id")
            return
        }

        val button = PluginCustomButton(
            pluginId = pluginId,
            id = id,
            text = label(config) ?: id,
            style = style(config) ?: BUTTON_FILLED,
            color = color(config),
            cornerRadiusDp = radius(config),
            onClick = config.optBoolean("onClick", false),
        )
        main.post { PluginUiRegistry.addButton(slot, button) }
    }

    override fun clear() {
        if (!allowed()) return
        main.post { PluginUiRegistry.clearPlugin(pluginId) }
    }

    private fun allowed(): Boolean {
        if (PERMISSION_UI in permissions) return true
        log("ui: permission ui is missing")
        return false
    }

    private fun label(config: JSONObject): String? {
        if (!config.has("text") || config.isNull("text")) return null
        return config.optString("text").trim().take(MAX_LABEL).ifBlank { null }
    }

    private fun style(config: JSONObject): String? {
        val value = config.optString("style").trim().lowercase()
        return value.takeIf { it in THEME_BUTTON_STYLES }
    }

    private fun radius(config: JSONObject): Float? {
        if (!config.has("cornerRadiusDp") || config.isNull("cornerRadiusDp")) return null
        val value = config.optDouble("cornerRadiusDp", 0.0).toFloat()
        if (value.isNaN()) return null
        return value.coerceIn(0f, MAX_BUTTON_RADIUS.toFloat())
    }

    private fun color(config: JSONObject): androidx.compose.ui.graphics.Color? {
        val hex = normalizeHexColor(config.optString("color")) ?: return null
        return parseThemeColor(hex)
    }

    private fun log(message: String) {
        Log.i(LOG_TAG, "[$pluginId] $message")
    }

    private companion object {
        val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        const val MAX_LABEL = 64
    }
}
