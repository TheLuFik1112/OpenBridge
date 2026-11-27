package app.openbridge.discovery

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import app.openbridge.core.Diag
import app.openbridge.net.Downloads
import app.openbridge.util.NfcHelper
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class NearbyPeer(
    val deviceId: String,
    val name: String,
    val endpointId: String,
    val lastSeen: Long,
)

@Immutable
data class NearbyRequest(
    val endpointId: String,
    val deviceId: String,
    val name: String,
    val trusted: Boolean,
)

@Immutable
data class NearbyProgress(
    val peerName: String,
    val fraction: Float,
    val text: String,
)

data class PullSummary(val texts: Int, val files: Int)

sealed interface NearbyEvent {
    data class Text(val peerName: String, val text: String) : NearbyEvent
    data class FileSaved(
        val peerName: String,
        val path: String,
        val bytes: Long,
        val name: String = "",
        val mime: String = "application/octet-stream",
    ) : NearbyEvent
    data class Failed(val peerName: String, val reason: String) : NearbyEvent
}

sealed interface NearbyItem {
    data class Text(val text: String) : NearbyItem
    data class FileItem(val uri: Uri, val name: String) : NearbyItem
}

class NearbyP2pManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val selfId: () -> String,
    private val selfName: () -> String,
    private val isTrusted: (String) -> Boolean,
    private val onPeer: (NearbyPeer) -> Unit,
    private val onPeerLost: (String) -> Unit,
    private val isNfcHandoff: () -> Boolean = { false },
    private val onIncomingRequest: (NearbyRequest) -> Unit = {},
) {
    private companion object {
        const val SERVICE_ID = "app.openbridge.nearby"
        const val NAME_SEPARATOR = "|"
        const val TEXT_PREFIX = "FD/TEXT:"
        const val FILE_PREFIX = "FD/FILE:"
        const val PULL_PREFIX = "FD/PULL"
        const val DONE_PREFIX = "FD/DONE"
        const val TOTAL_PREFIX = "FD/TOTAL:"
        const val ERROR_PREFIX = "FD/ERR:"
        const val MAX_ENDPOINT_NAME = 120
        const val STATUS_OK = 0
        const val STATUS_ERROR = 13
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val DISCOVERY_TIMEOUT_MS = 22_000L
        const val QUICK_DISCOVERY_MS = 6_000L
        const val PAYLOAD_TIMEOUT_MS = 180_000L
        const val PULL_TIMEOUT_MS = 300_000L
        const val MAX_BYTES_PAYLOAD = 90_000
        const val REFRESH_MS = 5_000L
    }

    private val client: ConnectionsClient? = runCatching {
        Nearby.getConnectionsClient(context.applicationContext)
    }.getOrNull()

    private val strategy = Strategy.P2P_CLUSTER

    private val endpoints = ConcurrentHashMap<String, String>()   // deviceId -> endpointId
    private val devices = ConcurrentHashMap<String, String>()     // endpointId -> deviceId
    private val names = ConcurrentHashMap<String, String>()       // endpointId -> имя
    private val connections = ConcurrentHashMap<String, String>() // endpointId -> deviceId

    private val outgoing = ConcurrentHashMap.newKeySet<String>()

    private val pendingFileNames = ConcurrentHashMap<String, String>()

    // Nearby hands out a temp file that keeps growing while the payload is in
    // flight. Copying it in onPayloadReceived saves the first chunk only, so
    // the payload is kept here and finalised on Status.SUCCESS.
    private class PendingFile(
        val endpointId: String,
        val peerName: String,
        val uri: Uri,
        val expectedBytes: Long,
    )

    private val pendingFiles = ConcurrentHashMap<Long, PendingFile>()

    private val connectionWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val payloadWaiters = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

    private val controlWaiters = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private val pullCounters = ConcurrentHashMap<String, IntArray>()

    private val pullTotals = ConcurrentHashMap<String, Int>()

    @Volatile
    var pullProvider: (() -> List<NearbyItem>)? = null

    private val _incoming = MutableStateFlow<NearbyRequest?>(null)
    val incoming: StateFlow<NearbyRequest?> = _incoming.asStateFlow()

    private val _progress = MutableStateFlow<NearbyProgress?>(null)
    val progress: StateFlow<NearbyProgress?> = _progress.asStateFlow()

    private val _events = MutableStateFlow<NearbyEvent?>(null)
    val events: StateFlow<NearbyEvent?> = _events.asStateFlow()

    private val _connected = MutableStateFlow<Set<String>>(emptySet())
    val connected: StateFlow<Set<String>> = _connected.asStateFlow()

    private val _delivered = MutableStateFlow(0)
    val delivered: StateFlow<Int> = _delivered.asStateFlow()

    private val _failed = MutableStateFlow(0)
    val failed: StateFlow<Int> = _failed.asStateFlow()

    @Volatile
    private var running = false

    /** True while this phone is sending or pulling, so two runs never overlap. */
    @Volatile
    private var isTransferring = false

    /** Endpoint that currently owns the transfer, if any. */
    @Volatile
    private var activeEndpointId: String? = null

    /** Bumped on every radio restart so a burst of errors resets only once. */
    @Volatile
    private var radioGeneration = 0

    private var refreshJob: Job? = null

    fun isAvailable(): Boolean = client != null

    fun isKnown(deviceId: String): Boolean =
        endpoints.containsKey(deviceId) || connections.containsValue(deviceId)

    fun consumeEvent() {
        _events.value = null
    }

    @Synchronized
    fun start() {
        val client = client ?: run {
            Diag.log("nearby", "Google Play Services недоступен — Nearby выключен")
            return
        }
        if (running) return
        running = true
        startRadio(client, retry = true)

        refreshJob = scope.launch(Dispatchers.IO) {
            while (isActive && running) {
                delay(REFRESH_MS)
                devices.forEach { (endpointId, deviceId) ->
                    if (deviceId.isNotBlank()) {
                        onPeer(
                            NearbyPeer(
                                deviceId = deviceId,
                                name = names[endpointId] ?: "Android",
                                endpointId = endpointId,
                                lastSeen = System.currentTimeMillis(),
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Starts advertising and discovery. Any Nearby error is logged with its
     * exact status code, then the radio is reset once and the start is
     * repeated — a stale session is the usual cause, not Bluetooth.
     */
    private fun startRadio(client: ConnectionsClient, retry: Boolean) {
        val generation = ++radioGeneration
        val advertising = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        val discovery = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()

        client.startAdvertising(localName(), SERVICE_ID, lifecycleCallback, advertising)
            .addOnSuccessListener { Diag.log("nearby", "startAdvertising ok") }
            .addOnFailureListener { error ->
                Diag.log("nearby", "startAdvertising failed: ${describe(error)}")
                if (retry && generation == radioGeneration) {
                    resetRadio("advertising: ${describe(error)}")
                    if (running) startRadio(client, retry = false)
                }
            }

        client.startDiscovery(SERVICE_ID, endpointCallback, discovery)
            .addOnSuccessListener { Diag.log("nearby", "startDiscovery ok") }
            .addOnFailureListener { error ->
                Diag.log("nearby", "startDiscovery failed: ${describe(error)}")
                if (retry && generation == radioGeneration) {
                    resetRadio("discovery: ${describe(error)}")
                    if (running) startRadio(client, retry = false)
                }
            }
    }

    private fun statusCode(error: Throwable): Int? = (error as? ApiException)?.statusCode

    private fun describe(error: Throwable): String {
        val api = error as? ApiException ?: return error.message ?: error.javaClass.simpleName
        return "${statusName(api.statusCode)} (${api.statusCode}): ${api.message.orEmpty()}"
    }

    private fun statusName(code: Int): String = when (code) {
        STATUS_OK -> "STATUS_OK"
        STATUS_ERROR -> "STATUS_ERROR"
        8000 -> "STATUS_NETWORK_NOT_CONNECTED"
        8001 -> "STATUS_ALREADY_ADVERTISING"
        8002 -> "STATUS_ALREADY_DISCOVERING"
        8003 -> "STATUS_ALREADY_CONNECTED_TO_ENDPOINT"
        8004 -> "STATUS_CONNECTION_REJECTED"
        8005 -> "STATUS_NOT_CONNECTED_TO_ENDPOINT"
        8007 -> "STATUS_BLUETOOTH_ERROR"
        8008 -> "STATUS_ALREADY_HAVE_ACTIVE_STRATEGY"
        8009 -> "STATUS_OUT_OF_ORDER_API_CALL"
        8011 -> "STATUS_ENDPOINT_UNKNOWN"
        8012 -> "STATUS_ENDPOINT_IO_ERROR"
        8013 -> "STATUS_PAYLOAD_IO_ERROR"
        else -> "STATUS_$code"
    }

    /**
     * Drops every Nearby session and endpoint mapping so the next transfer
     * starts from a clean radio. Used after errors and before a new transfer.
     */
    private fun resetRadio(reason: String) {
        Diag.log("nearby", "сброс Nearby: $reason")
        runCatching { client?.stopAllEndpoints() }
        runCatching { client?.stopDiscovery() }
        runCatching { client?.stopAdvertising() }
        endpoints.clear()
        devices.clear()
        names.clear()
        connections.clear()
        outgoing.clear()
        pendingFileNames.clear()
        pendingFiles.clear()
        pullCounters.clear()
        pullTotals.clear()
        _connected.value = emptySet()
        _progress.value = null
        _incoming.value = null
        activeEndpointId = null
    }

    fun stop() {
        if (!running) return
        running = false
        refreshJob?.cancel()
        refreshJob = null
        runCatching { client?.stopAdvertising() }
        runCatching { client?.stopDiscovery() }
        runCatching { client?.stopAllEndpoints() }
        endpoints.clear()
        devices.clear()
        names.clear()
        connections.clear()
        outgoing.clear()
        pendingFileNames.clear()
        pendingFiles.clear()
        controlWaiters.clear()
        pullCounters.clear()
        pullTotals.clear()
        _connected.value = emptySet()
        _incoming.value = null
        _progress.value = null
        isTransferring = false
        activeEndpointId = null
        NfcHelper.clearHandoff()
        Diag.log("nearby", "остановлен")
    }

    private fun localName(): String {
        val id = runCatching { selfId() }.getOrDefault("")
        val name = runCatching { selfName() }.getOrDefault("Android")
        return "$id$NAME_SEPARATOR${name.take(40)}".take(MAX_ENDPOINT_NAME)
    }

    private fun parseDeviceId(endpointName: String?): String =
        endpointName.orEmpty().substringBefore(NAME_SEPARATOR)

    private fun parseName(endpointName: String?, fallback: String): String {
        val raw = endpointName.orEmpty()
        val name = raw.substringAfter(NAME_SEPARATOR, "")
        return name.ifBlank { fallback }
    }

    private val endpointCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val deviceId = parseDeviceId(info.endpointName)
            if (deviceId.isBlank() || deviceId == runCatching { selfId() }.getOrNull()) return

            val name = parseName(info.endpointName, "Android")
            endpoints[deviceId] = endpointId
            devices[endpointId] = deviceId
            names[endpointId] = name

            Diag.log("nearby", "найден: $name ($deviceId)")
            onPeer(NearbyPeer(deviceId, name, endpointId, System.currentTimeMillis()))
        }

        override fun onEndpointLost(endpointId: String) {
            val deviceId = devices.remove(endpointId) ?: return
            endpoints.remove(deviceId)
            names.remove(endpointId)
            Diag.log("nearby", "потерян: $deviceId")
            if (!connections.containsKey(endpointId)) onPeerLost(deviceId)
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val deviceId = parseDeviceId(info.endpointName)
                .ifBlank { devices[endpointId].orEmpty() }
            val name = parseName(info.endpointName, names[endpointId] ?: "Android")
            devices[endpointId] = deviceId
            names[endpointId] = name
            if (deviceId.isNotBlank()) endpoints[deviceId] = endpointId

            val trusted = deviceId.isNotBlank() && isTrusted(deviceId)
            Diag.log("nearby", "запрос соединения от $name, доверенное=$trusted")

            if (outgoing.remove(endpointId)) {
                acceptInternal(endpointId, deviceId)
            } else if (isNfcHandoff()) {
                Diag.log("nearby", "NFC: принимаем соединение от $name без подтверждения")
                acceptInternal(endpointId, deviceId)
            } else if (trusted) {
                acceptInternal(endpointId, deviceId)
            } else if (_incoming.value == null) {
                val request = NearbyRequest(
                    endpointId = endpointId,
                    deviceId = deviceId,
                    name = name,
                    trusted = false,
                )
                _incoming.value = request
                runCatching { onIncomingRequest(request) }
            } else {
                Diag.log("nearby", "заняты другим запросом — отклоняем")
                runCatching { client?.rejectConnection(endpointId) }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val ok = result.status.isSuccess
            val deviceId = devices[endpointId].orEmpty()
            if (ok) {
                connections[endpointId] = deviceId
                _connected.value = connections.values.toSet()
                Diag.log("nearby", "соединение установлено: ${names[endpointId]} ($deviceId)")
            } else {
                val code = result.status.statusCode
                Diag.log(
                    "nearby",
                    "соединение не удалось: ${statusName(code)} ($code) ${result.status.statusMessage}",
                )
                if (code == 8007 || code == 8012) {
                    resetRadio("connection: ${statusName(code)}")
                }
            }
            connectionWaiters.remove(endpointId)?.complete(ok)
        }

        override fun onDisconnected(endpointId: String) {
            val deviceId = connections.remove(endpointId) ?: devices[endpointId]
            _connected.value = connections.values.toSet()

            if (activeEndpointId == endpointId) {
                activeEndpointId = null
                isTransferring = false
                _progress.value = null
            }
            pendingFileNames.remove(endpointId)
            pendingFiles.entries.removeIf { (_, pending) -> pending.endpointId == endpointId }

            Diag.log("nearby", "отключились: $deviceId")

            scope.launch {
                delay(700)
                controlWaiters.remove(endpointId)?.complete("failed")
            }
            if (deviceId != null) onPeerLost(deviceId)
        }
    }

    private fun acceptInternal(endpointId: String, deviceId: String) {
        runCatching {
            client?.acceptConnection(endpointId, payloadCallback)
            devices[endpointId] = deviceId
            if (deviceId.isNotBlank()) endpoints.putIfAbsent(deviceId, endpointId)
        }.onFailure { Diag.log("nearby", "accept failed: $it") }
    }

    fun accept(endpointId: String) {
        val request = _incoming.value?.takeIf { it.endpointId == endpointId } ?: return
        _incoming.value = null
        activeEndpointId = endpointId
        acceptInternal(endpointId, request.deviceId)
    }

    fun decline(endpointId: String) {
        val request = _incoming.value?.takeIf { it.endpointId == endpointId }
        _incoming.value = null
        runCatching { client?.rejectConnection(endpointId) }
        runCatching { client?.disconnectFromEndpoint(endpointId) }
        connections.remove(endpointId)
        _connected.value = connections.values.toSet()
        if (activeEndpointId == endpointId) {
            activeEndpointId = null
            isTransferring = false
            _progress.value = null
        }
        NfcHelper.clearHandoff()
        Diag.log("nearby", "запрос отклонён: ${request?.name ?: endpointId}")
    }

    suspend fun send(
        deviceId: String,
        items: List<NearbyItem>,
        onProgress: (Float, String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (isTransferring) {
            return@withContext Result.failure(IllegalStateException("Уже идёт другая передача"))
        }
        isTransferring = true
        var endpoint: String? = null
        try {
            runCatching {
                require(items.isNotEmpty()) { "Нечего отправлять" }
                val client = client ?: error("Google Play Services недоступен")
                if (!running) start()

                val endpointId = beginTransfer(client, deviceId)
                    ?: error("Устройство не найдено поблизости")
                endpoint = endpointId
                activeEndpointId = endpointId

                if (!connections.containsKey(endpointId)) {
                    val peerName = names[endpointId] ?: "устройство"
                    onProgress(0f, "Соединяемся с $peerName…")
                    if (!connect(client, deviceId, endpointId)) error("Не удалось соединиться")
                }

                if (!sendToEndpoint(client, endpointId, items, onProgress)) {
                    error("Передача прервалась")
                }
            }.onFailure { error ->
                Diag.log("nearby", "send failed: $error")
                _progress.value = null
            }
        } finally {
            endpoint?.let { ep -> client?.let { disconnect(it, ep) } }
            isTransferring = false
            activeEndpointId = null
            NfcHelper.clearHandoff()
        }
    }

    /**
     * Clears stale endpoints before a new transfer, so a reverse transfer
     * (B -> A) does not collide with the previous session.
     */
    private suspend fun beginTransfer(
        client: ConnectionsClient,
        deviceId: String,
        name: String = "",
        waitMs: Long = DISCOVERY_TIMEOUT_MS,
    ): String? {
        connections.entries.firstOrNull { it.value == deviceId }?.key?.let { return it }

        val preferred = endpoints[deviceId]
        val preferredName = preferred?.let { names[it] }
        runCatching { client.stopDiscovery() }
        endpoints.entries.removeIf { (_, ep) -> !connections.containsKey(ep) }
        devices.entries.removeIf { (ep, _) -> !connections.containsKey(ep) }
        names.entries.removeIf { (ep, _) -> !connections.containsKey(ep) }
        if (preferred != null && preferredName != null) names[preferred] = preferredName
        outgoing.clear()

        if (running) {
            client.startDiscovery(
                SERVICE_ID,
                endpointCallback,
                DiscoveryOptions.Builder().setStrategy(strategy).build(),
            ).addOnFailureListener { error ->
                Diag.log("nearby", "startDiscovery failed: ${describe(error)}")
                resetRadio("discovery: ${describe(error)}")
            }
        }

        if (preferred == null) return awaitEndpoint(deviceId, name, waitMs)

        return awaitEndpoint(deviceId, name, minOf(waitMs, QUICK_DISCOVERY_MS)) ?: preferred
    }

    private suspend fun sendToEndpoint(
        client: ConnectionsClient,
        endpointId: String,
        items: List<NearbyItem>,
        onProgress: (Float, String) -> Unit,
    ): Boolean {
        val peerName = names[endpointId] ?: "устройство"
        items.forEachIndexed { index, item ->
            val payload = when (item) {
                is NearbyItem.Text -> {
                    val bytes = (TEXT_PREFIX + item.text).toByteArray()
                    require(bytes.size <= MAX_BYTES_PAYLOAD) {
                        "Текст больше ${MAX_BYTES_PAYLOAD / 1024} КБ — отправьте файлом"
                    }
                    Payload.fromBytes(bytes)
                }

                is NearbyItem.FileItem -> {
                    deliver(client, endpointId, Payload.fromBytes((FILE_PREFIX + item.name).toByteArray()))
                    Payload.fromFile(copyToCache(item.uri, item.name))
                }
            }

            val base = index.toFloat() / items.size
            val step = 1f / items.size
            _progress.value = NearbyProgress(
                peerName = peerName,
                fraction = base,
                text = "Передача ${index + 1} из ${items.size}…",
            )
            onProgress(base, "Передача ${index + 1} из ${items.size}…")

            if (!deliver(client, endpointId, payload)) return false

            _progress.value = NearbyProgress(
                peerName = peerName,
                fraction = base + step,
                text = "Готово",
            )
            onProgress(base + step, "Готово")
        }
        return true
    }

    private suspend fun deliver(
        client: ConnectionsClient,
        endpointId: String,
        payload: Payload,
    ): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        payloadWaiters[payload.id] = waiter
        client.sendPayload(endpointId, payload)
            .addOnFailureListener { error ->
                payloadWaiters.remove(payload.id)?.complete(false)
                Diag.log("nearby", "sendPayload failed: ${describe(error)}")
                val code = statusCode(error)
                if (code == 8007 || code == 8012 || code == 8013) {
                    resetRadio("payload: ${describe(error)}")
                }
            }
        val delivered = withTimeoutOrNull(PAYLOAD_TIMEOUT_MS) { waiter.await() } ?: false
        payloadWaiters.remove(payload.id)
        return delivered
    }

    private suspend fun connect(
        client: ConnectionsClient,
        deviceId: String,
        endpointId: String,
    ): Boolean {
        if (requestConnection(client, endpointId)) return true

        // One clean reset and one retry: a stale session is far more likely
        // than an actually broken radio.
        Diag.log("nearby", "переподключение после сброса: ${names[endpointId] ?: deviceId}")
        resetRadio("connect: ${names[endpointId] ?: deviceId}")
        if (running) startRadio(client, retry = false)
        val fresh = awaitEndpoint(deviceId, "", QUICK_DISCOVERY_MS) ?: endpointId
        return requestConnection(client, fresh)
    }

    private suspend fun requestConnection(
        client: ConnectionsClient,
        endpointId: String,
    ): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        connectionWaiters[endpointId] = waiter
        outgoing.add(endpointId)
        client.requestConnection(localName(), endpointId, lifecycleCallback)
            .addOnFailureListener { error ->
                Diag.log("nearby", "requestConnection failed: ${describe(error)}")
                outgoing.remove(endpointId)
                connectionWaiters.remove(endpointId)?.complete(false)
            }
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { waiter.await() } ?: false
        if (!ok) outgoing.remove(endpointId)
        return ok
    }

    suspend fun pull(
        deviceId: String,
        name: String,
        onAccepted: () -> Unit = {},
    ): Result<PullSummary> = withContext(Dispatchers.IO) {
        if (isTransferring) {
            return@withContext Result.failure(IllegalStateException("Уже идёт другая передача"))
        }
        isTransferring = true
        var endpoint: String? = null
        try {
            runCatching {
                val client = client ?: error("blocked")
                if (!running) start()

                Diag.log("nearby", "NFC: ищем отправителя $name ($deviceId)")
                val endpointId = beginTransfer(client, deviceId, name) ?: error("no-peer")
                endpoint = endpointId
                activeEndpointId = endpointId

                if (!connections.containsKey(endpointId)) {
                    if (!connect(client, deviceId, endpointId)) error("no-peer")
                }
                onAccepted()
                val peerName = names[endpointId] ?: name.ifBlank { "устройство" }
                Diag.log("nearby", "NFC: соединение с $peerName, просим пакет")

                val waiter = CompletableDeferred<String>()
                controlWaiters[endpointId] = waiter
                val counter = IntArray(2)
                pullCounters[endpointId] = counter
                try {
                    if (!deliver(client, endpointId, Payload.fromBytes(PULL_PREFIX.toByteArray()))) {
                        error("no-answer")
                    }
                    val answer = withTimeoutOrNull(PULL_TIMEOUT_MS) { waiter.await() }
                        ?: error("no-answer")
                    when (answer) {
                        "done" -> PullSummary(texts = counter[0], files = counter[1])
                        "empty" -> error("empty")
                        else -> error("failed")
                    }
                } finally {
                    controlWaiters.remove(endpointId)
                    pullCounters.remove(endpointId)
                    pullTotals.remove(endpointId)
                }
            }.onFailure { Diag.log("nearby", "pull failed: $it") }
        } finally {
            endpoint?.let { ep -> client?.let { disconnect(it, ep) } }
            isTransferring = false
            activeEndpointId = null
            NfcHelper.clearHandoff()
        }
    }

    private suspend fun awaitEndpoint(
        deviceId: String,
        name: String,
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS,
    ): String? {
        val startedAt = System.currentTimeMillis()
        var nudged = false
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            connections.entries.firstOrNull { it.value.isNotBlank() && it.value == deviceId }
                ?.key?.let { return it }
            if (deviceId.isNotBlank()) endpoints[deviceId]?.let { return it }
            if (name.isNotBlank()) {
                names.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }
                    ?.key?.let { return it }
            }
            val elapsed = System.currentTimeMillis() - startedAt
            if (!nudged && elapsed > 6_000L) {
                nudged = true
                Diag.log("nearby", "поиск отправителя: перезапускаем discovery")
                runCatching { client?.stopDiscovery() }
                runCatching {
                    client?.startDiscovery(
                        SERVICE_ID,
                        endpointCallback,
                        DiscoveryOptions.Builder().setStrategy(strategy).build(),
                    )
                }
            }
            delay(400)
        }
        return null
    }

    private fun servePull(endpointId: String) {
        scope.launch(Dispatchers.IO) {
            val connectionsClient = client ?: return@launch
            val peerName = names[endpointId] ?: "устройство"
            var sent = false
            try {
                val items = runCatching { pullProvider?.invoke() }.getOrNull().orEmpty()
                Diag.log("nearby", "NFC: $peerName просит пакет (${items.size} эл.)")

                if (items.isEmpty()) {
                    deliver(
                        connectionsClient,
                        endpointId,
                        Payload.fromBytes((ERROR_PREFIX + "empty").toByteArray()),
                    )
                    return@launch
                }

                deliver(
                    connectionsClient,
                    endpointId,
                    Payload.fromBytes((TOTAL_PREFIX + items.size).toByteArray()),
                )

                sent = sendToEndpoint(connectionsClient, endpointId, items) { fraction, stage ->
                    _progress.value = NearbyProgress(peerName, fraction, stage)
                }
                deliver(
                    connectionsClient,
                    endpointId,
                    Payload.fromBytes(
                        if (sent) DONE_PREFIX.toByteArray()
                        else (ERROR_PREFIX + "failed").toByteArray()
                    ),
                )
                Diag.log(
                    "nearby",
                    "NFC: пакет для $peerName ${if (sent) "отправлен" else "не ушёл"}",
                )
                if (sent) {
                    _delivered.value += 1
                    delay(300)
                } else {
                    _failed.value += 1
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Diag.log("nearby", "NFC: servePull failed: $t")
                if (!sent) _failed.value += 1
                runCatching {
                    deliver(
                        connectionsClient,
                        endpointId,
                        Payload.fromBytes((ERROR_PREFIX + "failed").toByteArray()),
                    )
                }
            } finally {
                _progress.value = null
                disconnect(connectionsClient, endpointId)
            }
        }
    }

    private fun disconnect(client: ConnectionsClient, endpointId: String) {
        connections.remove(endpointId)
        _connected.value = connections.values.toSet()
        runCatching { client.disconnectFromEndpoint(endpointId) }
    }

    private fun updatePullProgress(endpointId: String, peerName: String) {
        val total = pullTotals[endpointId]?.takeIf { it > 0 } ?: return
        val counter = pullCounters[endpointId] ?: return
        val done = (counter[0] + counter[1]).coerceAtMost(total)
        _progress.value = NearbyProgress(peerName, done.toFloat() / total, "")
    }

    /**
     * Drops a payload that never completed: the GMS temp file is discarded and
     * any partial copy of ours is deleted, so a truncated file is never kept.
     */
    private fun discardPending(payloadId: Long, endpointId: String) {
        val pending = pendingFiles.remove(payloadId)
        val name = pendingFileNames.remove(endpointId)
        if (name != null) {
            val safeName = name.substringAfterLast('/').ifBlank { "file" }
            val leftover = File(File(context.cacheDir, "nearby-in"), safeName)
            runCatching { leftover.delete() }
            Diag.log("nearby", "[ERR] файл $safeName не принят — частичный файл удалён")
        } else if (pending != null) {
            Diag.log("nearby", "[ERR] пейлоад $payloadId от ${pending.peerName} не принят")
        }
    }

    /**
     * Copies a finished Nearby file into Download/OpenBridge. A size mismatch
     * means the payload is unusable, so the file is deleted and reported.
     */
    private fun finalizeFile(pending: PendingFile) {
        val peerName = pending.peerName
        val name = pendingFileNames.remove(pending.endpointId)
            ?: pending.uri.lastPathSegment?.substringAfter('-')?.ifBlank { "file" }
            ?: "file"
        val safeName = name.substringAfterLast('/').ifBlank { "file" }
        val dir = File(context.cacheDir, "nearby-in").apply { mkdirs() }
        val incoming = File(dir, safeName)

        var copied = 0L
        val complete = runCatching {
            context.contentResolver.openInputStream(pending.uri)?.use { input ->
                incoming.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                    }
                    output.flush()
                }
            } ?: error("не удалось открыть файл")

            if (pending.expectedBytes >= 0 && copied != pending.expectedBytes) {
                error("получено $copied из ${pending.expectedBytes} байт")
            }
            true
        }.getOrElse { error ->
            Diag.log("nearby", "[ERR] файл $safeName не скопирован: ${error.message}")
            false
        }

        if (!complete) {
            runCatching { incoming.delete() }
            _events.value = NearbyEvent.Failed(peerName, "Файл $safeName пришёл не полностью")
            return
        }

        val mime = runCatching {
            java.net.URLConnection.guessContentTypeFromName(safeName)
        }.getOrNull() ?: "application/octet-stream"

        val saved = Downloads.save(context, incoming, safeName, mime)
        runCatching { incoming.delete() }

        if (saved == null) {
            Diag.log("nearby", "[ERR] файл $safeName не сохранён в Download")
            _events.value = NearbyEvent.Failed(peerName, "Не удалось сохранить $safeName")
            return
        }

        pullCounters[pending.endpointId]?.let { it[1]++ }
        updatePullProgress(pending.endpointId, peerName)

        val savedMime = runCatching { context.contentResolver.getType(saved) }.getOrNull() ?: mime
        Diag.log("nearby", "[OK] файл $safeName ($copied байт) -> $saved")
        _events.value = NearbyEvent.FileSaved(
            peerName = peerName,
            path = saved.toString(),
            bytes = copied,
            name = safeName,
            mime = savedMime,
        )
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val dir = File(context.cacheDir, "nearby").apply { mkdirs() }
        val safe = name.substringAfterLast('/').ifBlank { "file.bin" }
        val file = File(dir, "${System.currentTimeMillis()}-$safe")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        } ?: error("Не удалось открыть файл $name")
        return file
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val peerName = names[endpointId] ?: "устройство"
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val text = runCatching { String(payload.asBytes()!!) }.getOrNull().orEmpty()
                    when {
                        text.startsWith(PULL_PREFIX) -> {
                            Diag.log("nearby", "NFC: $peerName просит пакет")
                            servePull(endpointId)
                        }

                        text.startsWith(TOTAL_PREFIX) -> {
                            val total = text.removePrefix(TOTAL_PREFIX).trim().toIntOrNull() ?: 0
                            if (total > 0) pullTotals[endpointId] = total
                        }

                        text.startsWith(DONE_PREFIX) -> {
                            controlWaiters.remove(endpointId)?.complete("done")
                        }

                        text.startsWith(ERROR_PREFIX) -> {
                            controlWaiters.remove(endpointId)
                                ?.complete(text.removePrefix(ERROR_PREFIX).trim())
                        }

                        text.startsWith(FILE_PREFIX) -> {
                            val name = text.removePrefix(FILE_PREFIX).trim()
                            if (name.isNotBlank()) pendingFileNames[endpointId] = name
                        }

                        else -> {
                            val clean = text.removePrefix(TEXT_PREFIX)
                            pullCounters[endpointId]?.let { it[0]++ }
                            updatePullProgress(endpointId, peerName)
                            Diag.log("nearby", "получен текст от $peerName (${clean.length} симв.)")
                            _events.value = NearbyEvent.Text(peerName, clean)
                        }
                    }
                }

                Payload.Type.FILE -> {
                    val file = payload.asFile()
                    val uri = runCatching { file?.asUri() }.getOrNull()
                    if (uri == null) {
                        Diag.log("nearby", "[ERR] файл от $peerName без uri")
                        _events.value = NearbyEvent.Failed(peerName, "Не удалось принять файл")
                        return
                    }

                    val expected = runCatching { file?.size ?: -1L }.getOrDefault(-1L)
                    pendingFiles[payload.id] = PendingFile(
                        endpointId = endpointId,
                        peerName = peerName,
                        uri = uri,
                        expectedBytes = expected,
                    )
                    Diag.log(
                        "nearby",
                        "файл от $peerName принят в очередь (${payload.id}, $expected байт)",
                    )
                }

                else -> Diag.log("nearby", "payload type=${payload.type} не поддержан")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val waiter = payloadWaiters[update.payloadId]
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    waiter?.complete(true)
                    pendingFiles.remove(update.payloadId)?.let { pending ->
                        scope.launch(Dispatchers.IO) { finalizeFile(pending) }
                    }
                }

                PayloadTransferUpdate.Status.FAILURE -> {
                    waiter?.complete(false)
                    discardPending(update.payloadId, endpointId)
                    controlWaiters.remove(endpointId)?.complete("failed")
                    if (activeEndpointId == endpointId) _progress.value = null
                    _events.value = NearbyEvent.Failed(
                        names[endpointId] ?: "устройство",
                        "получатель не принял данные",
                    )
                }

                PayloadTransferUpdate.Status.CANCELED -> {
                    waiter?.complete(false)
                    val dropped = pendingFiles[update.payloadId]
                    discardPending(update.payloadId, endpointId)
                    controlWaiters.remove(endpointId)?.complete("failed")
                    if (activeEndpointId == endpointId) _progress.value = null
                    Diag.log("nearby", "[ERR] передача отменена (${dropped?.peerName ?: endpointId})")
                    _events.value = NearbyEvent.Failed(
                        names[endpointId] ?: dropped?.peerName ?: "устройство",
                        "Передача отменена",
                    )
                }

                else -> {
                }
            }
        }
    }
}
