package app.openbridge.domain

import app.openbridge.core.AppGraph
import app.openbridge.util.NfcHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AppSettings(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    val deviceName = MutableStateFlow("")
    val serviceEnabled = MutableStateFlow(true)
    val autoAccept = MutableStateFlow(true)
    val portalEnabled = MutableStateFlow(false)
    val developerMode = MutableStateFlow(false)
    val autoSendReports = MutableStateFlow(false)
    val appLanguage = MutableStateFlow(sanitizeLanguage(graph.settings.appLanguage.value))
    val themeMode = MutableStateFlow(sanitizeTheme(graph.settings.themeMode.value))
    val themePluginId = MutableStateFlow(graph.settings.themePluginId.value)

    fun load() {
        val s = graph.settings
        deviceName.value = s.deviceName.value
        serviceEnabled.value = s.serviceEnabled.value
        autoAccept.value = s.autoAcceptTrusted.value
        portalEnabled.value = s.portalEnabled.value
        developerMode.value = s.developerMode.value
        autoSendReports.value = s.autoSendReports.value
        appLanguage.value = sanitizeLanguage(s.appLanguage.value)
        themeMode.value = sanitizeTheme(s.themeMode.value)
        themePluginId.value = s.themePluginId.value
    }

    fun setDeviceName(name: String) {
        val clean = name.trim()
        deviceName.value = clean

        NfcHelper.updateSender(name = clean)
        scope.launch(Dispatchers.IO) { graph.settings.setDeviceName(name) }
    }

    fun setAutoAccept(enabled: Boolean) {
        autoAccept.value = enabled
        scope.launch(Dispatchers.IO) { graph.settings.setAutoAcceptTrusted(enabled) }
    }

    fun setServiceEnabled(enabled: Boolean) {
        serviceEnabled.value = enabled
        scope.launch(Dispatchers.IO) { graph.settings.setServiceEnabled(enabled) }
    }

    fun setPortalEnabled(enabled: Boolean) {
        portalEnabled.value = enabled
        scope.launch(Dispatchers.IO) { graph.settings.setPortalEnabled(enabled) }
    }

    fun setDeveloperMode(enabled: Boolean) {
        developerMode.value = enabled
        scope.launch(Dispatchers.IO) { graph.settings.setDeveloperMode(enabled) }
    }

    fun setAutoSendReports(enabled: Boolean) {
        autoSendReports.value = enabled
        scope.launch(Dispatchers.IO) { graph.settings.setAutoSendReports(enabled) }
    }

    fun setAppLanguage(lang: String): String {
        val clean = sanitizeLanguage(lang)
        appLanguage.value = clean
        scope.launch(Dispatchers.IO) { graph.settings.setAppLanguage(clean) }
        return clean
    }

    fun setThemeMode(mode: String): String {
        val clean = sanitizeTheme(mode)
        themeMode.value = clean
        scope.launch(Dispatchers.IO) { graph.settings.setThemeMode(clean) }
        return clean
    }

    fun setThemePluginId(id: String) {
        val clean = id.trim().take(64).ifBlank { "default_flat" }
        themePluginId.value = clean
        scope.launch(Dispatchers.IO) { graph.settings.setThemePluginId(clean) }
    }

    private fun sanitizeLanguage(lang: String): String =
        if (lang in LANGUAGES) lang else LANGUAGE_SYSTEM

    private fun sanitizeTheme(mode: String): String =
        if (mode in THEMES) mode else THEME_SYSTEM

    companion object {
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"

        private const val LANGUAGE_SYSTEM = "system"
        private val LANGUAGES = listOf(LANGUAGE_SYSTEM, "en", "ru")
        private val THEMES = listOf(THEME_DARK, THEME_LIGHT, THEME_SYSTEM)
    }
}
