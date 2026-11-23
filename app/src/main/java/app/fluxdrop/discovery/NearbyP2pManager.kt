package app.fluxdrop.discovery

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import app.fluxdrop.core.Diag
import app.fluxdrop.net.Downloads
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

/** Устройство, найденное по Nearby (без общей сети). */
@Immutable
data class NearbyPeer(
    val deviceId: String,
    val name: String,
    val endpointId: String,
    val lastSeen: Long,
)

/** Входящий запрос на соединение Nearby: показываем шторку. */
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

/** Что пришло по Nearby — разбирает ViewModel (буфер, история, сообщение). */
sealed interface NearbyEvent {
    data class Text(val peerName: String, val text: String) : NearbyEvent
    data class FileSaved(val peerName: String, val path: String, val bytes: Long) : NearbyEvent
    data class Failed(val peerName: String, val reason: String) : NearbyEvent
}

sealed interface NearbyItem {
    data class Text(val text: String) : NearbyItem
    data class FileItem(val uri: Uri, val name: String) : NearbyItem
}

/**
 * Обнаружение и передача без роутера через Google Nearby Connections.
 *
 * Под капотом Google сам комбинирует BLE (advertising/scanning), Wi-Fi Direct и
 * локальный хотспот, поэтому устройства находят друг друга за пару секунд в чистом поле.
 *
 * В эфир уходит имя вида "deviceId|Имя устройства" — по нему мы дедуплицируем
 * устройство с LAN-находкой и понимаем, доверенное ли оно.
 */
