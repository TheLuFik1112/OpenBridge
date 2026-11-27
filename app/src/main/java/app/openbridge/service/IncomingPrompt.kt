package app.openbridge.service

import androidx.compose.runtime.Immutable
import app.openbridge.core.FileMeta
import app.openbridge.core.Payload
import app.openbridge.core.TransferOffer
import app.openbridge.core.filesOrEmpty
import app.openbridge.core.textOrNull
import app.openbridge.discovery.NearbyRequest

/**
 * A transport-agnostic incoming request.
 *
 * Wi-Fi (LAN/UDP) offers and Nearby connection requests are both turned into
 * this model, so the person always sees the same sheet with the same buttons,
 * whichever transport the sender picked.
 */
@Immutable
data class IncomingPrompt(
    val id: String,
    val peerName: String,
    val platform: String,
    val transport: String,
    val headline: String,
    val text: String? = null,
    val files: List<FileMeta> = emptyList(),
    val allowAlways: Boolean = false,
    /** Wi-Fi transfers report progress, so the sheet stays open until they end. */
    val trackProgress: Boolean = false,
) {
    companion object {
        const val TRANSPORT_WIFI = "WI-FI"
        const val TRANSPORT_NEARBY = "РЯДОМ"

        fun fromOffer(offer: TransferOffer): IncomingPrompt = IncomingPrompt(
            id = offer.transferId,
            peerName = offer.sender.name,
            platform = offer.sender.platform,
            transport = TRANSPORT_WIFI,
            headline = when (val payload = offer.payload) {
                is Payload.Text -> "Хочет отправить текст из буфера"
                is Payload.Files -> "Хочет отправить файлы (${payload.files.size})"
                is Payload.Mixed -> "Хочет отправить файлы и текст"
            },
            text = offer.payload.textOrNull,
            files = offer.payload.filesOrEmpty,
            allowAlways = true,
            trackProgress = true,
        )

        fun fromNearby(request: NearbyRequest): IncomingPrompt = IncomingPrompt(
            id = nearbyId(request.endpointId),
            peerName = request.name.ifBlank { "Устройство рядом" },
            platform = "ANDROID",
            transport = TRANSPORT_NEARBY,
            headline = "Хочет отправить пакет рядом",
            allowAlways = !request.trusted,
            trackProgress = false,
        )

        fun nearbyId(endpointId: String): String = "nearby:$endpointId"
    }
}
