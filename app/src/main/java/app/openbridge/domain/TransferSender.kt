package app.openbridge.domain

import android.content.ClipboardManager
import android.content.Context
import app.openbridge.core.AppGraph
import app.openbridge.core.PayloadBuilder
import app.openbridge.core.QueueItem
import app.openbridge.core.QueueKind
import app.openbridge.data.DiscoveredPeer
import app.openbridge.discovery.NearbyItem
import app.openbridge.discovery.NearbyProgress
import app.openbridge.net.TransferRejectedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransferSender(
    private val context: Context,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val queue: PackageQueue,
    private val peers: StateFlow<List<DiscoveredPeer>>,
    private val trustedIds: StateFlow<Set<String>>,
    private val engineReady: () -> Boolean,
    private val sending: MutableStateFlow<SendState?>,
    private val progress: MutableStateFlow<NearbyProgress?>,
    private val messages: MutableStateFlow<String?>,
) {
    fun sendTo(peer: DiscoveredPeer) {
        if (!engineReady()) {
            messages.value = "Подготовка соединения… попробуйте через секунду"
            return
        }
        if (queue.items.value.isEmpty()) {
            messages.value = "Сначала добавьте файлы или текст"
            return
        }
        if (sending.value != null) {
            messages.value = "Уже идёт передача"
            return
        }

        val hasFolder = queue.items.value.any { it.kind == QueueKind.FOLDER }
        if (peer.ip.isBlank() && hasFolder) {
            messages.value = "Папки передаются только по Wi-Fi. Подключите телефоны к одной сети"
            return
        }

        scope.launch {
            sending.value = SendState(peer.info.name, 0f, "Подключение…")
            var transport = "lan"

            var result: Result<Unit> = if (peer.ip.isBlank()) {
                Result.failure(java.io.IOException("no shared Wi-Fi"))
            } else {
                val built = withContext(Dispatchers.Default) {
                    PayloadBuilder.build(context, queue.items.value)
                }
                if (built == null) {
                    sending.value = null
                    messages.value = "Нечего отправлять"
                    return@launch
                }
                graph.client.send(peer.info, peer.ip, built) { fraction, text ->
                    sending.value = SendState(peer.info.name, fraction, text)
                }
            }

            if (result.isFailure && shouldFallbackToNearby(result.exceptionOrNull())) {
                val items = queue.nearbyItems()
                if (items.isNotEmpty() && items.size == queue.items.value.size &&
                    graph.nearby.isKnown(peer.info.id)
                ) {
                    sending.value = SendState(peer.info.name, 0f, "Прямое соединение…")
                    result = graph.nearby.send(peer.info.id, items) { fraction, text ->
                        sending.value = SendState(peer.info.name, fraction, text)
                    }
                    if (result.isSuccess) transport = "nearby"
                } else if (hasFolder) {
                    sending.value = null
                    messages.value = "Папки передаются только по Wi-Fi"
                    return@launch
                }
            }

            sending.value = null
            progress.value = null

            if (result.isSuccess) {
                val sent = queue.items.value
                queue.clear()
                graph.pluginEngine.onTransferSent(sentLabel(sent), peer.info.name, transport)
                messages.value = "Отправлено: ${peer.info.name}"
            } else {
                messages.value = result.exceptionOrNull()?.message ?: "Не удалось отправить"
            }
        }
    }

    fun sendClipboardNow() {
        if (!engineReady()) {
            messages.value = "Подготовка соединения…"
            return
        }
        val text = readClipboard()
        if (text.isNullOrBlank()) {
            messages.value = "Буфер обмена пуст"
            return
        }

        scope.launch {
            val ids = trustedIds.value
            val target = peers.value.firstOrNull { it.info.id in ids && it.ip.isNotBlank() }
                ?: peers.value.firstOrNull { it.info.id in ids }

            if (target == null) {
                messages.value = "Нет доверенного устройства рядом"
                return@launch
            }

            var result: Result<Unit> = if (target.ip.isBlank()) {
                Result.failure(java.io.IOException("no shared Wi-Fi"))
            } else {
                graph.client.sendText(target.info, target.ip, text)
            }
            var transport = "lan"

            if (result.isFailure && shouldFallbackToNearby(result.exceptionOrNull()) &&
                graph.nearby.isKnown(target.info.id)
            ) {
                sending.value = SendState(target.info.name, 0f, "Прямое соединение…")
                result = graph.nearby.send(
                    deviceId = target.info.id,
                    items = listOf(NearbyItem.Text(text)),
                ) { fraction, stage ->
                    sending.value = SendState(target.info.name, fraction, stage)
                }
                if (result.isSuccess) transport = "nearby"
            }
            sending.value = null
            progress.value = null

            if (result.isSuccess) {
                graph.pluginEngine.onTransferSent(text, target.info.name, transport)
            }
            messages.value = if (result.isSuccess) {
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

    private fun shouldFallbackToNearby(error: Throwable?): Boolean {
        if (error is TransferRejectedException) return false

        val text = error?.message.orEmpty()
        return !text.contains("declined", ignoreCase = true) &&
            !text.contains("rejected", ignoreCase = true)
    }

    private fun sentLabel(items: List<QueueItem>): String =
        if (items.size == 1) {
            items.first().text?.takeIf { it.isNotBlank() } ?: items.first().name
        } else {
            items.joinToString(", ") { it.name }
        }
}
