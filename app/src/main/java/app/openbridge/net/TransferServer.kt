package app.openbridge.net

import android.content.ContentValues
import android.provider.MediaStore
import app.openbridge.core.APP_VERSION
import app.openbridge.core.AppGraph
import app.openbridge.core.FileMeta
import app.openbridge.core.ResumeState
import app.openbridge.core.TLS_LOCAL_PORT
import app.openbridge.core.TLS_PORT
import app.openbridge.core.TransferOffer
import app.openbridge.core.WEB_PORT
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import io.ktor.utils.io.readRemaining

class TransferServer(private val g: AppGraph) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var relay: TlsRelay? = null

    fun start() {
        if (server != null) return

        // Сначала HTTP-сервер на loopback, только потом внешний TLS-порт:
        // ретранслятор не должен принимать соединения раньше, чем сервер готов.
        server = embeddedServer(
            factory = CIO,
            configure = {
                connector {
                    host = "127.0.0.1"
                    port = TLS_LOCAL_PORT
                }
                connector {
                    host = "0.0.0.0"
                    port = WEB_PORT
                }
            },
            module = { module() },
        ).start(wait = false)

        val tls = TlsRelay(
            sslContext = g.identity.serverSslContext(),
            listenPort = TLS_PORT,
            upstreamPort = TLS_LOCAL_PORT,
        )
        tls.start()
        relay = tls
    }

    fun stop() {
        runCatching { server?.stop(500, 1500) }
        server = null
        runCatching { relay?.stop() }
        relay = null
    }

    private fun Application.module() {
        install(ContentNegotiation) { json(g.json) }

        routing {
            get("/") { call.respondText("OpenBridge $APP_VERSION") }

            get("/v1/info") { call.respond(g.myPeerInfo()) }

            post("/v1/offer") {
                val offer = runCatching { call.receive<TransferOffer>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val response = g.incoming.handleOffer(offer)
                call.respond(
                    if (response.accepted) HttpStatusCode.OK else HttpStatusCode.Forbidden,
                    response,
                )
            }

            get("/v1/resume/{tid}/{index}") {
                val tid = call.parameters["tid"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val index = call.parameters["index"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                if (!g.incoming.isValidToken(tid, call.request.header("X-Transfer-Token"))) {
                    return@get call.respond(HttpStatusCode.Forbidden)
                }
                call.respond(ResumeState(g.receiver.received(tid, index)))
            }

            post("/v1/file/{tid}/{index}/meta") {
                val tid = call.parameters["tid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val index = call.parameters["index"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                if (!g.incoming.isValidToken(tid, call.request.header("X-Transfer-Token"))) {
                    return@post call.respond(HttpStatusCode.Forbidden)
                }
                val meta = runCatching { call.receive<FileMeta>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                if (!g.receiver.registerMeta(tid, index, meta)) {
                    return@post call.respond(HttpStatusCode.Conflict)
                }
                call.respond(HttpStatusCode.OK)
            }

            put("/v1/file/{tid}/{index}") {
                val tid = call.parameters["tid"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                val index = call.parameters["index"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest)
                val chunk = call.request.queryParameters["chunk"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest)
                if (!g.incoming.isValidToken(tid, call.request.header("X-Transfer-Token"))) {
                    return@put call.respond(HttpStatusCode.Forbidden)
                }
                val data = call.receiveChannel().readRemaining().readByteArray()
                if (!g.receiver.writeChunk(tid, index, chunk, data)) {
                    return@put call.respond(HttpStatusCode.Conflict)
                }
                g.incoming.onProgress(tid)
                call.respond(HttpStatusCode.OK)
            }

            post("/v1/file/{tid}/{index}/finish") {
                val tid = call.parameters["tid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val index = call.parameters["index"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                if (!g.incoming.isValidToken(tid, call.request.header("X-Transfer-Token"))) {
                    return@post call.respond(HttpStatusCode.Forbidden)
                }
                val result = g.receiver.finishFile(tid, index)
                call.respond(if (result.ok) HttpStatusCode.OK else HttpStatusCode.Conflict, result)
            }

            post("/v1/transfer/{tid}/complete") {
                val tid = call.parameters["tid"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                if (!g.incoming.isValidToken(tid, call.request.header("X-Transfer-Token"))) {
                    return@post call.respond(HttpStatusCode.Forbidden)
                }
                val result = g.receiver.complete(tid)
                g.incoming.onComplete(tid, result)
                call.respond(if (result.ok) HttpStatusCode.OK else HttpStatusCode.Conflict, result)
            }

            // ── Веб-портал ───────────────────────────────────────────────

            get("/web") {
                val token = call.request.queryParameters["t"]
                if (!g.portal.check(token)) {
                    return@get call.respondText("Нет доступа", status = HttpStatusCode.Forbidden)
                }
                call.respondText(g.portal.html(g.portal.token()), ContentType.Text.Html)
            }

            get("/web/list") {
                if (!g.portal.check(call.request.queryParameters["t"])) {
                    return@get call.respondText("[]", ContentType.Application.Json, HttpStatusCode.Forbidden)
                }
                call.respond(g.portal.files.value)
            }

            get("/web/get") {
                if (!g.portal.check(call.request.queryParameters["t"])) {
                    return@get call.respond(HttpStatusCode.Forbidden)
                }
                val file = g.portal.file(call.request.queryParameters["id"] ?: "")
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                // Текст из пакета живёт прямо в памяти — отдаём его как text/plain.
                val text = file.text
                if (text != null) {
                    call.response.headers.append(
                        HttpHeaders.ContentDisposition,
                        "inline; filename=\"${file.name.replace("\"", "")}\"",
                    )
                    return@get call.respondText(text, ContentType.Text.Plain)
                }

                val uri = g.portal.uri(file) ?: return@get call.respond(HttpStatusCode.NotFound)

                val total = withContext(Dispatchers.IO) {
                    g.app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                }
                if (total < 0) return@get call.respond(HttpStatusCode.NotFound)

                val rangeHeader = call.request.header(HttpHeaders.Range)
                var start = 0L
                var end = total - 1
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val spec = rangeHeader.removePrefix("bytes=").substringBefore(',')
                    val parts = spec.split("-")
                    start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                    end = parts.getOrNull(1)?.toLongOrNull() ?: (total - 1)
                }
                if (start < 0 || start >= total || end < start) {
                    return@get call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                }
                end = minOf(end, total - 1)
                val length = end - start + 1
                val mime = withContext(Dispatchers.IO) {
                    g.app.contentResolver.getType(uri) ?: "application/octet-stream"
                }
                val contentType = runCatching { ContentType.parse(mime) }
                    .getOrDefault(ContentType.Application.OctetStream)

                call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
                if (rangeHeader != null) {
                    call.response.headers.append(
                        HttpHeaders.ContentRange,
                        "bytes $start-$end/$total",
                    )
                }

                call.respondOutputStream(
                    contentType = contentType,
                    status = if (rangeHeader != null) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                ) {
                    withContext(Dispatchers.IO) {
                        g.app.contentResolver.openInputStream(uri)?.use { input ->
                            var skipped = 0L
                            while (skipped < start) {
                                val n = input.skip(start - skipped)
                                if (n <= 0) break
                                skipped += n
                            }
                            val buffer = ByteArray(256 * 1024)
                            var remaining = length
                            while (remaining > 0) {
                                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (read <= 0) break
                                write(buffer, 0, read)
                                remaining -= read
                            }
                            flush()
                        }
                    }
                }
            }

            put("/web/up") {
                if (!g.portal.check(call.request.queryParameters["t"])) {
                    return@put call.respond(HttpStatusCode.Forbidden)
                }
                val name = sanitizeName(call.request.queryParameters["name"] ?: "file")
                val rawType = call.request.header(HttpHeaders.ContentType) ?: "application/octet-stream"

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val uri = withContext(Dispatchers.IO) { createDownload(name, rawType) }
                        ?: return@put call.respond(HttpStatusCode.InternalServerError)
                    withContext(Dispatchers.IO) {
                        g.app.contentResolver.openOutputStream(uri)?.use { output ->
                            copyBody(call, output)
                        }
                    }
                    withContext(Dispatchers.IO) { finalizeDownload(uri) }
                } else {
                    val temp = java.io.File(g.app.cacheDir, "web-$name")
                    withContext(Dispatchers.IO) {
                        temp.outputStream().use { copyBody(call, it) }
                        Downloads.save(g.app, temp, "web/$name", rawType)
                        temp.delete()
                    }
                }
                call.respondText("ok")
            }
        }
    }

    private suspend fun copyBody(call: io.ktor.server.application.ApplicationCall, output: java.io.OutputStream) {
        val channel = call.receiveChannel()
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    private fun createDownload(name: String, mime: String): android.net.Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime.substringBefore(';').trim())
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/OpenBridge/web")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return g.app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun finalizeDownload(uri: android.net.Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { g.app.contentResolver.update(uri, values, null, null) }
    }

    private fun sanitizeName(name: String): String =
        name.replace('/', '_').replace('\\', '_').take(120).ifBlank { "file" }
}
