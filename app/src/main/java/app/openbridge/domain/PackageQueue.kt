package app.openbridge.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.openbridge.core.AppGraph
import app.openbridge.core.Diag
import app.openbridge.core.PayloadBuilder
import app.openbridge.core.QueueItem
import app.openbridge.core.QueueKind
import app.openbridge.discovery.NearbyItem
import app.openbridge.net.PortalFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Пакет на отправку: очередь файлов и текста, веб-портал и отметка «в метке
 * есть что забрать». Всё, что меняет пакет, проходит здесь, поэтому портал и
 * NFC-метка не могут разойтись с очередью.
 */
class PackageQueue(
    private val context: Context,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val messages: MutableStateFlow<String?>,
) {

    val items = MutableStateFlow<List<QueueItem>>(emptyList())
    val portalUrl = MutableStateFlow<String?>(null)

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val added = uris.map { uri ->
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
            items.value = items.value + added
            publish()
        }
    }

    fun addFolder(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val document = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            items.value = items.value + QueueItem(
                kind = QueueKind.FOLDER,
                uri = uri,
                name = document?.name ?: "Папка",
            )
            publish()
        }
    }

    fun addText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        items.value = items.value + QueueItem(
            kind = QueueKind.TEXT,
            text = clean,
            name = if (clean.length > 32) clean.take(32) + "…" else clean,
        )
        scope.launch(Dispatchers.IO) { publish() }
    }

    fun removeItem(id: String) {
        items.value = items.value.filterNot { it.id == id }
        scope.launch(Dispatchers.IO) { publish() }
    }

    fun clear() {
        items.value = emptyList()
        scope.launch(Dispatchers.IO) { publish() }
    }

    /** Включает или выключает веб-портал вместе с публикацией пакета. */
    fun setPortalEnabled(enabled: Boolean) {
        settings.setPortalEnabled(enabled)
        scope.launch(Dispatchers.IO) {
            if (enabled) {
                graph.portal.rotateToken()
                publish()
                portalUrl.value = graph.portal.url()
            } else {
                portalUrl.value = null
                runCatching { graph.portal.publish(emptyList()) }
                messages.value = "Веб-портал выключен"
            }
        }
    }

    /** Включает портал (если выключен) и отдаёт свежий URL для QR-кода. */
    fun ensurePortalUrl(onReady: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            // Публикуем пакет заново: QR должен вести на живой адрес с текущим
            // IP и свежим токеном, а не на вчерашний.
            if (!settings.portalEnabled.value) {
                settings.setPortalEnabled(true)
                graph.portal.rotateToken()
            }
            publish()
            val url = graph.portal.url()
            portalUrl.value = url
            withContext(Dispatchers.Main) { onReady(url) }
        }
    }

    /** Пакет для Nearby: текст и файлы. Папки умеет только LAN-канал. */
    fun nearbyItems(): List<NearbyItem> = items.value.mapNotNull { item ->        when (item.kind) {
            QueueKind.TEXT -> item.text?.takeIf { it.isNotBlank() }
                ?.let { NearbyItem.Text(it) }

            QueueKind.FILE -> item.uri?.let { NearbyItem.FileItem(it, item.name) }

            QueueKind.FOLDER -> null
        }
    }

    /** Очередь изменилась — обновляем метку NFC и веб-портал. */
    private suspend fun publish() {
        runCatching {
            if (!settings.portalEnabled.value) {
                portalUrl.value = null
                graph.portal.publish(emptyList())
                return@runCatching
            }

            val files = items.value.mapNotNull { item ->
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
            graph.portal.publish(files)
            portalUrl.value = graph.portal.url()
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
}
