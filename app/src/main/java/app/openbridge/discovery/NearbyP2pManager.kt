package app.openbridge.discovery

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import app.openbridge.core.Diag
import app.openbridge.core.TransferDirection
import app.openbridge.core.TransferMonitor
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
    data class Text(
        val peerName: String,
        val text: String,
        val transport: String = "nearby",
    ) : NearbyEvent
    data class FileSaved(
        val peerName: String,
        val path: String,
        val bytes: Long,
        val name: String = "",
        val mime: String = "application/octet-stream",
        val transport: String = "nearby",
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
    private val onSent: (items: List<NearbyItem>, recipientName: String) -> Unit = { _, _ -> },
    private val monitor: TransferMonitor = TransferMonitor(),
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

    private val endpoints = ConcurrentHashMap<String, String>()
    private val devices = ConcurrentHashMap<String, String>()
    private val names = ConcurrentHashMap<String, String>()
    private val connections = ConcurrentHashMap<String, String>()

    private val outgoing = ConcurrentHashMap.newKeySet<String>()

    private val pendingFileNames = ConcurrentHashMap<String, String>()

    private class PayloadProgress(val name: String, val base: Long)
    private val payloadProgress = ConcurrentHashMap<Long, PayloadProgress>()

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

    private val pullTargets = ConcurrentHashMap.newKeySet<String>()

    private fun transportOf(endpointId: String): String =
        if (endpointId in pullTargets || isNfcHandoff()) "nfc" else "nearby"

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

    @Volatile
    private var isTransferring = false

    @Volatile
    private var activeEndpointId: String? = null

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
            Diag.log("nearby", "Google Play Services unavailable - Nearby disabled")
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

    private fun resetRadio(reason: String) {
        Diag.log("nearby", "Nearby reset: $reason")
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
        Diag.log("nearby", "stopped")
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

            Diag.log("nearby", "found: $name ($deviceId)")
            onPeer(NearbyPeer(deviceId, name, endpointId, System.currentTimeMillis()))
        }

        override fun onEndpointLost(endpointId: String) {
            val deviceId = devices.remove(endpointId) ?: return
            endpoints.remove(deviceId)
            names.remove(endpointId)
            Diag.log("nearby", "lost: $deviceId")
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
            Diag.log("nearby", "connection request from $name, trusted=$trusted")

            if (outgoing.remove(endpointId)) {
                acceptInternal(endpointId, deviceId)
            } else if (isNfcHandoff()) {
                Diag.log("nearby", "NFC: accepting connection from $name without confirmation")
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
                Diag.log("nearby", "busy with another request - rejecting")
                runCatching { client?.rejectConnection(endpointId) }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val ok = result.status.isSuccess
            val deviceId = devices[endpointId].orEmpty()
            if (ok) {
                connections[endpointId] = deviceId
                _connected.value = connections.values.toSet()
                Diag.log("nearby", "connected: ${names[endpointId]} ($deviceId)")
            } else {
                val code = result.status.statusCode
                Diag.log(
                    "nearby",
                    "connection failed: ${statusName(code)} ($code) ${result.status.statusMessage}",
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

            Diag.log("nearby", "disconnected: $deviceId")

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
        Diag.log("nearby", "request declined: ${request?.name ?: endpointId}")
    }

    suspend fun send(
        deviceId: String,
        items: List<NearbyItem>,
        onProgress: (Float, String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (isTransferring) {
            return@withContext Result.failure(IllegalStateException("Another transfer is already running"))
        }
        isTransferring = true
        var endpoint: String? = null
        try {
            runCatching {
                require(items.isNotEmpty()) { "Nothing to send" }
                val client = client ?: error("Google Play Services unavailable")
                if (!running) start()

                val endpointId = beginTransfer(client, deviceId)
                    ?: error("Device not found nearby")
                endpoint = endpointId
                activeEndpointId = endpointId

                if (!connections.containsKey(endpointId)) {
                    val peerName = names[endpointId] ?: "устройство"
                    onProgress(0f, "Соединяемся с $peerName…")
                    if (!connect(client, deviceId, endpointId)) error("Could not connect")
                }

                if (!sendToEndpoint(client, endpointId, items, onProgress)) {
                    error("Transfer interrupted")
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
        val total = items.sumOf { itemBytes(it) }
        monitor.start(
            direction = TransferDirection.SENDING,
            transport = "nearby",
            peerName = peerName,
            totalBytes = total,
            currentFileName = itemName(items.firstOrNull()),
            filesTotal = items.size,
        )
        try {
            var base = 0L
            items.forEachIndexed { index, item ->
                val name = itemName(item)
                monitor.update(base, name, filesDone = index)

                val payload = when (item) {
                    is NearbyItem.Text -> {
                        val bytes = (TEXT_PREFIX + item.text).toByteArray()
                        require(bytes.size <= MAX_BYTES_PAYLOAD) {
                            "Text is larger than ${MAX_BYTES_PAYLOAD / 1024} KB - send it as a file"
                        }
                        Payload.fromBytes(bytes)
                    }

                    is NearbyItem.FileItem -> {
                        deliver(
                            client,
                            endpointId,
                            Payload.fromBytes((FILE_PREFIX + item.name).toByteArray()),
                        )
                        Payload.fromFile(copyToCache(item.uri, item.name))
                    }
                }

                val fraction = if (total > 0L) {
                    (base.toFloat() / total).coerceIn(0f, 1f)
                } else {
                    index.toFloat() / items.size
                }
                val stageText = "Передача ${index + 1} из ${items.size}…"
                _progress.value = NearbyProgress(peerName, fraction, stageText)
                onProgress(fraction, stageText)

                payloadProgress[payload.id] = PayloadProgress(name, base)
                val delivered = try {
                    deliver(client, endpointId, payload)
                } finally {
                    payloadProgress.remove(payload.id)
                }
                if (!delivered) return false

                base += itemBytes(item)
                monitor.update(base, filesDone = index + 1)

                val doneFraction = if (total > 0L) {
                    (base.toFloat() / total).coerceIn(0f, 1f)
                } else {
                    (index + 1).toFloat() / items.size
                }
                _progress.value = NearbyProgress(peerName, doneFraction, "Готово")
                onProgress(doneFraction, "Готово")
            }
            return true
        } finally {
            monitor.reset()
        }
    }

    private fun itemBytes(item: NearbyItem): Long = when (item) {
        is NearbyItem.Text -> (TEXT_PREFIX + item.text).toByteArray().size.toLong()
        is NearbyItem.FileItem -> fileSize(item.uri) + (FILE_PREFIX + item.name).toByteArray().size
    }

    private fun itemName(item: NearbyItem?): String = when (item) {
        is NearbyItem.FileItem -> item.name
        is NearbyItem.Text -> "Текст"
        null -> ""
    }

    private fun fileSize(uri: Uri): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }.getOrDefault(-1L).takeIf { it > 0L } ?: 0L

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

        Diag.log("nearby", "reconnecting after reset: ${names[endpointId] ?: deviceId}")
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
            return@withContext Result.failure(IllegalStateException("Another transfer is already running"))
        }
        isTransferring = true
        var endpoint: String? = null
        try {
            runCatching {
                val client = client ?: error("blocked")
                if (!running) start()

                Diag.log("nearby", "NFC: looking for sender $name ($deviceId)")
                val endpointId = beginTransfer(client, deviceId, name) ?: error("no-peer")
                endpoint = endpointId
                activeEndpointId = endpointId
                pullTargets.add(endpointId)

                if (!connections.containsKey(endpointId)) {
                    if (!connect(client, deviceId, endpointId)) error("no-peer")
                }
                onAccepted()
                val peerName = names[endpointId] ?: name.ifBlank { "устройство" }
                Diag.log("nearby", "NFC: connected to $peerName, requesting package")

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
            endpoint?.let { ep ->
                pullTargets.remove(ep)
                client?.let { disconnect(it, ep) }
            }
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
                Diag.log("nearby", "sender search: restarting discovery")
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
                Diag.log("nearby", "NFC: $peerName requests package (${items.size} items)")

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
                    "NFC: package for $peerName ${if (sent) "sent" else "failed"}",
                )
                if (sent) {
                    _delivered.value += 1
                    runCatching { onSent(items, peerName) }
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

    private fun discardPending(payloadId: Long, endpointId: String) {
        val pending = pendingFiles.remove(payloadId)
        val name = pendingFileNames.remove(endpointId)
        if (name != null) {
            val safeName = name.substringAfterLast('/').ifBlank { "file" }
            val leftover = File(File(context.cacheDir, "nearby-in"), safeName)
            runCatching { leftover.delete() }
            Diag.log("nearby", "[ERR] file $safeName not accepted - partial file deleted")
        } else if (pending != null) {
            Diag.log("nearby", "[ERR] payload $payloadId from ${pending.peerName} not accepted")
        }
    }

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
            } ?: error("could not open file")

            if (pending.expectedBytes >= 0 && copied != pending.expectedBytes) {
                error("received $copied of ${pending.expectedBytes} bytes")
            }
            true
        }.getOrElse { error ->
            Diag.log("nearby", "[ERR] file $safeName not copied: ${error.message}")
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
            Diag.log("nearby", "[ERR] file $safeName not saved to Download")
            _events.value = NearbyEvent.Failed(peerName, "Не удалось сохранить $safeName")
            return
        }

        pullCounters[pending.endpointId]?.let { it[1]++ }
        updatePullProgress(pending.endpointId, peerName)

        val savedMime = runCatching { context.contentResolver.getType(saved) }.getOrNull() ?: mime
        Diag.log("nearby", "[OK] file $safeName ($copied bytes) -> $saved")
        _events.value = NearbyEvent.FileSaved(
            peerName = peerName,
            path = saved.toString(),
            bytes = copied,
            name = safeName,
            mime = savedMime,
            transport = transportOf(pending.endpointId),
        )
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val dir = File(context.cacheDir, "nearby").apply { mkdirs() }
        val safe = name.substringAfterLast('/').ifBlank { "file.bin" }
        val file = File(dir, "${System.currentTimeMillis()}-$safe")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        } ?: error("Could not open file $name")
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
                            Diag.log("nearby", "NFC: $peerName requests package")
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
                            Diag.log("nearby", "text received from $peerName (${clean.length} chars)")
                            _events.value = NearbyEvent.Text(
                                peerName = peerName,
                                text = clean,
                                transport = transportOf(endpointId),
                            )
                        }
                    }
                }

                Payload.Type.FILE -> {
                    val file = payload.asFile()
                    val uri = runCatching { file?.asUri() }.getOrNull()
                    if (uri == null) {
                        Diag.log("nearby", "[ERR] file from $peerName has no uri")
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
                    val fileName = pendingFileNames[endpointId]
                        ?.substringAfterLast('/')
                        ?.ifBlank { "файл" }
                        ?: "файл"
                    monitor.start(
                        direction = TransferDirection.RECEIVING,
                        transport = transportOf(endpointId),
                        peerName = peerName,
                        totalBytes = expected.takeIf { it > 0L } ?: 0L,
                        currentFileName = fileName,
                        filesTotal = 1,
                    )
                    Diag.log(
                        "nearby",
                        "file from $peerName queued (${payload.id}, $expected bytes)",
                    )
                }

                else -> Diag.log("nearby", "unsupported payload type=${payload.type}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val waiter = payloadWaiters[update.payloadId]
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    payloadProgress[update.payloadId]?.let { outgoing ->
                        monitor.update(
                            bytesTransferred = outgoing.base + update.bytesTransferred,
                            currentFileName = outgoing.name,
                        )
                    }
                    pendingFiles[update.payloadId]?.let { incoming ->
                        monitor.update(
                            bytesTransferred = update.bytesTransferred,
                            currentFileName = pendingFileNames[incoming.endpointId]
                                ?.substringAfterLast('/')
                                ?.ifBlank { "файл" }
                                ?: "файл",
                            totalBytes = update.totalBytes.takeIf { it > 0L },
                        )
                    }
                }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    waiter?.complete(true)
                    val received = pendingFiles.remove(update.payloadId)
                    if (received != null) {
                        monitor.reset()
                        scope.launch(Dispatchers.IO) { finalizeFile(received) }
                    }
                }

                PayloadTransferUpdate.Status.FAILURE -> {
                    waiter?.complete(false)
                    val wasIncoming = pendingFiles.containsKey(update.payloadId)
                    discardPending(update.payloadId, endpointId)
                    if (wasIncoming) monitor.reset()
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
                    if (dropped != null) monitor.reset()
                    controlWaiters.remove(endpointId)?.complete("failed")
                    if (activeEndpointId == endpointId) _progress.value = null
                    Diag.log("nearby", "[ERR] transfer canceled (${dropped?.peerName ?: endpointId})")
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
