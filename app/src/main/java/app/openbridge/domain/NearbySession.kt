package app.openbridge.domain

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import app.openbridge.core.AppGraph
import app.openbridge.core.HistoryEntry
import app.openbridge.core.PeerInfo
import app.openbridge.discovery.NearbyEvent
import app.openbridge.discovery.NearbyProgress
import app.openbridge.discovery.NearbyRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class NearbySession(
    private val context: Context,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val messages: MutableStateFlow<String?>,
) {
    private val _incoming = MutableStateFlow<NearbyRequest?>(null)
    val incoming: StateFlow<NearbyRequest?> = _incoming.asStateFlow()

    val progress = MutableStateFlow<NearbyProgress?>(null)
    val enabled = MutableStateFlow(false)

    fun attach() {
        scope.launch(Dispatchers.IO) {
            graph.nearby.incoming.collect { _incoming.value = it }
        }
        scope.launch(Dispatchers.IO) {
            graph.nearby.progress.collect { progress.value = it }
        }
        scope.launch(Dispatchers.IO) {
            graph.nearby.events.collect { event -> event?.let { handleEvent(it) } }
        }
    }

    fun refreshAvailability() {
        enabled.value = runCatching { graph.nearby.isAvailable() }.getOrDefault(false)
    }

    fun accept() {
        val request = _incoming.value ?: return
        _incoming.value = null
        graph.nearby.accept(request.endpointId)
        messages.value = "Соединяемся с ${request.name}…"
    }

    fun trust() {
        val request = _incoming.value ?: return
        _incoming.value = null
        scope.launch(Dispatchers.IO) {
            graph.peers.trust(
                PeerInfo(
                    id = request.deviceId,
                    name = request.name,
                    certFingerprint = request.deviceId,
                )
            )
        }
        graph.nearby.accept(request.endpointId)
        messages.value = "${request.name} добавлено в доверенные"
    }

    fun decline() {
        val request = _incoming.value ?: return
        _incoming.value = null
        graph.nearby.decline(request.endpointId)
        messages.value = "Запрос отклонён"
    }

    fun dismissProgress() {
        progress.value = null
    }

    private suspend fun handleEvent(event: NearbyEvent) {
        when (event) {
            is NearbyEvent.Text -> {
                copyToClipboard(event.text)
                graph.history.add(
                    HistoryEntry(
                        id = UUID.randomUUID().toString(),
                        direction = "in",
                        peerName = event.peerName,
                        summary = "Текст, ${event.text.length} симв.",
                        bytes = event.text.length.toLong(),
                        files = 1,
                        timestamp = System.currentTimeMillis(),
                        status = "ok",
                    )
                )
                progress.value = null
                messages.value = "Текст от ${event.peerName} скопирован в буфер"
            }

            is NearbyEvent.FileSaved -> {
                graph.history.add(
                    HistoryEntry(
                        id = UUID.randomUUID().toString(),
                        direction = "in",
                        peerName = event.peerName,
                        summary = "Файл по Nearby",
                        bytes = event.bytes,
                        files = 1,
                        timestamp = System.currentTimeMillis(),
                        status = "ok",
                    )
                )
                progress.value = null
                messages.value = if (event.path.isBlank()) {
                    "Файл от ${event.peerName} получен, но не сохранился"
                } else {
                    "Файл от ${event.peerName} сохранён в Download/OpenBridge"
                }
            }

            is NearbyEvent.Failed -> {
                progress.value = null
                messages.value = "Nearby: ${event.reason}"
            }
        }
        graph.nearby.consumeEvent()
    }

    private suspend fun copyToClipboard(text: String) {
        withContext(Dispatchers.Main) {
            runCatching {
                val manager = context.getSystemService(ClipboardManager::class.java)
                manager?.setPrimaryClip(ClipData.newPlainText("OpenBridge", text))
            }
        }
    }
}
