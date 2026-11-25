package app.openbridge.domain

import app.openbridge.core.AppGraph
import app.openbridge.util.NfcHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Настройки в виде потока. Хранилище — [app.openbridge.data.SettingsRepository],
 * здесь только зеркало в памяти: интерфейс читает его без обращения к диску,
 * а запись уходит в репозиторий в фоне.
 */
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
    val appLanguage = MutableStateFlow("system")
    val themeMode = MutableStateFlow(THEME_SYSTEM)

    /** Читает prefs с диска. Только из фонового потока. */
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
    }

    fun setDeviceName(name: String) {
        val clean = name.trim()
        deviceName.value = clean
        // Имя уходит в метку NFC: получатель ищет нас по нему в эфире.
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

    /** Возвращает очищенный код языка — по нему интерфейс сразу берёт словарь. */
    fun setAppLanguage(lang: String): String {
        val clean = sanitizeLanguage(lang)
        appLanguage.value = clean
        scope.launch(Dispatchers.IO) { graph.settings.setAppLanguage(clean) }
        return clean
    }

    /** Возвращает очищенный режим темы. */
    fun setThemeMode(mode: String): String {
        val clean = sanitizeTheme(mode)
        themeMode.value = clean
        scope.launch(Dispatchers.IO) { graph.settings.setThemeMode(clean) }
        return clean
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
