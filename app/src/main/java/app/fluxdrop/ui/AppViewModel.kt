package app.fluxdrop.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fluxdrop.FluxApp
import app.fluxdrop.core.CrashReporter
import app.fluxdrop.core.Diag
import app.fluxdrop.core.HistoryEntry
import app.fluxdrop.core.PayloadBuilder
import app.fluxdrop.core.PendingCrash
import app.fluxdrop.core.QueueItem
import app.fluxdrop.core.QueueKind
import app.fluxdrop.core.TrustedPeer
import app.fluxdrop.data.DiscoveredPeer
import app.fluxdrop.data.PeerRepository
import app.fluxdrop.discovery.NearbyEvent
import app.fluxdrop.discovery.NearbyItem
import app.fluxdrop.discovery.NearbyProgress
import app.fluxdrop.discovery.NearbyRequest
import app.fluxdrop.net.NfcPortal
import app.fluxdrop.net.PortalFile
import app.fluxdrop.service.RadarService
import app.fluxdrop.util.NfcHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

@Immutable
data class SendState(
    val peerName: String,
    val fraction: Float,
    val text: String,
)

enum class NfcPhase { READY, CONNECTING, RECEIVING, DONE, TRANSFER, SENT, ERROR }

/** Что рисует NFC-шторка. Никаких режимов — только ход передачи. */
@Immutable
data class NfcState(
    val phase: NfcPhase = NfcPhase.READY,
    val fraction: Float = 0f,
    val files: Int = 0,
    val texts: Int = 0,
    val hasItems: Boolean = false,
    /** Имя отправителя, с которым соединяемся (для строки «Соединяемся с …»). */
    val peerName: String = "",
    /** Шторка про нашу отправку, а не про приём: от этого зависит текст ошибки. */
    val sending: Boolean = false,
)

