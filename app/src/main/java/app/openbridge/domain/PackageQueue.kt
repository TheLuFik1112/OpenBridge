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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class PackageQueue(
    private val context: Context,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val messages: MutableStateFlow<String?>,
) {
    val items = MutableStateFlow<List<QueueItem>>(emptyList())
    val portalUrl = MutableStateFlow<String?>(null)

    private val publishMutex = Mutex()

    private companion object {
        const val MAX_PORTAL_FILES = 1000
        const val MAX_FOLDER_DEPTH = 8
    }

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

    fun setPortalEnabled(enabled: Boolean) {
        settings.setPortalEnabled(enabled)
        scope.launch(Dispatchers.IO) {
            if (enabled) {
                graph.portal.rotateToken()
                publish()
            } else {
                publish()
                messages.value = "Веб-портал выключен"
            }
        }
    }

    fun ensurePortalUrl(onReady: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
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

    fun nearbyItems(): List<NearbyItem> = items.value.mapNotNull { item ->        when (item.kind) {
            QueueKind.TEXT -> item.text?.takeIf { it.isNotBlank() }
                ?.let { NearbyItem.Text(it) }

            QueueKind.FILE -> item.uri?.let { NearbyItem.FileItem(it, item.name) }

            QueueKind.FOLDER -> null
        }
    }

    private suspend fun publish() = publishMutex.withLock {
        runCatching {
            if (!settings.portalEnabled.value) {
                portalUrl.value = null
                graph.portal.publish(emptyList())
                return@runCatching
            }

            val files = buildList<PortalFile> {
                items.value.forEach { item ->
                    when (item.kind) {
                        QueueKind.TEXT -> textPortalFile(item)?.let { add(it) }
                        QueueKind.FILE -> filePortalFile(item)?.let { add(it) }
                        QueueKind.FOLDER -> addAll(folderPortalFiles(item))
                    }
                }
            }
            graph.portal.publish(files)
            portalUrl.value = graph.portal.url()
            Diag.log("portal", "published ${files.size} item(s)")
        }.onFailure { Diag.log("portal", "publish failed: $it") }
    }

    private fun textPortalFile(item: QueueItem): PortalFile? {
        val text = item.text?.takeIf { it.isNotBlank() } ?: return null
        return PortalFile(
            id = item.id,
            name = textFileName(item),
            size = text.toByteArray(Charsets.UTF_8).size.toLong(),
            text = text,
        )
    }

    private fun filePortalFile(item: QueueItem): PortalFile? {
        val uri = item.uri ?: return null
        val size = item.size.takeIf { it > 0L } ?: PayloadBuilder.sizeOf(context, uri)
        return PortalFile(
            id = item.id,
            name = item.name,
            size = size,
            uri = uri.toString(),
        )
    }

    private fun folderPortalFiles(item: QueueItem): List<PortalFile> {
        val uri = item.uri ?: return emptyList()
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<PortalFile>()
        collectFolder(root, item, "", 0, out)
        return out
    }

    private fun collectFolder(
        dir: DocumentFile,
        rootItem: QueueItem,
        prefix: String,
        depth: Int,
        out: MutableList<PortalFile>,
    ) {
        if (depth > MAX_FOLDER_DEPTH || out.size >= MAX_PORTAL_FILES) return
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            if (out.size >= MAX_PORTAL_FILES) return
            val name = child.name?.takeIf { it.isNotBlank() } ?: continue
            val path = if (prefix.isBlank()) name else "$prefix/$name"
            if (child.isDirectory) {
                collectFolder(child, rootItem, path, depth + 1, out)
            } else if (child.isFile) {
                val id = UUID.nameUUIDFromBytes(
                    (rootItem.id + "/" + path).toByteArray(Charsets.UTF_8),
                ).toString()
                out += PortalFile(
                    id = id,
                    name = "${rootItem.name.ifBlank { "folder" }}/$path",
                    size = child.length(),
                    uri = child.uri.toString(),
                )
            }
        }
    }

    private fun textFileName(item: QueueItem): String {
        val base = item.name
            .removeSuffix("…")
            .replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .take(40)
            .ifBlank { "text" }
        return if (base.endsWith(".txt", ignoreCase = true)) base else "$base.txt"
    }
}
