package app.openbridge.domain

import android.app.Application
import android.os.SystemClock
import app.openbridge.OpenBridgeApp
import app.openbridge.core.AppGraph
import app.openbridge.core.CrashReporter
import app.openbridge.core.Diag
import app.openbridge.core.PendingCrash
import app.openbridge.discovery.NearbyItem
import app.openbridge.service.RadarService
import app.openbridge.util.NfcHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class EngineController(
    private val app: Application,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val messages: MutableStateFlow<String?>,
    private val ready: MutableStateFlow<Boolean>,
    private val nearbyEnabled: MutableStateFlow<Boolean>,
    private val nearbyItems: () -> List<NearbyItem>,
    private val onWarm: () -> Unit,
) {
    val error = MutableStateFlow<String?>(null)
    val serverState = MutableStateFlow("starting")
    val lastSelfCheck = MutableStateFlow("—")
    val crash = MutableStateFlow<PendingCrash?>(null)
    val discoveryRefreshing = MutableStateFlow(false)

    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scope.launch(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            Diag.log("engine", "start")

            runCatching { settings.load() }.onFailure { fail("settings", it) }
            runCatching { onWarm() }.onFailure { fail("data", it) }

            scope.launch(Dispatchers.IO) {
                graph.serverState.collect { serverState.value = it }
            }
            scope.launch(Dispatchers.IO) {
                graph.lastSelfCheck.collect { lastSelfCheck.value = it }
            }

            runCatching { graph.warmUp() }
                .onFailure { fail("keys and data", it) }

            runCatching {
                NfcHelper.prime(app)
                NfcHelper.updateSender(
                    id = graph.identity.identity.id,
                    name = settings.deviceName.value,
                )
            }.onFailure { Diag.log("nfc", "sender init failed: $it") }

            graph.nearby.pullProvider = { nearbyItems() }

            launch {
                runCatching { graph.startRuntime() }
                    .onFailure { fail("server", it) }
            }

            launch {
                runCatching { (app as OpenBridgeApp).startRadarServiceIfEnabled() }
                    .onFailure { fail("service", it) }
            }

            launch {
                loadPendingCrash()
                nearbyEnabled.value = runCatching { graph.nearby.isAvailable() }.getOrDefault(false)
            }

            ready.value = true
            Diag.log(
                "engine",
                "ready in ${SystemClock.elapsedRealtime() - startedAt} ms",
            )
        }
    }

    fun setServiceEnabled(enabled: Boolean) {
        if (!ready.value) return
        settings.setServiceEnabled(enabled)
        scope.launch(Dispatchers.IO) {
            if (enabled) {
                RadarService.start(app)
            } else {
                RadarService.stop(app)
            }
        }
    }

    fun dismissCrash() {
        crash.value = null
        scope.launch(Dispatchers.IO) { CrashReporter.clear(app) }
    }

    fun sendCrash() {
        val report = crash.value ?: return
        crash.value = null
        scope.launch(Dispatchers.IO) {
            val path = runCatching { CrashReporter.send(app, report) }.getOrNull()
            CrashReporter.clear(app)
            messages.value = if (path != null) "Логи сохранены: $path" else "Не удалось отправить логи"
        }
    }

    fun checkServer() {
        scope.launch(Dispatchers.IO) {
            val result = runCatching { graph.probeServer() }
                .getOrElse { "Ошибка: ${it.javaClass.simpleName}: ${it.message}" }
            graph.lastSelfCheck.value = result
            Diag.log("probe", "manual check: $result")
            messages.value = result
        }
    }

    fun saveLogs() {
        scope.launch(Dispatchers.IO) {
            val path = runCatching { Diag.dump("manual") }.getOrNull()
            messages.value = path?.let { "Отчёт сохранён: $it" } ?: "Не удалось сохранить отчёт"
        }
    }

    fun refreshNearby() {
        scope.launch(Dispatchers.IO) {
            discoveryRefreshing.value = true
            runCatching { graph.nearby.stop() }
            runCatching { graph.nearby.start() }
                .onFailure { Diag.log("discovery", "Nearby restart failed: $it") }
            nearbyEnabled.value = runCatching { graph.nearby.isAvailable() }.getOrDefault(false)
            Diag.log("discovery", "nearby refresh")
            delay(900)
            discoveryRefreshing.value = false
        }
    }

    fun restartServer() {
        scope.launch(Dispatchers.IO) {
            val state = graph.restartServer()
            messages.value = "Сервер: $state"
        }
    }

    fun restartDiscovery() {
        scope.launch(Dispatchers.IO) {
            runCatching { graph.nearby.stop() }
            runCatching { graph.nearby.start() }
            runCatching { graph.discovery.start() }
            nearbyEnabled.value = runCatching { graph.nearby.isAvailable() }.getOrDefault(false)
        }
    }

    fun refreshDiscovery(preferredAddress: String?) {
        scope.launch(Dispatchers.IO) {
            discoveryRefreshing.value = true
            runCatching { graph.discovery.setPreferredAddress(preferredAddress) }
            runCatching { graph.discovery.restart() }
                .onFailure { Diag.log("discovery", "LAN restart failed: $it") }
            runCatching { graph.nearby.stop() }
            runCatching { graph.nearby.start() }
                .onFailure { Diag.log("discovery", "Nearby restart failed: $it") }
            nearbyEnabled.value = runCatching { graph.nearby.isAvailable() }.getOrDefault(false)
            Diag.log("discovery", "manual refresh, interface=${preferredAddress ?: "auto"}")
            delay(900)
            discoveryRefreshing.value = false
        }
    }

    fun logsFlow(): Flow<String> = flow {
        while (true) {
            emit(Diag.recentLines(200))
            delay(2_000)
        }
    }

    private fun loadPendingCrash() {
        val pending = runCatching { CrashReporter.pending(app) }.getOrNull() ?: return
        if (settings.autoSendReports.value) {
            val sent = runCatching { CrashReporter.send(app, pending) }.getOrNull()
            CrashReporter.clear(app)
            messages.value = if (sent != null) {
                "Отчёт о сбое отправлен разработчику"
            } else {
                "Не удалось отправить отчёт о сбое"
            }
        } else {
            crash.value = pending
        }
    }

    private fun fail(what: String, error: Throwable) {
        Diag.log("engine", "$what failed: $error")
        this.error.value = "$what: ${error.javaClass.simpleName}: ${error.message}"
    }
}
