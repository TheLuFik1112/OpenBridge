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

/** Итог запроса пакета: сколько элементов реально дошло. */
data class PullSummary(val texts: Int, val files: Int)

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
 *
 * После касания NFC получатель не ждёт, пока ему что-то предложат: он находит
 * отправителя по id из метки, соединяется и просит пакет ([pull]). Отправитель
 * в ответ выкладывает очередь ([servePull]) — это и есть передача «в один тап».
 */
class NearbyP2pManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val selfId: () -> String,
    private val selfName: () -> String,
    private val isTrusted: (String) -> Boolean,
    private val onPeer: (NearbyPeer) -> Unit,
    private val onPeerLost: (String) -> Unit,
    /** Метку только что прочитали: соединение по NFC принимаем сами. */
    private val isNfcHandoff: () -> Boolean = { false },
) {

    private companion object {
        const val SERVICE_ID = "app.fluxdrop.nearby"
        const val NAME_SEPARATOR = "|"
        const val TEXT_PREFIX = "FD/TEXT:"
        const val FILE_PREFIX = "FD/FILE:"
        const val PULL_PREFIX = "FD/PULL"
        const val DONE_PREFIX = "FD/DONE"
        const val TOTAL_PREFIX = "FD/TOTAL:"
        const val ERROR_PREFIX = "FD/ERR:"
        const val MAX_ENDPOINT_NAME = 120
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val DISCOVERY_TIMEOUT_MS = 22_000L
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

    /** Эндпоинты, к которым соединяемся мы сами: их запрос принимаем молча. */
    private val outgoing = ConcurrentHashMap.newKeySet<String>()

    /** Имя файла, которое приходит отдельным байтовым пакетом перед самим файлом. */
    private val pendingFileNames = ConcurrentHashMap<String, String>()

    private val connectionWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val payloadWaiters = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

    /** Ожидание ответа отправителя на запрос пакета: "done", "empty" или текст ошибки. */
    private val controlWaiters = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Счётчики принятого во время запроса: [0] тексты, [1] файлы. */
    private val pullCounters = ConcurrentHashMap<String, IntArray>()

    /** Сколько всего элементов выкладывает отправитель: для полосы прогресса. */
    private val pullTotals = ConcurrentHashMap<String, Int>()

    /**
     * Что отдавать, если получатель просит пакет после касания NFC.
     * Ставит ViewModel: очередь живёт в нём, а не в графе.
     */
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

    /** Пакет ушёл получателю, который пришёл по NFC: для «Отправлено» в шторке. */
    private val _delivered = MutableStateFlow(0)
    val delivered: StateFlow<Int> = _delivered.asStateFlow()

    /** Пакет не дошёл по NFC: шторка должна сказать об этом, а не висеть. */
    private val _failed = MutableStateFlow(0)
    val failed: StateFlow<Int> = _failed.asStateFlow()

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

    /** @Synchronized: после касания NFC поиск стартует и из pull, и из движка. */
    @Synchronized
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
        controlWaiters.clear()
        pullCounters.clear()
        pullTotals.clear()
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
            } else if (isNfcHandoff()) {
                // Метку только что прочитали, значит человек сам поднёс телефон:
                // не заставляем его ещё раз нажимать «Принять».
                Diag.log("nearby", "NFC: принимаем соединение от $name без подтверждения")
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
            // Даём последнему пакету (например, «готово» после NFC) дойти первым.
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

            if (!sendToEndpoint(client, endpointId, items, onProgress)) {
                error("Передача прервалась")
            }

            if (!isTrusted(deviceId)) {
                // Если устройство не доверенное — закрываем сессию после передачи,
                // чтобы при следующей отправке получателю снова показалась шторка с подтверждением.
                delay(500)
                disconnect(client, endpointId)
            }
        }.onFailure { error ->
            Diag.log("nearby", "send failed: $error")
            _progress.value = null
        }
    }

    /** Выкладывает элементы уже установленному соединению. true — всё доехало. */
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
                    // Имя файла передаём отдельно: Nearby сохраняет входящие под своим именем.
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

    // ── пакет по NFC: получатель просит, отправитель выкладывает ─────────

    /**
     * Получатель нашёл метку отправителя и забирает его пакет.
     *
     * Никакого «предложения» от отправителя нет: сначала ищем его в эфире
     * по id (или имени) из метки, соединяемся и отправляем запрос. Дальше
     * отправитель сам выкладывает очередь, а мы принимаем её обычным путём.
     */
    suspend fun pull(
        deviceId: String,
        name: String,
        onAccepted: () -> Unit = {},
    ): Result<PullSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val client = client ?: error("blocked")
            if (!running) start()

            Diag.log("nearby", "NFC: ищем отправителя $name ($deviceId)")
            val endpointId = awaitEndpoint(deviceId, name) ?: error("no-peer")

            if (!connections.containsKey(endpointId)) {
                if (!connect(client, endpointId)) error("no-peer")
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
                if (!isTrusted(deviceId)) disconnect(client, endpointId)
            }
        }.onFailure { Diag.log("nearby", "pull failed: $it") }
    }

    /**
     * Ждёт, пока отправитель появится в эфире. Nearby отдаёт находки не сразу,
     * поэтому после касания приходится подождать пару секунд.
     */
    private suspend fun awaitEndpoint(deviceId: String, name: String): String? {
        val startedAt = System.currentTimeMillis()
        var nudged = false
        while (System.currentTimeMillis() - startedAt < DISCOVERY_TIMEOUT_MS) {
            connections.entries.firstOrNull { it.value.isNotBlank() && it.value == deviceId }
                ?.key?.let { return it }
            if (deviceId.isNotBlank()) endpoints[deviceId]?.let { return it }
            if (name.isNotBlank()) {
                names.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }
                    ?.key?.let { return it }
            }
            val elapsed = System.currentTimeMillis() - startedAt
            if (!nudged && elapsed > 6_000L) {
                // Поиск иногда «залипает» после переключения экрана — перезапускаем.
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

    /** Отправитель: выкладываем очередь тому, кто пришёл по метке. */
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

                // Говорим получателю, сколько всего элементов — иначе у него
                // полоса прогресса стояла бы на нуле до самого конца.
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
                // Ошибка в отдельной корутине не должна ронять процесс и оставлять
                // получателя в вечном ожидании — говорим ему, что передача сорвалась.
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

    /** Получатель во время запроса: доля принятого от объявленного отправителем. */
    private fun updatePullProgress(endpointId: String, peerName: String) {
        val total = pullTotals[endpointId]?.takeIf { it > 0 } ?: return
        val counter = pullCounters[endpointId] ?: return
        val done = (counter[0] + counter[1]).coerceAtMost(total)
        _progress.value = NearbyProgress(peerName, done.toFloat() / total, "")
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
                    when {
                        // Получатель пришёл по NFC и просит пакет.
                        text.startsWith(PULL_PREFIX) -> {
                            Diag.log("nearby", "NFC: $peerName просит пакет")
                            servePull(endpointId)
                        }

                        // Отправитель сказал, сколько всего будет элементов.
                        text.startsWith(TOTAL_PREFIX) -> {
                            val total = text.removePrefix(TOTAL_PREFIX).trim().toIntOrNull() ?: 0
                            if (total > 0) pullTotals[endpointId] = total
                        }

                        // Отправитель закончил выкладывать пакет.
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
                    val uri = payload.asFile()?.asUri() ?: return
                    // Считаем сразу: файл уже у нас, сохранение идёт своим чередом.
                    pullCounters[endpointId]?.let { it[1]++ }
                    updatePullProgress(endpointId, peerName)
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
                    controlWaiters.remove(endpointId)?.complete("failed")
                    _events.value = NearbyEvent.Failed(
                        names[endpointId] ?: "устройство",
                        "получатель не принял данные",
                    )
                }

                else -> {
                    // Внутрифайловый прогресс сюда не подмешиваем: он считается
                    // по одному файлу и сбивал бы общую долю пакета.
                }
            }
        }
    }
}
