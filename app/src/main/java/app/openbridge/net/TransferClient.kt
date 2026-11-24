package app.openbridge.net

import android.content.ClipData
import app.openbridge.core.AppGraph
import app.openbridge.core.BuiltPayload
import app.openbridge.core.Diag
import app.openbridge.core.FileMeta
import app.openbridge.core.HistoryEntry
import app.openbridge.core.IdentityStore
import app.openbridge.core.OfferResponse
import app.openbridge.core.Payload
import app.openbridge.core.PeerInfo
import app.openbridge.core.ResumeState
import app.openbridge.core.SourceFile
import app.openbridge.core.TransferOffer
import app.openbridge.core.filesOrEmpty
import app.openbridge.core.summary
import app.openbridge.core.totalBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection

class TransferClient(private val g: AppGraph) {

    private data class HttpResult(val code: Int, val text: String) {
        val ok: Boolean get() = code in 200..299
    }

    suspend fun send(
        peer: PeerInfo,
        ip: String,
        built: BuiltPayload,
        onProgress: (Float, String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val transferId = UUID.randomUUID().toString()
        val fingerprint = peer.certFingerprint.takeIf { it.length >= 64 }

        runCatching {
            val offer = signedOffer(transferId, built.payload)
            onProgress(0f, "Ждём подтверждения на ${peer.name}…")

            val offerResult = retryIo(3) {
                request(
                    ip, peer.port, "POST", "/v1/offer",
                    g.json.encodeToString(TransferOffer.serializer(), offer).encodeToByteArray(),
                    fingerprint,
                )
            }
            Diag.log("client", "offer to ${ip}:${peer.port} -> ${offerResult.code}")
            if (!offerResult.ok) {
                val reason = runCatching {
                    g.json.decodeFromString(OfferResponse.serializer(), offerResult.text).reason
                }.getOrNull()
                error(reason?.takeIf { it.isNotBlank() } ?: "Передача отклонена")
            }
            val token = g.json.decodeFromString(OfferResponse.serializer(), offerResult.text).token

            val totalBytes = built.sources.sumOf { it.meta.size }.coerceAtLeast(1L)
            var sentBytes = 0L

            built.sources.forEach { source ->
                onProgress(
                    (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f),
                    "Подготовка ${source.meta.name}",
                )
                val meta = hashSource(source)

                val metaResult = request(
                    ip, peer.port, "POST",
                    "/v1/file/$transferId/${meta.index}/meta",
                    g.json.encodeToString(FileMeta.serializer(), meta).encodeToByteArray(),
                    fingerprint, token,
                )
                if (!metaResult.ok) error("Получатель не принял описание файла")

                val received = runCatching {
                    val result = request(
                        ip, peer.port, "GET",
                        "/v1/resume/$transferId/${meta.index}",
                        null, fingerprint, token,
                    )
                    if (result.ok) {
                        g.json.decodeFromString(ResumeState.serializer(), result.text).received.toSet()
                    } else {
                        emptySet()
                    }
                }.getOrDefault(emptySet())

                upload(ip, peer.port, transferId, source, meta, received, token, fingerprint) { bytes ->
                    sentBytes += bytes
                    onProgress(
                        (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f),
                        "Отправка ${meta.name}",
                    )
                }

                val finish = request(
                    ip, peer.port, "POST",
                    "/v1/file/$transferId/${meta.index}/finish",
                    null, fingerprint, token,
                )
                if (!finish.ok) error("Не удалось завершить ${meta.name}")
            }

            val complete = request(
                ip, peer.port, "POST",
                "/v1/transfer/$transferId/complete",
                null, fingerprint, token,
            )
            if (!complete.ok) error("Получатель не подтвердил передачу")

            onProgress(1f, "Готово")
            g.history.add(
                HistoryEntry(
                    id = transferId,
                    direction = "out",
                    peerName = peer.name,
                    summary = built.payload.summary(),
                    bytes = built.payload.totalBytes,
                    files = built.payload.filesOrEmpty.size,
                    timestamp = System.currentTimeMillis(),
                    status = "ok",
                )
            )
        }.onFailure { error ->
            Diag.log("send", "failed to ${peer.name} ${ip}:${peer.port}: $error")
            runCatching { Diag.dump("send", error) }
            g.history.add(
                HistoryEntry(
                    id = transferId,
                    direction = "out",
                    peerName = peer.name,
                    summary = built.payload.summary(),
                    bytes = built.payload.totalBytes,
                    files = built.payload.filesOrEmpty.size,
                    timestamp = System.currentTimeMillis(),
                    status = "failed",
                )
            )
            onProgress(0f, friendlyError(error))
        }
    }

    private fun friendlyError(error: Throwable): String {
        val text = error.message.orEmpty()
        val looksLikeDrop = listOf(
            "unexpected end of stream",
            "Connection reset",
            "Broken pipe",
            "refused",
            "failed to connect",
            "timeout",
            "timed out",
        ).any { text.contains(it, ignoreCase = true) }
        return if (looksLikeDrop) {
            "Устройство не отвечает. Проверьте, что на нём установлена версия 0.1.2 " +
                "и включена «Служба обнаружения»."
        } else {
            text.ifBlank { "Ошибка передачи" }
        }
    }

    private fun <T> retryIo(times: Int, block: () -> T): T {
        var lastError: Throwable? = null
        for (attempt in 1..times) {
            val result = runCatching(block)
            result.getOrNull()?.let { return it }
            lastError = result.exceptionOrNull()
            Diag.log("client", "attempt $attempt failed: $lastError")
            if (attempt < times) Thread.sleep(400L * attempt)
        }
        throw lastError ?: IllegalStateException("нет соединения")
    }

    suspend fun sendText(peer: PeerInfo, ip: String, text: String): Result<Unit> =
        send(peer, ip, BuiltPayload(Payload.Text(text), emptyList())) { _, _ -> }

    private fun signedOffer(transferId: String, payload: Payload): TransferOffer {
        val now = System.currentTimeMillis()
        val unsigned = TransferOffer(
            transferId = transferId,
            sender = g.myPeerInfo(),
            payload = payload,
            createdAt = now,
            expiresAt = now + 10 * 60_000L,
            signatureB64 = "",
        )
        return unsigned.copy(signatureB64 = g.identity.sign(unsigned.signableBytes()))
    }

    private fun hashSource(source: SourceFile): FileMeta {
        val meta = source.meta
        if (meta.chunkHashes.isNotEmpty()) return meta

        val input = g.app.contentResolver.openInputStream(source.uri)
            ?: error("Не удалось открыть ${meta.name}")

        val digest = MessageDigest.getInstance("SHA-256")
        val hashes = mutableListOf<String>()
        var size = 0L

        input.use { stream ->
            val buffer = ByteArray(meta.chunkSize)
            while (true) {
                var filled = 0
                while (filled < buffer.size) {
                    val read = stream.read(buffer, filled, buffer.size - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled == 0) break
                val slice = if (filled == buffer.size) buffer else buffer.copyOf(filled)
                hashes += IdentityStore.sha256Hex(slice)
                digest.update(slice)
                size += slice.size
            }
        }

        return meta.copy(
            size = size,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            chunkHashes = hashes,
        )
    }

    private fun upload(
        ip: String,
        port: Int,
        transferId: String,
        source: SourceFile,
        meta: FileMeta,
        received: Set<Int>,
        token: String,
        fingerprint: String?,
        onBytes: (Long) -> Unit,
    ) {
        if (meta.chunkCount == 0) return
        val input = g.app.contentResolver.openInputStream(source.uri)
            ?: error("Файл недоступен: ${meta.name}")

        input.use { stream ->
            var position = 0L
            for (index in 0 until meta.chunkCount) {
                val size = minOf(meta.chunkSize.toLong(), meta.size - position).toInt()
                if (size <= 0) break
                val bytes = ByteArray(size)
                stream.readFully(bytes)
                position += size

                if (index in received) continue

                var attempt = 0
                while (true) {
                    val result = request(
                        ip, port, "PUT",
                        "/v1/file/$transferId/${meta.index}?chunk=$index",
                        bytes, fingerprint, token,
                    )
                    if (result.ok) break
                    attempt++
                    if (attempt >= 4) error("Чанк $index не принят (${result.code})")
                    Thread.sleep(250L * attempt)
                }
                onBytes(size.toLong())
            }
        }
    }

    private fun request(
        ip: String,
        port: Int,
        method: String,
        path: String,
        body: ByteArray?,
        fingerprint: String?,
        token: String? = null,
    ): HttpResult {
        val url = URL("https://$ip:$port$path")
        val (factory, _) = g.identity.clientSsl(fingerprint)
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = factory
            hostnameVerifier = HostnameVerifier { _, _ -> true }
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 180_000
            setRequestProperty("Connection", "keep-alive")
            if (token != null) setRequestProperty("X-Transfer-Token", token)
        }

        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                if (method == "PUT") "application/octet-stream" else "application/json",
            )
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.buffered(256 * 1024).use { it.write(body); it.flush() }
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = runCatching {
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        connection.disconnect()
        return HttpResult(code, text)
    }

    private fun InputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = read(target, offset, target.size - offset)
            if (read <= 0) error("Файл закончился раньше времени")
            offset += read
        }
    }

    @Suppress("unused")
    private fun clip(text: String) = ClipData.newPlainText("OpenBridge", text)

    @Suppress("unused")
    private fun percent(value: Float): String =
        String.format(Locale.US, "%.0f%%", value * 100)
}
