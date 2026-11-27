package app.openbridge.core

import android.app.Application
import app.openbridge.data.HistoryRepository
import app.openbridge.data.PeerRepository
import app.openbridge.data.SettingsRepository
import app.openbridge.discovery.NearbyP2pManager
import app.openbridge.discovery.PeerDiscovery
import app.openbridge.net.IncomingCoordinator
import app.openbridge.net.Portal
import app.openbridge.net.ReceiveStore
import app.openbridge.net.TransferClient
import app.openbridge.net.TransferServer
import app.openbridge.plugins.PluginManager
import app.openbridge.service.IncomingNotifier
import app.openbridge.service.IncomingPresenter
import app.openbridge.util.NfcHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val identity by lazy {
        Diag.log("identity", "creating")
        runCatching { IdentityStore(app) }.getOrElse { error ->
            Diag.log("identity", "failed: $error")
            Diag.dump("identity", error)
            throw error
        }.also { Diag.log("identity", "ready") }
    }
    val settings by lazy { SettingsRepository(app) }
    val plugins by lazy { PluginManager(app) }
    val peers by lazy { PeerRepository(app, json) }
    val history by lazy { HistoryRepository(app, json) }
    val portal by lazy { Portal(app, settings) }
    val receiver by lazy { ReceiveStore(app, identity) }
    val notifier by lazy { IncomingNotifier(app) }

    val incoming by lazy { IncomingCoordinator(this) }
    val presenter by lazy { IncomingPresenter(app, this) }
    val client by lazy { TransferClient(this) }
    val server by lazy { TransferServer(this) }

    val nearby by lazy {
        NearbyP2pManager(
            context = app,
            scope = scope,
            selfId = { identity.identity.id },
            selfName = { settings.deviceName.value },
            isTrusted = { id -> peers.trusted.value.any { it.id == id && !it.revoked } },
            isNfcHandoff = { NfcHelper.recentlyServed() },
            onPeer = { peer ->
                peers.onDiscovered(
                    PeerInfo(id = peer.deviceId, name = peer.name, certFingerprint = peer.deviceId),
                    ip = "",
                    source = "nearby",
                )
            },
            onPeerLost = { id -> peers.onPeerLost(id, "nearby") },
        )
    }

    val discovery by lazy {
        PeerDiscovery(
            context = app,
            scope = scope,
            self = { myPeerInfo() },
            onPeer = { peer, ip, source -> peers.onDiscovered(peer, ip, source) },
        )
    }

    val runtimeActive = MutableStateFlow(false)

    val serverState = MutableStateFlow("остановлен")
    val lastSelfCheck = MutableStateFlow("—")

    private var pruneJob: Job? = null

    suspend fun warmUp() {
        val started = android.os.SystemClock.elapsedRealtime()
        identity.identity
        settings.deviceName.value
        peers.trusted.value
        history.entries.value
        myPeerInfo()
        Diag.log("app", "warmUp identity+data ${android.os.SystemClock.elapsedRealtime() - started} ms")
    }

    fun myPeerInfo(): PeerInfo {
        val id = identity.identity
        return PeerInfo(
            id = id.id,
            name = settings.deviceName.value,
            platform = "android",
            port = TLS_PORT,
            certFingerprint = id.fingerprint,
            certB64 = id.certB64,
            publicKeyB64 = id.publicKeyB64,
        )
    }

    fun probeServer(): String {
        var last: Throwable? = null
        for (attempt in 1..3) {
            val result = runCatching { probeOnce() }
            result.getOrNull()?.let { return it }
            last = result.exceptionOrNull()
            if (attempt < 3) Thread.sleep(400L * attempt)
        }
        throw last ?: IllegalStateException("нет соединения")
    }

    private fun probeOnce(): String {
        val url = java.net.URL("https://127.0.0.1:$TLS_PORT/v1/info")
        val (factory, _) = identity.clientSsl(identity.identity.fingerprint)
        val connection = (url.openConnection() as javax.net.ssl.HttpsURLConnection).apply {
            sslSocketFactory = factory
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            connectTimeout = 5_000
            readTimeout = 8_000
        }
        return try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            "HTTP $code ${text.take(90)}"
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    fun startRuntime() {
        if (runtimeActive.value) return
        runtimeActive.value = true
        Diag.log("runtime", "start")

        runCatching { server.start() }
            .onFailure {
                serverState.value = "ошибка: ${it.javaClass.simpleName}"
                Diag.log("runtime", "server failed: $it")
            }
        if (serverState.value != "ошибка") serverState.value = "работает"
        runCatching { discovery.start() }
            .onFailure { Diag.log("runtime", "LAN discovery failed: $it") }
        runCatching { nearby.start() }
            .onFailure { Diag.log("runtime", "nearby start failed: $it") }

        scope.launch {
            delay(700)
            runCatching { probeServer() }
                .onSuccess {
                    lastSelfCheck.value = it
                    Diag.log("probe", "self-check ok: $it")
                }
                .onFailure {
                    lastSelfCheck.value = "ошибка: ${it.message}"
                    Diag.log("probe", "self-check failed: $it")
                }
        }

        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive) {
                runCatching { peers.prune() }
                delay(5_000)
            }
        }
    }

    fun stopRuntime() {
        runtimeActive.value = false
        serverState.value = "остановлен"
        Diag.log("runtime", "stop")
        pruneJob?.cancel()
        pruneJob = null
        discovery.stop()
        runCatching { nearby.stop() }
        runCatching { server.stop() }
    }

    suspend fun restartServer(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        Diag.log("runtime", "restart requested")
        runCatching { server.stop() }
        val result = runCatching { server.start() }
        result.onFailure {
            serverState.value = "ошибка: ${it.javaClass.simpleName}"
            Diag.log("runtime", "restart failed: $it")
        }
        if (result.isSuccess) {
            serverState.value = "работает"
            delay(400)
            val probe = runCatching { probeServer() }
            lastSelfCheck.value = probe.getOrElse { "ошибка: ${it.message}" }
        }
        serverState.value
    }
}
