package app.fluxdrop.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val TLS_PORT = 53530
const val WEB_PORT = 53531
const val TLS_LOCAL_PORT = 53533
const val CHUNK_SIZE = 16 * 1024 * 1024
const val APP_VERSION = "0.3.1"

@Serializable
@Immutable
data class PeerInfo(
    val id: String,
    val name: String,
    val platform: String = "android",
    val port: Int = TLS_PORT,
    val certFingerprint: String = id,
    val certB64: String = "",
    val publicKeyB64: String = "",
)

/** Минимальный пакет для UDP/mDNS/BLE — без сертификата, чтобы не фрагментировать датаграмму. */
@Serializable
@Immutable
data class Beacon(
    val id: String,
    val name: String,
    val platform: String = "android",
    val port: Int = TLS_PORT,
    val fp: String,
    val v: Int = 1,
)

@Serializable
@Immutable
data class FileMeta(
    val index: Int,
    val path: String,
    val size: Long,
    val mime: String = "application/octet-stream",
    val sha256: String = "",
    val chunkSize: Int = CHUNK_SIZE,
    val chunkHashes: List<String> = emptyList(),
) {
    val chunkCount: Int get() = chunkHashes.size
    val name: String get() = path.substringAfterLast('/')
}

@Serializable
sealed interface Payload {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : Payload

    @Serializable
    @SerialName("files")
    data class Files(val files: List<FileMeta>) : Payload

    @Serializable
    @SerialName("mixed")
    data class Mixed(val text: String, val files: List<FileMeta>) : Payload
}

val Payload.filesOrEmpty: List<FileMeta>
    get() = when (this) {
        is Payload.Files -> files
        is Payload.Mixed -> files
        is Payload.Text -> emptyList()
    }

val Payload.textOrNull: String?
    get() = when (this) {
        is Payload.Text -> text
        is Payload.Mixed -> text
        else -> null
    }

val Payload.totalBytes: Long
    get() = filesOrEmpty.sumOf { it.size }

val Payload.kind: String
    get() = when (this) {
        is Payload.Text -> "text"
        is Payload.Files -> "files"
        is Payload.Mixed -> "mixed"
    }

/** Детерминированная строка, которую подписывает отправитель. */
fun Payload.signatureMaterial(): String = when (this) {
    is Payload.Text -> "text:${text.length}:${text.hashCode()}"
    is Payload.Files -> "files:" + files.joinToString(";") { "${it.index}:${it.path}:${it.size}:${it.mime}" }
    is Payload.Mixed -> "mixed:${text.length}:${text.hashCode()}|" +
        files.joinToString(";") { "${it.index}:${it.path}:${it.size}:${it.mime}" }
}

fun Payload.summary(): String = when (this) {
    is Payload.Text -> "Текст • ${text.length} симв."
    is Payload.Files -> "${files.size} файл(ов) • ${formatBytes(totalBytes)}"
    is Payload.Mixed -> "Файлы (${files.size}) + текст"
}

@Serializable
data class TransferOffer(
    val transferId: String,
    val sender: PeerInfo,
    val payload: Payload,
    val createdAt: Long,
    val expiresAt: Long,
    val signatureB64: String,
) {
    fun signableBytes(): ByteArray =
        "$transferId|$createdAt|$expiresAt|${sender.id}|${payload.kind}|${payload.signatureMaterial()}"
            .encodeToByteArray()
}

@Serializable
data class OfferResponse(
    val accepted: Boolean,
    val reason: String = "",
    val token: String = "",
    val transferId: String = "",
)

@Serializable
data class TransferResult(val ok: Boolean, val reason: String = "")

@Serializable
data class ResumeState(val received: List<Int>)

@Serializable
@Immutable
data class TrustedPeer(
    val id: String,
    val name: String,
    val platform: String = "android",
    val certB64: String,
    val publicKeyB64: String,
    val fingerprint: String,
    val trustedAt: Long,
    val autoText: Boolean = true,
    val autoFiles: Boolean = true,
    val revoked: Boolean = false,
)

@Serializable
@Immutable
data class HistoryEntry(
    val id: String,
    val direction: String,          // "out" | "in"
    val peerName: String,
    val summary: String,
    val bytes: Long,
    val files: Int,
    val timestamp: Long,
    val status: String,             // "ok" | "declined" | "failed"
)

sealed interface IncomingStage {
    data object Awaiting : IncomingStage
    data object Connecting : IncomingStage
    data class Transferring(val doneBytes: Long, val totalBytes: Long) : IncomingStage
    data object Done : IncomingStage
    data class Failed(val reason: String) : IncomingStage
}

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val digits = if (value >= 100) 0 else if (value >= 10) 1 else 2
    return String.format(java.util.Locale.US, "%.${digits}f %s", value, units[unit])
}
