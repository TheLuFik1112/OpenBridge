package app.openbridge.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.openbridge.OpenBridgeApp
import app.openbridge.core.Diag
import app.openbridge.core.HistoryEntry
import app.openbridge.core.PendingCrash
import app.openbridge.core.QueueItem
import app.openbridge.core.TrustedPeer
import app.openbridge.data.DiscoveredPeer
import app.openbridge.discovery.NearbyProgress
import app.openbridge.discovery.NearbyRequest
import app.openbridge.domain.AppSettings
import app.openbridge.domain.EngineController
import app.openbridge.domain.NearbySession
import app.openbridge.domain.NfcSession
import app.openbridge.domain.NfcState
import app.openbridge.domain.PackageQueue
import app.openbridge.domain.SendState
import app.openbridge.domain.TransferSender
import app.openbridge.text.AppStrings
import app.openbridge.text.resolveStrings
import app.openbridge.theme.BuiltInThemes
import app.openbridge.theme.ThemeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val openBridgeApp = application as OpenBridgeApp
    private val graph get() = openBridgeApp.graph
    private val context: Context get() = getApplication()

    private val messages = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = messages.asStateFlow()

    val sending = MutableStateFlow<SendState?>(null)
    // Берём сохранённый язык сразу: после пересоздания процесса UI не должен
    // откатываться к системному, пока AppSettings.load() читает настройки.
    val strings = MutableStateFlow<AppStrings>(
        resolveStrings(graph.settings.appLanguage.value),
    )

    private val engineReady = MutableStateFlow(false)
    private val nearbyEnabledState = MutableStateFlow(false)

    val peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val trusted = MutableStateFlow<List<TrustedPeer>>(emptyList())
    val trustedIds = MutableStateFlow<Set<String>>(emptySet())
    val history = MutableStateFlow<List<HistoryEntry>>(emptyList())

    private val settings = AppSettings(graph, viewModelScope)
    private val packages = PackageQueue(context, graph, viewModelScope, settings, messages)
    private val nearbySession = NearbySession(context, graph, viewModelScope, messages)
    private val nfc = NfcSession(context, graph, viewModelScope, packages, strings, messages)

    private val sender = TransferSender(
        context = context,
        graph = graph,
        scope = viewModelScope,
        queue = packages,
        peers = peers,
        trustedIds = trustedIds,
        engineReady = { engineReady.value },
        sending = sending,
        progress = nearbySession.progress,
        messages = messages,
    )

    private val engine = EngineController(
        app = application,
        graph = graph,
        scope = viewModelScope,
        settings = settings,
        messages = messages,
        ready = engineReady,
        nearbyEnabled = nearbyEnabledState,
        nearbyItems = { packages.nearbyItems() },
        onWarm = ::bindRepositories,
    )

    init {
        Diag.log("viewmodel", "init")

        viewModelScope.launch {
            settings.appLanguage.collect { strings.value = resolveStrings(it) }
        }

        viewModelScope.launch(Dispatchers.IO) {
            nearbySession.progress.collect { progress ->
                if (progress != null) nfc.onNearbyProgress(progress.fraction)
            }
        }
    }

    val isEngineReady: StateFlow<Boolean> = engineReady
    val engineError: StateFlow<String?> = engine.error
    val serverState: StateFlow<String> = engine.serverState
    val lastSelfCheck: StateFlow<String> = engine.lastSelfCheck
    val crash: StateFlow<PendingCrash?> = engine.crash

    val nearbyEnabled: StateFlow<Boolean> = nearbyEnabledState
    val nearbyIncoming: StateFlow<NearbyRequest?> = nearbySession.incoming
    val nearbyProgress: StateFlow<NearbyProgress?> = nearbySession.progress

    val deviceName: StateFlow<String> = settings.deviceName.asStateFlow()
    val serviceEnabled: StateFlow<Boolean> = settings.serviceEnabled.asStateFlow()
    val autoAccept: StateFlow<Boolean> = settings.autoAccept.asStateFlow()
    val portalEnabled: StateFlow<Boolean> = settings.portalEnabled.asStateFlow()
    val developerMode: StateFlow<Boolean> = settings.developerMode.asStateFlow()
    val autoSendReports: StateFlow<Boolean> = settings.autoSendReports.asStateFlow()
    val appLanguage: StateFlow<String> = settings.appLanguage.asStateFlow()
    val themeMode: StateFlow<String> = settings.themeMode.asStateFlow()

    private val installedThemes = MutableStateFlow(graph.plugins.builtInThemes())
    val themes: StateFlow<List<ThemeConfig>> = installedThemes.asStateFlow()

    private val activeThemeState = MutableStateFlow(
        BuiltInThemes.byId(graph.settings.themePluginId.value) ?: BuiltInThemes.DefaultFlat,
    )
    val activeTheme: StateFlow<ThemeConfig> = activeThemeState.asStateFlow()

    private val themePluginState = MutableStateFlow(graph.settings.themePluginId.value)
    val themePluginId: StateFlow<String> = themePluginState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val list = graph.plugins.loadInstalledThemes()
            installedThemes.value = list
            applyTheme(list, settings.themePluginId.value)
        }
    }

    val queue: StateFlow<List<QueueItem>> = packages.items.asStateFlow()
    val portalUrl: StateFlow<String?> = packages.portalUrl.asStateFlow()

    val nfcSheetOpen: StateFlow<Boolean> = nfc.isOpen
    val nfcState: StateFlow<NfcState> = nfc.state

    fun startEngine() = engine.start()

    private fun bindRepositories() {
        val peersRepo = graph.peers
        val historyRepo = graph.history
        viewModelScope.launch(Dispatchers.IO) {
            peersRepo.discovered.collect { peers.value = it }
        }
        viewModelScope.launch(Dispatchers.IO) {
            peersRepo.trusted.collect { list ->
                trusted.value = list
                trustedIds.value = list.filterNot { it.revoked }.map { it.id }.toSet()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            historyRepo.entries.collect { history.value = it }
        }
        nearbySession.attach()
        nfc.attach()
    }

    fun consumeMessage() {
        messages.value = null
    }

    fun dismissCrash() = engine.dismissCrash()

    fun sendCrash() = engine.sendCrash()

    fun acceptNearby() = nearbySession.accept()

    fun trustNearby() = nearbySession.trust()

    fun declineNearby() = nearbySession.decline()

    fun dismissNearbyProgress() = nearbySession.dismissProgress()

    fun openNfcSheet() = nfc.openSheet()

    fun closeNfcSheet() = nfc.close()

    fun handleNfcReceived(raw: String) = nfc.handleReceived(raw)

    fun addUris(uris: List<Uri>) = packages.addUris(uris)

    fun addFolder(uri: Uri) = packages.addFolder(uri)

    fun addText(text: String) = packages.addText(text)

    fun removeItem(id: String) = packages.removeItem(id)

    fun clearQueue() = packages.clear()

    fun sendTo(peer: DiscoveredPeer) = sender.sendTo(peer)

    fun sendClipboardNow() = sender.sendClipboardNow()

    fun ensurePortalUrl(onReady: (String) -> Unit) = packages.ensurePortalUrl(onReady)

    fun setDeviceName(name: String) = settings.setDeviceName(name)

    fun setServiceEnabled(enabled: Boolean) = engine.setServiceEnabled(enabled)

    fun setAutoAccept(enabled: Boolean) = settings.setAutoAccept(enabled)

    fun setPortalEnabled(enabled: Boolean) = packages.setPortalEnabled(enabled)

    fun revoke(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            graph.peers.revoke(id)
            messages.value = "Устройство больше не доверенное"
        }
    }

    fun setAppLanguage(lang: String) {
        val clean = settings.setAppLanguage(lang)
        strings.value = resolveStrings(clean)
    }

    fun setThemeMode(mode: String) {
        settings.setThemeMode(mode)
    }

    fun setThemePlugin(id: String) {
        val theme = installedThemes.value.firstOrNull { it.id == id } ?: return
        activeThemeState.value = theme
        themePluginState.value = theme.id
        settings.setThemePluginId(theme.id)
    }

    fun importTheme(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = strings.value
            graph.plugins.importThemeFromUri(uri)
                .onSuccess { theme ->
                    val list = graph.plugins.loadInstalledThemes()
                    installedThemes.value = list
                    if (themePluginState.value == theme.id) {
                        list.firstOrNull { it.id == theme.id }?.let {
                            activeThemeState.value = it
                        }
                    }
                    messages.value = s.pluginImported(theme.name)
                }
                .onFailure { error ->
                    messages.value = "${s.pluginImportFailed}: ${error.message ?: "unknown error"}"
                }
        }
    }

    fun deleteTheme(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = strings.value
            if (graph.plugins.deleteTheme(id)) {
                val list = graph.plugins.loadInstalledThemes()
                installedThemes.value = list
                if (themePluginState.value == id) {
                    applyTheme(list, BuiltInThemes.DEFAULT_ID)
                }
                messages.value = s.pluginDeleted
            } else {
                messages.value = s.pluginDeleteFailed
            }
        }
    }

    private fun applyTheme(list: List<ThemeConfig>, id: String) {
        val theme = list.firstOrNull { it.id == id } ?: BuiltInThemes.DefaultFlat
        activeThemeState.value = theme
        themePluginState.value = theme.id
        if (theme.id != id) settings.setThemePluginId(theme.id)
    }

    fun setDeveloperMode(enabled: Boolean) = settings.setDeveloperMode(enabled)

    fun setAutoSendReports(enabled: Boolean) = settings.setAutoSendReports(enabled)

    fun checkServer() = engine.checkServer()

    fun saveLogs() = engine.saveLogs()

    fun restartServer() = engine.restartServer()

    fun restartDiscovery() = engine.restartDiscovery()

    fun logsFlow() = engine.logsFlow()

    fun serverStats(): String = buildString {
        appendLine("Движок: ${if (engineReady.value) "готов" else "запускается"}")
        appendLine("Сервер: ${serverState.value}")
        appendLine("Проверка: ${lastSelfCheck.value}")
        appendLine("TLS-порт: 53530, веб: 53531")
        appendLine("Устройств рядом: ${peers.value.size}")
        appendLine("Доверенных: ${trusted.value.count { !it.revoked }}")
        appendLine("Передач в истории: ${history.value.size}")
        appendLine("LAN: UDP 53531")
        append("Nearby: ${if (nearbyEnabledState.value) "доступен" else "нет (нет Play Services)"}")
    }
}
