package app.openbridge.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object Diag {
    private const val MAX_LINES = 400
    private const val FOLDER = "OpenBridge"

    private val lines = ArrayDeque<String>()
    private val pending = ArrayDeque<String>()
    private var appContext: Context? = null
    private var installed = false

    @Volatile
    private var logStream: java.io.OutputStream? = null

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "openbridge-log").apply { isDaemon = true }
    }

    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed) return
        installed = true

        writer.execute { openLogFile(context) }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { log("CRASH", "${thread.name} ${error.javaClass.name}: ${error.message}") }
            runCatching { dump("crash", error) }
            previous?.uncaughtException(thread, error)
        }

        log("app", "start on ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}")
    }

    fun log(tag: String, message: String = "") {
        val line = "${stamp()} [$tag] $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        synchronized(pending) {
            pending.addLast(line)
            while (pending.size > MAX_LINES) pending.removeFirst()
        }

        writer.execute { flushPending() }
    }

    private fun flushPending() {
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            pending.toList().also { pending.clear() }
        }
        val stream = logStream ?: return
        runCatching {
            stream.write((batch.joinToString("\n") + "\n").toByteArray())
            stream.flush()
        }
    }

    private fun openLogFile(context: Context) {
        runCatching {
            val name = "log-${fileStamp()}.txt".replace(":", "-").replace(" ", "_")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + File.separator + FOLDER,
                    )
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                logStream = uri?.let { resolver.openOutputStream(it, "wa") }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FOLDER,
                )
                dir.mkdirs()
                logStream = File(dir, name).outputStream()
            }
        }
    }

    fun dump(kind: String = "report", error: Throwable? = null): String? {
        val context = appContext ?: return null
        val body = buildString {
            appendLine("OpenBridge $APP_VERSION")
            appendLine(
                "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            )
            appendLine("time: ${fileStamp()}")
            if (error != null) {
                appendLine()
                appendLine("=== ERROR ===")
                appendLine(error.stackTraceToString())
                var cause = error.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    appendLine()
                    appendLine("=== CAUSE ${depth + 1} ===")
                    appendLine(cause.stackTraceToString())
                    cause = cause.cause
                    depth++
                }
            }
            appendLine()
            appendLine("=== LOG ===")
            synchronized(lines) { lines.forEach { appendLine(it) } }
        }

        val name = "openbridge-$kind-${fileStamp()}.txt".replace(":", "-").replace(" ", "_")
        return runCatching { save(context, name, body) }.getOrElse { io ->
            log("diag", "save failed: $io")
            runCatching {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                val file = File(dir, name)
                file.writeText(body)
                file.absolutePath
            }.getOrNull()
        }
    }

    fun recentLines(count: Int = 200): String = synchronized(lines) {
        lines.takeLast(count).joinToString("\n")
    }

    fun saveText(name: String, body: String): String? {
        val context = appContext ?: return null
        return runCatching { save(context, name, body) }.getOrNull()
    }

    private fun save(context: Context, name: String, body: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + FOLDER,
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                return "Download/$FOLDER/$name"
            }
        }

        @Suppress("DEPRECATION")
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER,
        )
        if (publicDir.exists() || publicDir.mkdirs()) {
            val file = File(publicDir, name)
            file.writeText(body)
            return file.absolutePath
        }

        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, name)
        file.writeText(body)
        return file.absolutePath
    }

    private fun stamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun fileStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