class NearbyP2pManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val selfId: () -> String,
    private val selfName: () -> String,
    private val isTrusted: (String) -> Boolean,
    private val onPeer: (NearbyPeer) -> Unit,
    private val onPeerLost: (String) -> Unit,
) {

    private companion object {
        const val SERVICE_ID = "app.fluxdrop.nearby"
        const val NAME_SEPARATOR = "|"
        const val TEXT_PREFIX = "FD/TEXT:"
        const val FILE_PREFIX = "FD/FILE:"
        const val MAX_ENDPOINT_NAME = 120
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val PAYLOAD_TIMEOUT_MS = 180_000L
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

    /** Эндпоинты, к которым соединяемся мы сами: их запрос принимаем молча. */
    private val outgoing = ConcurrentHashMap.newKeySet<String>()

    /** Имя файла, которое приходит отдельным байтовым пакетом перед самим файлом. */
    private val pendingFileNames = ConcurrentHashMap<String, String>()

    private val connectionWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val payloadWaiters = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

    private val _incoming = MutableStateFlow<NearbyRequest?>(null)
    val incoming: StateFlow<NearbyRequest?> = _incoming.asStateFlow()

    private val _progress = MutableStateFlow<NearbyProgress?>(null)
    val progress: StateFlow<NearbyProgress?> = _progress.asStateFlow()

    private val _events = MutableStateFlow<NearbyEvent?>(null)
    val events: StateFlow<NearbyEvent?> = _events.asStateFlow()

    private val _connected = MutableStateFlow<Set<String>>(emptySet())
    val connected: StateFlow<Set<String>> = _connected.asStateFlow()

    @Volatile
    private var running = false

    private var refreshJob: Job? = null

    fun isAvailable(): Boolean = client != null

    fun isKnown(deviceId: String): Boolean =
        endpoints.containsKey(deviceId) || connections.containsValue(deviceId)

    fun consumeEvent() {
        _events.value = null
    }

    // ── запуск / остановка ───────────────────────────────────────────────

    fun start() {
        val client = client ?: run {
            Diag.log("nearby", "Google Play Services недоступен — Nearby выключен")
            return
        }
        if (running) return
        running = true

        val advertising = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        val discovery = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()

        client.startAdvertising(localName(), SERVICE_ID, lifecycleCallback, advertising)
            .addOnSuccessListener { Diag.log("nearby", "startAdvertising ok") }
            .addOnFailureListener { error ->
                Diag.log("nearby", "startAdvertising failed: $error")
                // Если уже рекламировались — перезапускаем
                if (error.message?.contains("8001") == true || error.message?.contains("ALREADY") == true) {
                    client.stopAdvertising()
                    client.startAdvertising(localName(), SERVICE_ID, lifecycleCallback, advertising)
                }
            }

        client.startDiscovery(SERVICE_ID, endpointCallback, discovery)
            .addOnSuccessListener { Diag.log("nearby", "startDiscovery ok") }
            .addOnFailureListener { error ->
                Diag.log("nearby", "startDiscovery failed: $error")
                // Если уже вели поиск — перезапускаем
                if (error.message?.contains("8002") == true || error.message?.contains("ALREADY") == true) {
                    client.stopDiscovery()
                    client.startDiscovery(SERVICE_ID, endpointCallback, discovery)
                }
            }

        // Nearby сообщает о находке один раз, а список на экране живёт по lastSeen.
        // Освежаем отметку, иначе устройство «исчезнет» через 20 секунд.
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
        _connected.value = emptySet()
        _incoming.value = null
        _progress.value = null
        Diag.log("nearby", "остановлен")
    }

    /** Имя в эфире: deviceId|Имя — так мы склеиваем находки LAN и Nearby. */
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

    // ── поиск ────────────────────────────────────────────────────────────

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

    // ── соединения ───────────────────────────────────────────────────────

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

            // Инициатор тоже получает onConnectionInitiated — молча принимаем свой же запрос.
            if (outgoing.remove(endpointId)) {
                acceptInternal(endpointId, deviceId)
            } else if (trusted) {
                // Доверенному устройству отвечаем сразу, без шторки.
                acceptInternal(endpointId, deviceId)
            } else if (_incoming.value == null) {
                _incoming.value = NearbyRequest(
                    endpointId = endpointId,
                    deviceId = deviceId,
                    name = name,
                    trusted = false,
                )
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
                Diag.log("nearby", "соединение не удалось: ${result.status.statusMessage}")
            }
            connectionWaiters.remove(endpointId)?.complete(ok)
        }

        override fun onDisconnected(endpointId: String) {
            val deviceId = connections.remove(endpointId) ?: devices[endpointId]
            _connected.value = connections.values.toSet()
            Diag.log("nearby", "отключились: $deviceId")
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

    /** Пользователь нажал «Принять» в шторке. */
    fun accept(endpointId: String) {
        val request = _incoming.value?.takeIf { it.endpointId == endpointId } ?: return
        _incoming.value = null
        acceptInternal(endpointId, request.deviceId)
    }

    fun decline(endpointId: String) {
        val request = _incoming.value?.takeIf { it.endpointId == endpointId } ?: return
        _incoming.value = null
        runCatching { client?.rejectConnection(endpointId) }
        Diag.log("nearby", "запрос отклонён: ${request.name}")
    }

    // ── отправка ─────────────────────────────────────────────────────────

    /**
     * Отправляет текст и файлы на устройство Nearby.
     * Папки сюда не передаются: их умеет только LAN-канал.
     */
    suspend fun send(
        deviceId: String,
        items: List<NearbyItem>,
        onProgress: (Float, String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(items.isNotEmpty()) { "Нечего отправлять" }
            val client = client ?: error("Google Play Services недоступен")

            val endpointId = endpoints[deviceId]
                ?: connections.entries.firstOrNull { it.value == deviceId }?.key
                ?: error("Устройство не найдено поблизости")

            if (!connections.containsKey(endpointId)) {
                val peerName = names[endpointId] ?: "устройство"
                onProgress(0f, "Соединяемся с $peerName…")
                if (!connect(client, endpointId)) error("Не удалось соединиться")
            }

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
                        // Имя файла передаём отдельно: Nearby сохраняет входящие под своим именем.
                        deliver(client, endpointId, Payload.fromBytes((FILE_PREFIX + item.name).toByteArray()))
                        Payload.fromFile(copyToCache(item.uri, item.name))
                    }
                }

                val base = index.toFloat() / items.size
                val step = 1f / items.size
                _progress.value = NearbyProgress(
                    peerName = names[endpointId] ?: "устройство",
                    fraction = base,
                    text = "Передача ${index + 1} из ${items.size}…",
                )
                onProgress(base, "Передача ${index + 1} из ${items.size}…")

                if (!deliver(client, endpointId, payload)) {
                    error("Передача прервалась на шаге ${index + 1}")
                }

                _progress.value = NearbyProgress(
                    peerName = names[endpointId] ?: "устройство",
                    fraction = base + step,
                    text = "Готово",
                )
                onProgress(base + step, "Готово")
            }
        }.onFailure { error ->
            Diag.log("nearby", "send failed: $error")
            _progress.value = null
        }
    }

    /** Отправляет один payload и ждёт подтверждения или ошибки. */
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
                Diag.log("nearby", "sendPayload failed: $error")
            }
        val delivered = withTimeoutOrNull(PAYLOAD_TIMEOUT_MS) { waiter.await() } ?: false
        payloadWaiters.remove(payload.id)
        return delivered
    }

    private suspend fun connect(client: ConnectionsClient, endpointId: String): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        connectionWaiters[endpointId] = waiter
        outgoing.add(endpointId)
        client.requestConnection(localName(), endpointId, lifecycleCallback)
            .addOnFailureListener { error ->
                Diag.log("nearby", "requestConnection failed: $error")
                outgoing.remove(endpointId)
                connectionWaiters.remove(endpointId)?.complete(false)
            }
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { waiter.await() } ?: false
        if (!ok) outgoing.remove(endpointId)
        return ok
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

    // ── приём ────────────────────────────────────────────────────────────

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val peerName = names[endpointId] ?: "устройство"
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val text = runCatching { String(payload.asBytes()!!) }.getOrNull().orEmpty()
                    if (text.startsWith(FILE_PREFIX)) {
                        val name = text.removePrefix(FILE_PREFIX).trim()
                        if (name.isNotBlank()) pendingFileNames[endpointId] = name
                    } else {
                        val clean = text.removePrefix(TEXT_PREFIX)
                        Diag.log("nearby", "получен текст от $peerName (${clean.length} симв.)")
                        _events.value = NearbyEvent.Text(peerName, clean)
                    }
                }

                Payload.Type.FILE -> {
                    val uri = payload.asFile()?.asUri() ?: return
                    scope.launch(Dispatchers.IO) {
                        val name = pendingFileNames.remove(endpointId)
                            ?: uri.lastPathSegment?.substringAfter('-')?.ifBlank { "file" }
                            ?: "file"
                        val temp = File(context.cacheDir, "nearby-in").apply { mkdirs() }
                        val incoming = File(temp, name)
                        val copied = runCatching {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                incoming.outputStream().use { output -> input.copyTo(output) }
                            }
                            incoming.length()
                        }.getOrDefault(0L)

                        val saved = if (copied > 0) {
                            Downloads.save(context, incoming, name, "application/octet-stream")
                        } else {
                            null
                        }
                        runCatching { incoming.delete() }
                        Diag.log("nearby", "получен файл $name -> $saved")
                        _events.value = NearbyEvent.FileSaved(
                            peerName = peerName,
                            path = saved?.toString().orEmpty(),
                            bytes = copied,
                        )
                    }
                }

                else -> Diag.log("nearby", "payload type=${payload.type} не поддержан")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val waiter = payloadWaiters[update.payloadId]
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> waiter?.complete(true)
                PayloadTransferUpdate.Status.FAILURE -> {
                    waiter?.complete(false)
                    _events.value = NearbyEvent.Failed(
                        names[endpointId] ?: "устройство",
                        "получатель не принял данные",
                    )
                }

                else -> {
                    val total = update.totalBytes.takeIf { it > 0 } ?: return
                    val peerName = names[endpointId] ?: "устройство"
                    _progress.value = NearbyProgress(
                        peerName = peerName,
                        fraction = update.bytesTransferred.toFloat() / total,
                        text = "Передача…",
                    )
                }
            }
        }
    }
}