/**
 * Вся тяжёлая работа (KeyStore, сертификаты, сокеты, служба) выполняется
 * в [startEngine] на Dispatchers.IO. Конструктор и init{} только создают
 * пустые StateFlow — это доли миллисекунды, UI рисуется сразу.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val fluxApp = application as FluxApp
    private val g get() = fluxApp.graph
    private val context: Context get() = getApplication()

    private val engineStarted = AtomicBoolean(false)

    // ── готовность движка ────────────────────────────────────────────────

    private val _isEngineReady = MutableStateFlow(false)
    val isEngineReady: StateFlow<Boolean> = _isEngineReady.asStateFlow()

    private val _engineError = MutableStateFlow<String?>(null)
    val engineError: StateFlow<String?> = _engineError.asStateFlow()

    /** Входящий запрос Nearby — показываем шторку. */
    private val _nearbyIncoming = MutableStateFlow<NearbyRequest?>(null)
    val nearbyIncoming: StateFlow<NearbyRequest?> = _nearbyIncoming.asStateFlow()

    /** Nearby работает только с Google Play Services (на Huawei их нет). */
    val nearbyEnabled = MutableStateFlow(false)

    val nearbyProgress = MutableStateFlow<NearbyProgress?>(null)

    // ── состояние экранов (без обращения к диску) ────────────────────────

    val deviceName = MutableStateFlow("")
    val serviceEnabled = MutableStateFlow(true)
    val autoAccept = MutableStateFlow(true)
    val portalEnabled = MutableStateFlow(false)
    val developerMode = MutableStateFlow(false)
    val autoSendReports = MutableStateFlow(false)
    val appLanguage = MutableStateFlow("system")
    val themeMode = MutableStateFlow(THEME_DARK)
    val strings = MutableStateFlow<AppStrings>(resolveStrings("system"))

    val peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val trusted = MutableStateFlow<List<TrustedPeer>>(emptyList())
    val trustedIds = MutableStateFlow<Set<String>>(emptySet())
    val history = MutableStateFlow<List<HistoryEntry>>(emptyList())

    val serverState = MutableStateFlow("запускается")
    val lastSelfCheck = MutableStateFlow("—")

    val queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val sending = MutableStateFlow<SendState?>(null)
    val message = MutableStateFlow<String?>(null)
    val portalUrl = MutableStateFlow<String?>(null)

    private val _crash = MutableStateFlow<PendingCrash?>(null)
    val crash: StateFlow<PendingCrash?> = _crash.asStateFlow()

    init {
        // Никаких сертификатов, KeyStore, сокетов и prefs. Только запись в журнал.
        Diag.log("viewmodel", "init")
    }

    /**
     * Вызывается один раз после первой отрисовки (LaunchedEffect в AppRoot).
     * Повторные вызовы игнорируются.
     */
    fun startEngine() {
        if (!engineStarted.compareAndSet(false, true)) return

        viewModelScope.launch(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            Diag.log("engine", "start")

            // Настройки и репозитории привязываем в первую очередь: UI сразу заполняется данными
            bindSettings()
            bindRepositories()

            // Параллельно запускаем тяжелые операции
            val warmUpJob = launch {
                runCatching { g.warmUp() }
                    .onFailure { fail("Ключи и данные", it) }
            }
            warmUpJob.join()

            // Метка должна знать, кто мы: id для поиска по Nearby, имя для показа.
            runCatching {
                NfcHelper.prime(context)
                NfcHelper.updateSender(
                    id = g.identity.identity.id,
                    name = deviceName.value,
                )
            }.onFailure { Diag.log("nfc", "sender init failed: $it") }

            // Получатель по NFC просит пакет — отдаём очередь, которая живёт здесь.
            g.nearby.pullProvider = { nearbyItems() }

            launch {
                runCatching { g.startRuntime() }
                    .onFailure { fail("Сервер", it) }
            }

            launch {
                runCatching { fluxApp.startRadarServiceIfEnabled() }
                    .onFailure { fail("Служба", it) }
            }

            launch {
                loadPendingCrash()
                nearbyEnabled.value = runCatching { g.nearby.isAvailable() }.getOrDefault(false)
            }

            _isEngineReady.value = true
            Diag.log(
                "engine",
                "ready in ${SystemClock.elapsedRealtime() - startedAt} ms",
            )
        }
    }

    private fun fail(what: String, error: Throwable) {
        Diag.log("engine", "$what failed: $error")
        _engineError.value = "$what: ${error.javaClass.simpleName}: ${error.message}"
    }

    /** Копируем настройки из prefs (только из фонового потока). */
    private fun bindSettings() {
        runCatching {
            val s = g.settings
            deviceName.value = s.deviceName.value
            serviceEnabled.value = s.serviceEnabled.value
            autoAccept.value = s.autoAcceptTrusted.value
            portalEnabled.value = s.portalEnabled.value
            developerMode.value = s.developerMode.value
            autoSendReports.value = s.autoSendReports.value
            appLanguage.value = s.appLanguage.value
            themeMode.value = s.themeMode.value
            strings.value = resolveStrings(s.appLanguage.value)
        }.onFailure { fail("Настройки", it) }
    }

    /**
     * Подписки на репозитории. Запускаются на IO и только после warmUp,
     * поэтому чтение JSON с диска не попадает в главный поток.
     */
    private fun bindRepositories() {
        runCatching {
            val peersRepo: PeerRepository = g.peers
            val historyRepo = g.history

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
            viewModelScope.launch(Dispatchers.IO) {
                g.serverState.collect { serverState.value = it }
            }
            viewModelScope.launch(Dispatchers.IO) {
                g.lastSelfCheck.collect { lastSelfCheck.value = it }
            }
            viewModelScope.launch(Dispatchers.IO) {
                g.nearby.incoming.collect { _nearbyIncoming.value = it }
            }
            viewModelScope.launch(Dispatchers.IO) {
                g.nearby.progress.collect { progress ->
                    nearbyProgress.value = progress
                    if (progress != null) syncNfcProgress(progress.fraction)
                }
            }
            viewModelScope.launch(Dispatchers.IO) {
                g.nearby.events.collect { event -> event?.let { handleNearbyEvent(it) } }
            }
            bindNfc()
        }.onFailure { fail("Данные", it) }
    }

    // ── сбои ─────────────────────────────────────────────────────────────

    private fun loadPendingCrash() {
        val pending = runCatching { CrashReporter.pending(context) }.getOrNull() ?: return
        if (g.settings.autoSendReports.value) {
            val sent = runCatching { CrashReporter.send(context, pending) }.getOrNull()
            CrashReporter.clear(context)
            message.value = if (sent != null) {
                "Отчёт о сбое отправлен разработчику"
            } else {
                "Не удалось отправить отчёт о сбое"
            }
        } else {
            _crash.value = pending
        }
    }

    fun dismissCrash() {
        _crash.value = null
        viewModelScope.launch(Dispatchers.IO) { CrashReporter.clear(context) }
    }

    fun sendCrash() {
        val report = _crash.value ?: return
        _crash.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val path = runCatching { CrashReporter.send(context, report) }.getOrNull()
            CrashReporter.clear(context)
            message.value = if (path != null) "Логи сохранены: $path" else "Не удалось отправить логи"
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    // ── Nearby (без роутера) ─────────────────────────────────────────────

    /** Пользователь нажал «Принять» в шторке входящего запроса Nearby. */
    fun acceptNearby() {
        val request = _nearbyIncoming.value ?: return
        _nearbyIncoming.value = null
        g.nearby.accept(request.endpointId)
        message.value = "Соединяемся с ${request.name}…"
    }

    /** Пользователь нажал «Доверять» в шторке: добавляем в доверенные и принимаем. */
    fun trustNearby() {
        val request = _nearbyIncoming.value ?: return
        _nearbyIncoming.value = null
        viewModelScope.launch(Dispatchers.IO) {
            g.peers.trust(
                app.fluxdrop.core.PeerInfo(
                    id = request.deviceId,
                    name = request.name,
                    certFingerprint = request.deviceId,
                )
            )
        }
        g.nearby.accept(request.endpointId)
        message.value = "${request.name} добавлено в доверенные"
    }

    fun declineNearby() {
        val request = _nearbyIncoming.value ?: return
        _nearbyIncoming.value = null
        g.nearby.decline(request.endpointId)
        message.value = "Запрос отклонён"
    }

    // ── NFC ──────────────────────────────────────────────────────────────

    /**
     * Состояние нижней NFC-шторки. Режимов больше нет: отправка (HostApduService)
     * и приём (NDEF_DISCOVERED) включены всегда, поэтому шторка только показывает,
     * что происходит.
     */
    private val _nfcSheetOpen = MutableStateFlow(false)
    val nfcSheetOpen: StateFlow<Boolean> = _nfcSheetOpen.asStateFlow()

    private val _nfcState = MutableStateFlow(NfcState())
    val nfcState: StateFlow<NfcState> = _nfcState.asStateFlow()

    fun openNfcSheet() {
        if (!NfcHelper.isNfcEnabled(context)) {
            message.value = strings.value.nfcNotAvailable
            return
        }
        // Пустой пакет открывать нечего: окно появляется только когда есть что отправить.
        if (queue.value.isEmpty()) {
            message.value = strings.value.nfcNeedItems
            return
        }
        syncNfcPackage()
        if (_nfcState.value.phase != NfcPhase.RECEIVING &&
            _nfcState.value.phase != NfcPhase.CONNECTING
        ) {
            _nfcState.value = NfcState(hasItems = true)
        }
        _nfcSheetOpen.value = true
    }

    fun closeNfcSheet() {
        _nfcSheetOpen.value = false
    }

    /**
     * Касание вторым телефоном. В метке — адрес отправителя `fluxdrop://…`:
     * ищем его по Nearby и забираем пакет. Старые метки с адресом локального
     * портала тоже понимаем, чтобы обновление не рвало передачу между версиями.
     */
    fun handleNfcReceived(raw: String) {
        Diag.log("nfc", "incoming: $raw")
        val sender = NfcHelper.parseSender(raw)
        if (sender != null) {
            pullFromSender(sender)
            return
        }
        if (NfcPortal.isPortalUrl(raw)) {
            receiveFromPortal(raw)
            return
        }
        message.value = strings.value.nfcNothingToReceive
    }

    /** Получатель нашёл отправителя в метке и забирает его пакет по Nearby. */
    private fun pullFromSender(sender: NfcHelper.Sender) {
        if (_nfcState.value.phase == NfcPhase.CONNECTING ||
            _nfcState.value.phase == NfcPhase.RECEIVING
        ) return
        if (!runCatching { g.nearby.isAvailable() }.getOrDefault(false)) {
            _nfcState.value = NfcState(NfcPhase.ERROR)
            _nfcSheetOpen.value = true
            message.value = strings.value.nfcNearbyUnavailable
            return
        }

        _nfcState.value = NfcState(
            phase = NfcPhase.CONNECTING,
            peerName = sender.name.ifBlank { "отправитель" },
        )
        _nfcSheetOpen.value = true
        NfcHelper.vibrate(context)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val summary = g.nearby.pull(sender.deviceId, sender.name) {
                    _nfcState.value = _nfcState.value.copy(phase = NfcPhase.RECEIVING)
                }.getOrThrow()

                _nfcState.value = NfcState(
                    phase = NfcPhase.DONE,
                    fraction = 1f,
                    files = summary.files,
                    texts = summary.texts,
                )
                message.value = when {
                    summary.files > 0 -> strings.value.nfcReceivedCount(summary.files)
                    summary.texts > 0 -> strings.value.nfcTextCopied
                    else -> strings.value.nfcEmptyPackage
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Diag.log("nfc", "pull failed: $t")
                _nfcState.value = NfcState(NfcPhase.ERROR)
                message.value = when (t.message) {
                    "empty" -> strings.value.nfcEmptyPackage
                    "no-peer" -> strings.value.nfcNoNearbyPeer
                    "blocked" -> strings.value.nfcNearbyUnavailable
                    else -> strings.value.nfcReceiveFailed
                }
            }
        }
    }

    /** Старая метка: страница портала отправителя, качаем по HTTP. */
    private fun receiveFromPortal(raw: String) {
        if (_nfcState.value.phase == NfcPhase.RECEIVING) return

        _nfcState.value = NfcState(NfcPhase.RECEIVING)
        _nfcSheetOpen.value = true
        NfcHelper.vibrate(context)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val received = NfcPortal.receive(context, raw) { fraction ->
                    _nfcState.value = _nfcState.value.copy(
                        phase = NfcPhase.RECEIVING,
                        fraction = fraction,
                    )
                }
                if (received.texts.isNotEmpty()) {
                    copyToClipboard(received.texts.joinToString("\n\n"))
                }
                _nfcState.value = NfcState(
                    phase = NfcPhase.DONE,
                    fraction = 1f,
                    files = received.files,
                    texts = received.texts.size,
                )
                message.value = when {
                    received.files > 0 -> strings.value.nfcReceivedCount(received.files)
                    received.texts.isNotEmpty() -> strings.value.nfcTextCopied
                    else -> strings.value.nfcEmptyPackage
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Diag.log("nfc", "receive failed: $t")
                _nfcState.value = NfcState(NfcPhase.ERROR)
                message.value = if (t.message == "empty") {
                    strings.value.nfcEmptyPackage
                } else {
                    strings.value.nfcReceiveFailed
                }
            }
        }
    }

    /**
     * Отправка по NFC и её обратная связь:
     *  - метку прочитали → «передаём», пока получатель не заберёт пакет;
     *  - пакет ушёл → «Отправлено».
     * События приходят из HCE-службы и Nearby, поэтому подписки живут в фоне.
     */
    private fun bindNfc() {
        viewModelScope.launch(Dispatchers.IO) {
            // StateFlow сразу отдаёт текущее значение: без этой отметки после
            // пересоздания Activity мы бы показали «Отправлено» ещё раз.
            var read = NfcHelper.served.value
            NfcHelper.served.collect { count ->
                if (count <= read) {
                    read = count
                    return@collect
                }
                read = count
                withContext(Dispatchers.Main) {
                    if (_nfcState.value.phase != NfcPhase.RECEIVING) {
                        _nfcState.value = NfcState(
                            phase = NfcPhase.TRANSFER,
                            hasItems = true,
                            sending = true,
                        )
                        _nfcSheetOpen.value = true
                    }
                }
                NfcHelper.vibrate(context)

                // Получатель может не прийти: у него нет приложения, и он ушёл
                // на страницу скачивания. Тогда просто показываем «Отправлено».
                delay(NFC_HANDOFF_TIMEOUT_MS)
                withContext(Dispatchers.Main) {
                    if (_nfcState.value.phase == NfcPhase.TRANSFER &&
                        g.nearby.progress.value == null
                    ) {
                        _nfcState.value = NfcState(phase = NfcPhase.SENT, fraction = 1f)
                    }
                }
                delay(1_800)
                withContext(Dispatchers.Main) {
                    if (_nfcState.value.phase == NfcPhase.SENT) closeNfcSheet()
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            var sent = g.nearby.delivered.value
            g.nearby.delivered.collect { count ->
                if (count <= sent) {
                    sent = count
                    return@collect
                }
                sent = count
                withContext(Dispatchers.Main) {
                    _nfcState.value = NfcState(phase = NfcPhase.SENT, fraction = 1f)
                    _nfcSheetOpen.value = true
                    NfcHelper.vibrate(context)
                }
                delay(2_400)
                withContext(Dispatchers.Main) {
                    if (_nfcState.value.phase == NfcPhase.SENT) closeNfcSheet()
                }
            }
        }

        // Пакет не дошёл, хотя метку прочитали: не оставляем шторку на «Передаём».
        viewModelScope.launch(Dispatchers.IO) {
            var failures = g.nearby.failed.value
            g.nearby.failed.collect { count ->
                if (count <= failures) {
                    failures = count
                    return@collect
                }
                failures = count
                withContext(Dispatchers.Main) {
                    _nfcState.value = NfcState(phase = NfcPhase.ERROR, sending = true)
                    _nfcSheetOpen.value = true
                }
                NfcHelper.vibrate(context)
                delay(5_000)
                withContext(Dispatchers.Main) {
                    if (_nfcState.value.phase == NfcPhase.ERROR) closeNfcSheet()
                }
            }
        }
    }

    /** Прогресс Nearby во время NFC показываем в самой шторке, без второго диалога. */
    private fun syncNfcProgress(fraction: Float) {
        val current = _nfcState.value
        when (current.phase) {
            NfcPhase.CONNECTING, NfcPhase.RECEIVING ->
                _nfcState.value = current.copy(phase = NfcPhase.RECEIVING, fraction = fraction)

            NfcPhase.TRANSFER ->
                _nfcState.value = current.copy(fraction = fraction)

            else -> Unit
        }
    }

    /** Метка зовёт за пакетом только тогда, когда он не пустой. */
    private fun syncNfcPackage() {
        NfcHelper.updatePackage(queue.value.isNotEmpty())
    }

    private suspend fun copyToClipboard(text: String) {
        withContext(Dispatchers.Main) {
            runCatching {
                val manager = context.getSystemService(ClipboardManager::class.java)
                manager?.setPrimaryClip(android.content.ClipData.newPlainText("FluxDrop", text))
            }
        }
    }

    /** Включает веб-портал (если выключен) и возвращает URL для показа QR-кода. */
    fun ensurePortalUrl(onReady: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            // Включаем портал и публикуем пакет заново: QR должен вести на живой
            // адрес с текущим IP и свежим токеном, а не на вчерашний.
            if (!portalEnabled.value) {
                portalEnabled.value = true
                g.settings.setPortalEnabled(true)
                g.portal.rotateToken()
            }
            publishPortal()
            val url = g.portal.url()
            portalUrl.value = url
            withContext(Dispatchers.Main) { onReady(url) }
        }
    }

    fun dismissNearbyProgress() {
        nearbyProgress.value = null
    }

    private suspend fun handleNearbyEvent(event: NearbyEvent) {
        when (event) {
            is NearbyEvent.Text -> {
                withContext(Dispatchers.Main) {
                    runCatching {
                        val manager = context.getSystemService(ClipboardManager::class.java)
                        manager?.setPrimaryClip(
                            android.content.ClipData.newPlainText("FluxDrop", event.text)
                        )
                    }
                }
                g.history.add(
                    HistoryEntry(
                        id = java.util.UUID.randomUUID().toString(),
                        direction = "in",
                        peerName = event.peerName,
                        summary = "Текст, ${event.text.length} симв.",
                        bytes = event.text.length.toLong(),
                        files = 1,
                        timestamp = System.currentTimeMillis(),
                        status = "ok",
                    )
                )
                nearbyProgress.value = null
                message.value = "Текст от ${event.peerName} скопирован в буфер"
            }

            is NearbyEvent.FileSaved -> {
                g.history.add(
                    HistoryEntry(
                        id = java.util.UUID.randomUUID().toString(),
                        direction = "in",
                        peerName = event.peerName,
                        summary = "Файл по Nearby",
                        bytes = event.bytes,
                        files = 1,
                        timestamp = System.currentTimeMillis(),
                        status = "ok",
                    )
                )
                nearbyProgress.value = null
                message.value = if (event.path.isBlank()) {
                    "Файл от ${event.peerName} получен, но не сохранился"
                } else {
                    "Файл от ${event.peerName} сохранён в Download/FluxDrop"
                }
            }

            is NearbyEvent.Failed -> {
                nearbyProgress.value = null
                message.value = "Nearby: ${event.reason}"
            }
        }
        g.nearby.consumeEvent()
    }

    /** Пакет для Nearby: текст и файлы. Папки умеет только LAN-канал. */
    private fun nearbyItems(): List<NearbyItem> = queue.value.mapNotNull { item ->
        when (item.kind) {
            QueueKind.TEXT -> item.text?.takeIf { it.isNotBlank() }
                ?.let { NearbyItem.Text(it) }

            QueueKind.FILE -> item.uri?.let { NearbyItem.FileItem(it, item.name) }

            QueueKind.FOLDER -> null
        }
    }

    private fun shouldFallbackToNearby(error: Throwable?): Boolean {
        val text = error?.message.orEmpty()
        return !text.contains("отклонен", ignoreCase = true)
    }

    // ── журнал (только для раздела «Для разработчиков») ──────────────────

    /**
     * Поток логов. Обновляет UI только пока его собирают — то есть пока
     * открыт диалог логов в меню разработчика. Радар и настройки его не читают.
     */
    fun logsFlow(): Flow<String> = flow {
        while (true) {
            emit(Diag.recentLines(200))
            delay(2_000)
        }
    }

    // ── очередь и отправка ───────────────────────────────────────────────

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val items = uris.map { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                QueueItem(
                    kind = QueueKind.FILE,
                    uri = uri,
                    name = PayloadBuilder.displayName(context, uri),
                    size = PayloadBuilder.sizeOf(context, uri),
                    mime = PayloadBuilder.mimeOf(context, uri),
                )
            }
            queue.value = queue.value + items
            publishPortal()
        }
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val document = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            val item = QueueItem(
                kind = QueueKind.FOLDER,
                uri = uri,
                name = document?.name ?: "Папка",
            )
            queue.value = queue.value + item
            publishPortal()
        }
    }

    fun addText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        queue.value = queue.value + QueueItem(
            kind = QueueKind.TEXT,
            text = clean,
            name = if (clean.length > 32) clean.take(32) + "…" else clean,
        )
        viewModelScope.launch(Dispatchers.IO) { publishPortal() }
    }

    fun removeItem(id: String) {
        queue.value = queue.value.filterNot { it.id == id }
        viewModelScope.launch(Dispatchers.IO) { publishPortal() }
    }

    fun clearQueue() {
        queue.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { publishPortal() }
    }

    fun sendTo(peer: DiscoveredPeer) {
        if (!_isEngineReady.value) {
            message.value = "Подготовка соединения… попробуйте через секунду"
            return
        }
        if (queue.value.isEmpty()) {
            message.value = "Сначала добавьте файлы или текст"
            return
        }
        if (sending.value != null) {
            message.value = "Уже идёт передача"
            return
        }

        val hasFolder = queue.value.any { it.kind == QueueKind.FOLDER }
        if (peer.ip.isBlank() && hasFolder) {
            message.value = "Папки передаются только по Wi-Fi. Подключите телефоны к одной сети"
            return
        }

        viewModelScope.launch {
            sending.value = SendState(peer.info.name, 0f, "Подключение…")

            // 1. LAN (TLS) — приоритетный и самый быстрый канал.
            var result: Result<Unit> = if (peer.ip.isBlank()) {
                Result.failure(java.io.IOException("нет общего Wi-Fi"))
            } else {
                val built = withContext(Dispatchers.Default) {
                    PayloadBuilder.build(context, queue.value)
                }
                if (built == null) {
                    sending.value = null
                    message.value = "Нечего отправлять"
                    return@launch
                }
                g.client.send(peer.info, peer.ip, built) { fraction, text ->
                    sending.value = SendState(peer.info.name, fraction, text)
                }
            }

            // 2. Nearby — если LAN не удался, роутера нет или сеть режет пакеты.
            if (result.isFailure && shouldFallbackToNearby(result.exceptionOrNull())) {
                val items = nearbyItems()
                if (items.isNotEmpty() && items.size == queue.value.size &&
                    g.nearby.isKnown(peer.info.id)
                ) {
                    sending.value = SendState(peer.info.name, 0f, "Прямое соединение…")
                    result = g.nearby.send(peer.info.id, items) { fraction, text ->
                        sending.value = SendState(peer.info.name, fraction, text)
                    }
                } else if (hasFolder) {
                    sending.value = null
                    message.value = "Папки передаются только по Wi-Fi"
                    return@launch
                }
            }

            sending.value = null
            nearbyProgress.value = null

            if (result.isSuccess) {
                queue.value = emptyList()
                publishPortal()
                message.value = "Отправлено: ${peer.info.name}"
            } else {
                message.value = result.exceptionOrNull()?.message ?: "Не удалось отправить"
            }
        }
    }

    /** Вызывается плиткой быстрых настроек, когда Activity уже на экране. */
    fun sendClipboardNow() {
        if (!_isEngineReady.value) {
            message.value = "Подготовка соединения…"
            return
        }
        val text = readClipboard()
        if (text.isNullOrBlank()) {
            message.value = "Буфер обмена пуст"
            return
        }

        viewModelScope.launch {
            val peers = peers.value
            val ids = trustedIds.value
            val target = peers.firstOrNull { it.info.id in ids && it.ip.isNotBlank() }
                ?: peers.firstOrNull { it.info.id in ids }

            if (target == null) {
                message.value = "Нет доверенного устройства рядом"
                return@launch
            }

            var result: Result<Unit> = if (target.ip.isBlank()) {
                Result.failure(java.io.IOException("нет общего Wi-Fi"))
            } else {
                g.client.sendText(target.info, target.ip, text)
            }

            // Роутера рядом нет — отправляем текст через Nearby.
            if (result.isFailure && shouldFallbackToNearby(result.exceptionOrNull()) &&
                g.nearby.isKnown(target.info.id)
            ) {
                sending.value = SendState(target.info.name, 0f, "Прямое соединение…")
                result = g.nearby.send(
                    deviceId = target.info.id,
                    items = listOf(NearbyItem.Text(text)),
                ) { fraction, stage ->
                    sending.value = SendState(target.info.name, fraction, stage)
                }
            }
            sending.value = null
            nearbyProgress.value = null

            message.value = if (result.isSuccess) {
                "Отправлено: ${target.info.name}"
            } else {
                result.exceptionOrNull()?.message ?: "Не удалось отправить"
            }
        }
    }

    private fun readClipboard(): String? {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    // ── настройки ────────────────────────────────────────────────────────

    fun setDeviceName(name: String) {
        deviceName.value = name.trim()
        // Имя уходит в метку NFC: получатель ищет нас по нему в эфире.
        NfcHelper.updateSender(name = name.trim())
        viewModelScope.launch(Dispatchers.IO) { g.settings.setDeviceName(name) }
    }

    fun setServiceEnabled(enabled: Boolean) {
        if (!_isEngineReady.value) return
        serviceEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            g.settings.setServiceEnabled(enabled)
            if (enabled) RadarService.start(context) else RadarService.stop(context)
        }
    }

    fun setAutoAccept(enabled: Boolean) {
        autoAccept.value = enabled
        viewModelScope.launch(Dispatchers.IO) { g.settings.setAutoAcceptTrusted(enabled) }
    }

    fun setPortalEnabled(enabled: Boolean) {
        if (!_isEngineReady.value) return
        portalEnabled.value = enabled
        viewModelScope.launch(Dispatchers.IO) {
            g.settings.setPortalEnabled(enabled)
            if (enabled) {
                g.portal.rotateToken()
                publishPortal()
                portalUrl.value = g.portal.url()
            } else {
                portalUrl.value = null
                g.portal.publish(emptyList())
                message.value = "Веб-портал выключен"
            }
        }
    }

    fun revoke(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            g.peers.revoke(id)
            message.value = "Устройство больше не доверенное"
        }
    }

    fun setAppLanguage(lang: String) {
        val clean = if (lang in listOf("system", "en", "ru")) lang else "system"
        appLanguage.value = clean
        strings.value = resolveStrings(clean)
        viewModelScope.launch(Dispatchers.IO) { g.settings.setAppLanguage(clean) }
    }

    fun setThemeMode(mode: String) {
        val clean = if (mode in listOf(THEME_DARK, THEME_LIGHT, THEME_SYSTEM)) mode else THEME_DARK
        themeMode.value = clean
        viewModelScope.launch(Dispatchers.IO) { g.settings.setThemeMode(clean) }
    }

    fun setDeveloperMode(enabled: Boolean) {
        developerMode.value = enabled
        viewModelScope.launch(Dispatchers.IO) { g.settings.setDeveloperMode(enabled) }
    }

    fun setAutoSendReports(enabled: Boolean) {
        autoSendReports.value = enabled
        viewModelScope.launch(Dispatchers.IO) { g.settings.setAutoSendReports(enabled) }
    }

    fun checkServer() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { g.probeServer() }
                .getOrElse { "Ошибка: ${it.javaClass.simpleName}: ${it.message}" }
            g.lastSelfCheck.value = result
            Diag.log("probe", "manual check: $result")
            message.value = result
        }
    }

    fun saveLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val path = runCatching { Diag.dump("manual") }.getOrNull()
            message.value = path?.let { "Отчёт сохранён: $it" } ?: "Не удалось сохранить отчёт"
        }
    }

    fun restartServer() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = g.restartServer()
            message.value = "Сервер: $state"
        }
    }

    /** Собирается из уже готовых StateFlow — без обращения к сервисам. */
    fun serverStats(): String = buildString {
        appendLine("Движок: ${if (_isEngineReady.value) "готов" else "запускается"}")
        appendLine("Сервер: ${serverState.value}")
        appendLine("Проверка: ${lastSelfCheck.value}")
        appendLine("TLS-порт: 53530, веб: 53531")
        appendLine("Устройств рядом: ${peers.value.size}")
        appendLine("Доверенных: ${trusted.value.count { !it.revoked }}")
        appendLine("Передач в истории: ${history.value.size}")
        appendLine("LAN: UDP 53531")
        append("Nearby: ${if (nearbyEnabled.value) "доступен" else "нет (нет Play Services)"}")
    }

    fun restartDiscovery() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { g.nearby.stop() }
            runCatching { g.nearby.start() }
            runCatching { g.discovery.start() }
            nearbyEnabled.value = runCatching { g.nearby.isAvailable() }.getOrDefault(false)
        }
    }

    /**
     * Пакет на веб-портал (QR-код) и состояние NFC-метки.
     *
     * По NFC портал больше не открывается: метка ведёт напрямую в приложение
     * получателя. Портал остался отдельной функцией и сам не включается —
     * незачем держать пакет открытым в локальной сети без спроса.
     */
    private fun publishPortal() {
        syncNfcPackage()
        runCatching {
            if (!portalEnabled.value) {
                portalUrl.value = null
                g.portal.publish(emptyList())
                return@runCatching
            }

            val files = queue.value.mapNotNull { item ->
                if (item.kind == QueueKind.TEXT) {
                    val text = item.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    PortalFile(
                        id = item.id,
                        name = textFileName(item),
                        size = text.toByteArray(Charsets.UTF_8).size.toLong(),
                        text = text,
                    )
                } else {
                    val uri = item.uri ?: return@mapNotNull null
                    PortalFile(
                        id = item.id,
                        name = item.name,
                        size = item.size,
                        uri = uri.toString(),
                    )
                }
            }
            g.portal.publish(files)
            portalUrl.value = g.portal.url()
            Diag.log("portal", "published ${files.size} item(s)")
        }.onFailure { Diag.log("portal", "publish failed: $it") }
    }

    /** Имя для текста из пакета: получатель сохранит его как обычный .txt. */
    private fun textFileName(item: QueueItem): String {
        val base = item.name
            .removeSuffix("…")
            .replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .take(40)
            .ifBlank { "text" }
        return "$base.txt"
    }

    private companion object {
        /** Сколько ждём получателя после чтения метки, прежде чем закрыть шторку. */
        const val NFC_HANDOFF_TIMEOUT_MS = 25_000L
    }
}