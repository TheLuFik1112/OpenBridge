package app.fluxdrop.net

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.fluxdrop.core.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Один элемент пакета на портале отправителя. */
data class NfcItem(
    val id: String,
    val name: String,
    val size: Long,
    val text: String?,
)

data class NfcResult(
    val files: Int,
    val texts: List<String>,
    val bytes: Long,
)

/**
 * Приём по NFC без диалогов: касание даёт ссылку на портал отправителя,
 * а дальше всё качается по обычному HTTP в той же Wi-Fi сети.
 *
 * Работает в своём процессе на HttpURLConnection, поэтому не зависит от
 * готовности движка: приложение могло быть только что запущено касанием.
 */
object NfcPortal {

    private const val CONNECT_TIMEOUT = 6_000
    private const val READ_TIMEOUT = 60_000
    private const val BUFFER = 128 * 1024

    /** http://ip:53531/web?t=token — живой портал, а не страница релизов. */
    fun isPortalUrl(raw: String): Boolean {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.getQueryParameter("t").isNullOrBlank()) return false
        return (uri.path ?: "").startsWith("/web")
    }

    suspend fun receive(
        context: Context,
        rawUrl: String,
        onProgress: (Float) -> Unit,
    ): NfcResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(rawUrl)
        val token = uri.getQueryParameter("t").orEmpty()
        val base = "${uri.scheme}://${uri.authority}"

        val items = fetchList(base, token)
        if (items.isEmpty()) throw IOException("empty")

        val totalBytes = items.sumOf { if (it.text != null) 0L else it.size }
            .coerceAtLeast(1L)
        var doneBytes = 0L
        var files = 0
        val texts = mutableListOf<String>()

        for (item in items) {
            val inline = item.text
            if (inline != null) {
                texts += inline
                continue
            }
            downloadItem(context, base, token, item) { delta ->
                doneBytes += delta
                onProgress((doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
            }
            files++
        }

        onProgress(1f)
        Diag.log("nfc", "received $files file(s), ${texts.size} text(s), $doneBytes bytes")
        NfcResult(files = files, texts = texts, bytes = doneBytes)
    }

    // ── портал ───────────────────────────────────────────────────────────

    private fun fetchList(base: String, token: String): List<NfcItem> {
        val body = request("$base/web/list?t=${Uri.encode(token)}")
        val array = JSONArray(body)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    NfcItem(
                        id = id,
                        name = obj.optString("name").ifBlank { "file" },
                        size = obj.optLong("size", 0L),
                        text = obj.optString("text", "").takeIf { it.isNotEmpty() },
                    )
                )
            }
        }
    }

    private fun downloadItem(
        context: Context,
        base: String,
        token: String,
        item: NfcItem,
        onChunk: (Long) -> Unit,
    ) {
        val url = "$base/web/get?t=${Uri.encode(token)}&id=${Uri.encode(item.id)}"
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val mime = conn.contentType?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val name = sanitize(item.name.ifBlank { "fluxdrop-${item.id.take(6)}" })

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, conn, name, mime, onChunk)
            } else {
                saveLegacy(context, conn, name, mime, onChunk)
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        conn: HttpURLConnection,
        name: String,
        mime: String,
        onChunk: (Long) -> Unit,
    ) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/FluxDrop/nfc")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("media store insert failed")
        try {
            resolver.openOutputStream(target)?.use { output ->
                copy(conn, output, onChunk)
            } ?: throw IOException("openOutputStream failed")
            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(target, null, null) }
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        conn: HttpURLConnection,
        name: String,
        mime: String,
        onChunk: (Long) -> Unit,
    ) {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(root, "FluxDrop/nfc").apply { mkdirs() }
        val temp = File(context.cacheDir, "nfc-$name")
        try {
            temp.outputStream().use { copy(conn, it, onChunk) }
            Downloads.save(context, temp, "nfc/$name", mime)
        } finally {
            temp.delete()
        }
    }

    private fun copy(
        conn: HttpURLConnection,
        output: java.io.OutputStream,
        onChunk: (Long) -> Unit,
    ) {
        val input = conn.inputStream
        val buffer = ByteArray(BUFFER)
        input.use {
            while (true) {
                val read = it.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                onChunk(read.toLong())
            }
        }
        output.flush()
    }

    private fun request(url: String): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "*/*")
        }

    private fun sanitize(name: String): String {
        val clean = name
            .replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .take(120)
        return clean.ifBlank { "file" }
    }
}
