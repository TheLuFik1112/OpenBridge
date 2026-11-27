package app.openbridge.net

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import app.openbridge.core.APP_VERSION
import app.openbridge.core.AppGraph
import app.openbridge.core.Diag
import app.openbridge.core.FileMeta
import app.openbridge.core.ResumeState
import app.openbridge.core.TLS_LOCAL_PORT
import app.openbridge.core.TLS_PORT
import app.openbridge.core.TransferOffer
import app.openbridge.core.WEB_PORT
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
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
import io.ktor.server.response.respondBytes
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
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TransferServer(private val g: AppGraph) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var relay: TlsRelay? = null

    fun start() {
        if (server != null) return

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

            get("/web") {
                val token = call.request.queryParameters["t"]
                if (!g.portal.check(token)) {
                    return@get call.respondText(
                        "Access denied. Scan the QR code again.",
                        status = HttpStatusCode.Forbidden,
                    )
                }
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respondText(
                    g.portal.html(token.orEmpty()),
                    ContentType.Text.Html.withCharset(Charsets.UTF_8),
                )
            }

            get("/web/list") { call.respondPortalFiles() }
            get("/api/files") { call.respondPortalFiles() }
            get("/api/list") { call.respondPortalFiles() }

            get("/download/all") { call.servePortalZip() }
            get("/api/download-all") { call.servePortalZip() }

            get("/web/get") { call.servePortalFile(call.request.queryParameters["id"]) }
            get("/download/{id}") { call.servePortalFile(call.parameters["id"]) }

            put("/web/up") { call.handlePortalUpload() }
            post("/upload") { call.handlePortalUpload() }
            post("/api/upload") { call.handlePortalUpload() }

            get("/favicon.ico") { call.respond(HttpStatusCode.NoContent) }
        }
    }

    private fun ApplicationCall.portalAuthorized(): Boolean {
        val query = request.queryParameters["t"]
        val header = request.header("X-Portal-Token")
        val bearer = request.header(HttpHeaders.Authorization)
            ?.trim()
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return g.portal.check(query ?: header ?: bearer)
    }

    private suspend fun ApplicationCall.respondPortalFiles() {
        response.headers.append(HttpHeaders.CacheControl, "no-store")
        response.headers.append("X-Content-Type-Options", "nosniff")
        if (!portalAuthorized()) {
            return respondText(
                """{"error":"invalid token"}""",
                ContentType.Application.Json,
                HttpStatusCode.Forbidden,
            )
        }
        respond(g.portal.files.value)
    }

    private suspend fun ApplicationCall.servePortalFile(id: String?) {
        if (id.isNullOrBlank()) {
            return respondText("Missing file id", status = HttpStatusCode.BadRequest)
        }
        val file = g.portal.file(id)
            ?: return respondText("Unknown file", status = HttpStatusCode.NotFound)

        val text = file.text
        if (text != null) {
            val name = if (file.name.endsWith(".txt", ignoreCase = true)) file.name else "${file.name}.txt"
            attachHeaders(name)
            return respondBytes(
                text.toByteArray(Charsets.UTF_8),
                ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                HttpStatusCode.OK,
            )
        }

        val uri = g.portal.uri(file)
            ?: return respondText("Unknown file", status = HttpStatusCode.NotFound)

        val total = resolveSize(uri, file.size)
        val mime = withContext(Dispatchers.IO) {
            runCatching { g.app.contentResolver.getType(uri) }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val contentType = runCatching { ContentType.parse(mime) }
            .getOrDefault(ContentType.Application.OctetStream)

        if (total < 0L) {
            val stream = openStream(uri)
                ?: return respondText("Unable to open file", status = HttpStatusCode.NotFound)
            attachHeaders(file.name)
            stream.use {
                respondOutputStream(contentType, HttpStatusCode.OK) {
                    val output = this
                    withContext(Dispatchers.IO) {
                        stream.copyTo(output, 256 * 1024)
                        output.flush()
                    }
                }
            }
            return
        }

        if (total == 0L) {
            attachHeaders(file.name)
            return respondOutputStream(contentType, HttpStatusCode.OK, 0L) { }
        }

        var start = 0L
        var end = total - 1
        var partial = false
        val range = request.header(HttpHeaders.Range)?.trim()
        if (!range.isNullOrEmpty() && range.startsWith("bytes=", ignoreCase = true)) {
            val parsed = parseRange(range, total)
            if (parsed == null) {
                response.headers.append(HttpHeaders.ContentRange, "bytes */$total")
                return respondText(
                    "Range not satisfiable",
                    status = HttpStatusCode.RequestedRangeNotSatisfiable,
                )
            }
            start = parsed.first
            end = parsed.second
            partial = true
        }

        val length = end - start + 1
        response.headers.append(HttpHeaders.AcceptRanges, "bytes")
        if (partial) {
            response.headers.append(HttpHeaders.ContentRange, "bytes $start-$end/$total")
        }

        val stream = openStream(uri)
            ?: return respondText("Unable to open file", status = HttpStatusCode.NotFound)
        attachHeaders(file.name)
        stream.use {
            respondOutputStream(
                contentType = contentType,
                status = if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                contentLength = length,
            ) {
                val output = this
                withContext(Dispatchers.IO) {
                    skipFully(stream, start)
                    copyExactly(stream, output, length)
                    output.flush()
                }
            }
        }
    }

    private suspend fun ApplicationCall.servePortalZip() {
        if (!portalAuthorized()) {
            return respondText("Invalid token", status = HttpStatusCode.Forbidden)
        }
        val files = g.portal.files.value
        if (files.isEmpty()) {
            return respondText("No files in package", status = HttpStatusCode.NotFound)
        }

        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        attachHeaders("OpenBridge-$stamp.zip")
        respondOutputStream(ContentType("application", "zip"), HttpStatusCode.OK) {
            val zip = ZipOutputStream(java.io.BufferedOutputStream(this))
            val used = mutableSetOf<String>()
            for ((index, file) in files.withIndex()) {
                val entryName = uniqueEntry(zipName(file, index), used)
                zip.putNextEntry(ZipEntry(entryName))
                val text = file.text
                if (text != null) {
                    zip.write(text.toByteArray(Charsets.UTF_8))
                } else {
                    val uri = g.portal.uri(file)
                    val stream = uri?.let {
                        withContext(Dispatchers.IO) {
                            runCatching { g.app.contentResolver.openInputStream(it) }.getOrNull()
                        }
                    }
                    if (stream != null) {
                        stream.use { input ->
                            withContext(Dispatchers.IO) { input.copyTo(zip, 256 * 1024) }
                        }
                    }
                }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
        }
    }

    private suspend fun ApplicationCall.handlePortalUpload() {
        if (!portalAuthorized()) {
            return respondText("Invalid token", status = HttpStatusCode.Forbidden)
        }

        val call = this
        val name = sanitizeName(request.queryParameters["name"] ?: "file")
        val rawType = request.header(HttpHeaders.ContentType)
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.contains('/') }
            ?: "application/octet-stream"
        Diag.log("portal", "upload $name ($rawType)")

        var savedUri: Uri? = null

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val uri = withContext(Dispatchers.IO) { createDownload(name, rawType) }
                ?: return respondText(
                    "Cannot create download",
                    status = HttpStatusCode.InternalServerError,
                )
            try {
                withContext(Dispatchers.IO) {
                    g.app.contentResolver.openOutputStream(uri)?.use { output ->
                        copyBody(call, output)
                    } ?: error("openOutputStream failed")
                }
                withContext(Dispatchers.IO) { finalizeDownload(uri) }
                savedUri = uri
            } catch (t: Throwable) {
                runCatching { g.app.contentResolver.delete(uri, null, null) }
                Diag.log("portal", "upload failed: $t")
                return respondText(
                    "Upload failed: ${t.message}",
                    status = HttpStatusCode.InternalServerError,
                )
            }
        } else {
            val temp = java.io.File(g.app.cacheDir, "web-$name")
            try {
                withContext(Dispatchers.IO) {
                    temp.outputStream().use { copyBody(call, it) }
                    savedUri = Downloads.save(g.app, temp, "web/$name", rawType)
                }
            } catch (t: Throwable) {
                Diag.log("portal", "upload failed: $t")
                return respondText(
                    "Upload failed: ${t.message}",
                    status = HttpStatusCode.InternalServerError,
                )
            } finally {
                temp.delete()
            }
        }

        savedUri?.let { uri ->
            val path = uri.toString()
            if (looksLikeText(rawType, name)) {
                val text = withContext(Dispatchers.IO) { readPortalText(uri) }
                if (!text.isNullOrBlank()) {
                    g.pluginEngine.onTextReceived(text, PORTAL_SENDER)
                } else {
                    g.pluginEngine.onFileReceived(name, path, rawType, PORTAL_SENDER)
                }
            } else {
                g.pluginEngine.onFileReceived(name, path, rawType, PORTAL_SENDER)
            }
        }

        respondText("ok")
    }

    private suspend fun copyBody(call: ApplicationCall, output: OutputStream) {
        val channel = call.receiveChannel()
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    private suspend fun ApplicationCall.resolveSize(uri: Uri, hint: Long): Long =
        withContext(Dispatchers.IO) {
            runCatching {
                g.app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    if (descriptor.length >= 0L) descriptor.length else -1L
                } ?: -1L
            }.getOrDefault(-1L).takeIf { it >= 0L }
                ?: runCatching {
                    g.app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                }.getOrNull()?.takeIf { it >= 0L }
                ?: hint.takeIf { it > 0L }
                ?: -1L
        }

    private suspend fun ApplicationCall.openStream(uri: Uri): InputStream? =
        withContext(Dispatchers.IO) {
            runCatching { g.app.contentResolver.openInputStream(uri) }.getOrNull()
        }

    private fun ApplicationCall.attachHeaders(name: String) {
        val clean = name.replace(Regex("[\\r\\n\"]"), "_").ifBlank { "file" }
        val ascii = clean.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }
        val encoded = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
        response.headers.append(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded",
        )
        response.headers.append(HttpHeaders.CacheControl, "no-store")
        response.headers.append("X-Content-Type-Options", "nosniff")
    }

    private fun parseRange(header: String, total: Long): Pair<Long, Long>? {
        val spec = header.substringAfter('=', "").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()

        var start: Long
        var end: Long
        if (left.isEmpty()) {
            val suffix = right.toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            start = (total - suffix).coerceAtLeast(0L)
            end = total - 1
        } else {
            start = left.toLongOrNull() ?: return null
            end = if (right.isEmpty()) total - 1 else right.toLongOrNull() ?: return null
        }
        if (start < 0L || start >= total || end < start) return null
        return start to minOf(end, total - 1)
    }

    private fun skipFully(input: InputStream, amount: Long) {
        var remaining = amount
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            remaining -= read
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, amount: Long) {
        val buffer = ByteArray(256 * 1024)
        var remaining = amount
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun zipName(file: PortalFile, index: Int): String {
        val raw = file.name.ifBlank { "file-$index" }
        val withExt = if (file.text != null && !raw.endsWith(".txt", ignoreCase = true)) {
            "$raw.txt"
        } else {
            raw
        }
        return withExt
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
            .ifBlank { "file-$index" }
    }

    private fun uniqueEntry(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$base ($index)$ext"
            if (used.add(candidate)) return candidate
            index++
        }
    }

    private fun looksLikeText(mime: String, name: String): Boolean =
        mime.startsWith("text/", ignoreCase = true) ||
            name.endsWith(".txt", ignoreCase = true) ||
            name.endsWith(".md", ignoreCase = true)

    /**
     * Reads a small text upload so it can be passed to `onTextReceived`.
     * Larger files stay file-only, which keeps memory use predictable.
     */
    private fun readPortalText(uri: Uri): String? = runCatching {
        val input = if (uri.scheme == "file") {
            uri.path?.let { java.io.File(it).inputStream() }
        } else {
            g.app.contentResolver.openInputStream(uri)
        }
        input.use { stream ->
            if (stream == null) return@use null

            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            var tooLarge = false
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_PORTAL_TEXT_BYTES) {
                    tooLarge = true
                    break
                }
                out.write(buffer, 0, read)
            }
            if (tooLarge) null else out.toString("UTF-8")
        }
    }.getOrNull()

    private fun createDownload(name: String, mime: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime.substringBefore(';').trim())
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/OpenBridge/web")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return g.app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun finalizeDownload(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { g.app.contentResolver.update(uri, values, null, null) }
    }

    private fun sanitizeName(name: String): String =
        name.replace('/', '_').replace('\\', '_').take(120).ifBlank { "file" }

    private companion object {
        const val PORTAL_SENDER = "QR Portal"
        const val MAX_PORTAL_TEXT_BYTES = 256 * 1024
    }
}
