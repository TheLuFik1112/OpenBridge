package app.openbridge.core

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    private const val PREFS = "openbridge.crash"
    private const val KEY_TIME = "pending_time"
    private const val KEY_BODY = "pending_body"
    private const val KEY_FILE = "pending_file"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { persist(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun persist(context: Context, thread: Thread, error: Throwable) {
        val stamp = System.currentTimeMillis()
        val body = buildString {
            appendLine("OpenBridge $APP_VERSION")
            appendLine(
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            )
            appendLine("Time: ${format(stamp)}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine(error.stackTraceToString())
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 5) {
                appendLine()
                appendLine("=== Cause ${depth + 1} ===")
                appendLine(cause.stackTraceToString())
                cause = cause.cause
                depth++
            }
            appendLine()
            appendLine("=== Last 200 log lines ===")
            appendLine(Diag.recentLines(200))
        }

        val stored = runCatching { Diag.saveText("last_crash.txt", body) }.getOrNull()
        Diag.log("crash", "report saved: $stored")

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_TIME, stamp)
            .putString(KEY_BODY, body)
            .putString(KEY_FILE, stored)
            .apply()
    }

    fun pending(context: Context): PendingCrash? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val body = prefs.getString(KEY_BODY, null) ?: return null
        if (body.isBlank()) return null
        return PendingCrash(
            createdAt = prefs.getLong(KEY_TIME, 0L),
            body = body,
            filePath = prefs.getString(KEY_FILE, null),
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun send(context: Context, crash: PendingCrash): String? {
        val name = "crash-report-${format(System.currentTimeMillis())}.txt"
            .replace(":", "-")
            .replace(" ", "_")
        Diag.log("crash", "sending report $name")
        return Diag.saveText(name, crash.body)
    }

    private fun format(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
}

data class PendingCrash(
    val createdAt: Long,
    val body: String,
    val filePath: String?,
) {
    val shortError: String
        get() = body.lineSequence()
            .firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?.trim()
            ?.take(160)
            ?: "Неизвестная ошибка"

    val timeLabel: String
        get() = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(createdAt))
}
