package app.openbridge.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow

class SettingsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("openbridge.settings", Context.MODE_PRIVATE)

    private val defaultName: String =
        (Build.MODEL ?: "Android").replaceFirstChar { it.uppercase() }.ifBlank { "Android" }

    val deviceName = MutableStateFlow(prefs.getString(KEY_NAME, defaultName) ?: defaultName)
    val serviceEnabled = MutableStateFlow(prefs.getBoolean(KEY_SERVICE, true))
    val autoAcceptTrusted = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    val portalEnabled = MutableStateFlow(prefs.getBoolean(KEY_PORTAL, false))
    val overlayPromptShown = MutableStateFlow(prefs.getBoolean(KEY_OVERLAY_PROMPT, false))
    val developerMode = MutableStateFlow(prefs.getBoolean(KEY_DEVELOPER, false))
    val autoSendReports = MutableStateFlow(prefs.getBoolean(KEY_AUTO_REPORTS, false))
    val appLanguage = MutableStateFlow(prefs.getString(KEY_LANGUAGE, "system") ?: "system")

    val themeMode = MutableStateFlow(normalizeTheme(prefs.getString(KEY_THEME, null)))

    val themePluginId = MutableStateFlow(
        prefs.getString(KEY_THEME_PLUGIN, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_THEME_PLUGIN,
    )

    fun setThemeMode(value: String) {
        val clean = normalizeTheme(value)
        themeMode.value = clean
        prefs.edit().putString(KEY_THEME, clean).apply()
    }

    fun setThemePluginId(value: String) {
        val clean = value.trim().take(64).ifBlank { DEFAULT_THEME_PLUGIN }
        themePluginId.value = clean
        prefs.edit().putString(KEY_THEME_PLUGIN, clean).apply()
    }

    private fun normalizeTheme(value: String?): String =
        if (value != null && value in THEMES) value else THEME_SYSTEM

    fun setAppLanguage(value: String) {
        val clean = if (value in listOf("system", "en", "ru")) value else "system"
        appLanguage.value = clean
        prefs.edit().putString(KEY_LANGUAGE, clean).apply()
    }

    fun setDeviceName(value: String) {
        val clean = value.trim().take(40).ifBlank { defaultName }
        deviceName.value = clean
        prefs.edit().putString(KEY_NAME, clean).apply()
    }

    fun setServiceEnabled(value: Boolean) {
        serviceEnabled.value = value
        prefs.edit().putBoolean(KEY_SERVICE, value).apply()
    }

    fun setAutoAcceptTrusted(value: Boolean) {
        autoAcceptTrusted.value = value
        prefs.edit().putBoolean(KEY_AUTO, value).apply()
    }

    fun setPortalEnabled(value: Boolean) {
        portalEnabled.value = value
        prefs.edit().putBoolean(KEY_PORTAL, value).apply()
    }

    fun markOverlayPromptShown() {
        overlayPromptShown.value = true
        prefs.edit().putBoolean(KEY_OVERLAY_PROMPT, true).apply()
    }

    fun setDeveloperMode(value: Boolean) {
        developerMode.value = value
        prefs.edit().putBoolean(KEY_DEVELOPER, value).apply()
    }

    fun setAutoSendReports(value: Boolean) {
        autoSendReports.value = value
        prefs.edit().putBoolean(KEY_AUTO_REPORTS, value).apply()
    }

    fun portalToken(): String =
        prefs.getString(KEY_TOKEN, null) ?: newToken().also {
            prefs.edit().putString(KEY_TOKEN, it).apply()
        }

    fun newToken(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val token = (1..10).map { alphabet.random() }.joinToString("")
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    private companion object {
        const val KEY_NAME = "device_name"
        const val KEY_SERVICE = "service_enabled"
        const val KEY_AUTO = "auto_accept"
        const val KEY_PORTAL = "portal_enabled"
        const val KEY_TOKEN = "portal_token"
        const val KEY_OVERLAY_PROMPT = "overlay_prompt"
        const val KEY_DEVELOPER = "developer_mode"
        const val KEY_AUTO_REPORTS = "auto_send_reports"
        const val KEY_LANGUAGE = "app_language"
        const val KEY_THEME = "theme_mode"
        const val KEY_THEME_PLUGIN = "theme_plugin_id"

        const val DEFAULT_THEME_PLUGIN = "default_flat"

        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"

        private val THEMES = listOf(THEME_DARK, THEME_LIGHT, THEME_SYSTEM)
    }
}
