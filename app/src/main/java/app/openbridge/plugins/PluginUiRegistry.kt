package app.openbridge.plugins

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import app.openbridge.theme.BUTTON_FILLED

@Immutable
data class PluginButtonOverride(
    val pluginId: String,
    val text: String? = null,
    val style: String? = null,
    val cornerRadiusDp: Float? = null,
    val color: Color? = null,
    val hidden: Boolean = false,
    val onClick: Boolean = false,
)

@Immutable
data class PluginCustomButton(
    val pluginId: String,
    val id: String,
    val text: String,
    val style: String = BUTTON_FILLED,
    val color: Color? = null,
    val cornerRadiusDp: Float? = null,
    val onClick: Boolean = false,
)

object PluginUiRegistry {
    private const val MAX_OVERRIDES = 256
    private const val MAX_SLOT_BUTTONS = 8

    private val overrides = mutableStateMapOf<String, PluginButtonOverride>()
    private val slots = mutableStateMapOf<String, List<PluginCustomButton>>()

    fun overrideFor(buttonId: String): PluginButtonOverride? = overrides[buttonId]

    fun buttons(slotId: String): List<PluginCustomButton> = slots[slotId].orEmpty()

    fun setOverride(buttonId: String, value: PluginButtonOverride) {
        if (buttonId !in overrides && overrides.size >= MAX_OVERRIDES) return
        overrides[buttonId] = value
    }

    fun addButton(slotId: String, button: PluginCustomButton) {
        val current = slots[slotId].orEmpty()
        val kept = current.filterNot { it.pluginId == button.pluginId && it.id == button.id }
        slots[slotId] = (kept + button).takeLast(MAX_SLOT_BUTTONS)
    }

    fun clearPlugin(pluginId: String) {
        overrides.keys.toList().forEach { key ->
            if (overrides[key]?.pluginId == pluginId) overrides.remove(key)
        }
        slots.keys.toList().forEach { slot ->
            val kept = slots[slot].orEmpty().filterNot { it.pluginId == pluginId }
            if (kept.isEmpty()) slots.remove(slot) else slots[slot] = kept
        }
    }

    fun retainPlugins(ids: Set<String>) {
        overrides.keys.toList().forEach { key ->
            val owner = overrides[key]?.pluginId
            if (owner != null && owner !in ids) overrides.remove(key)
        }
        slots.keys.toList().forEach { slot ->
            val kept = slots[slot].orEmpty().filter { it.pluginId in ids }
            if (kept.isEmpty()) slots.remove(slot) else slots[slot] = kept
        }
    }

    fun clearAll() {
        overrides.clear()
        slots.clear()
    }
}
