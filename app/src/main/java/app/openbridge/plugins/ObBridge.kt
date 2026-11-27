package app.openbridge.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.Keep

/**
 * The only bridge exposed to JavaScript. QuickJS needs a plain interface with
 * non-overloaded methods, so every method returns void and takes a String.
 */
@Keep
interface ObBridge {
    fun openUrl(url: String)
    fun copyToClipboard(text: String)
    fun toast(message: String)
    fun log(message: String)
}

/**
 * Sandbox implementation. Permissions from `manifest.json` decide whether the
 * script may open links or write to the clipboard; `toast` and `log` are always
 * available because they cannot change anything outside the app.
 */
@Keep
class AndroidObBridge(
    context: Context,
    private val pluginId: String,
    private val permissions: Set<String>,
) : ObBridge {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override fun openUrl(url: String) {
        if (PERMISSION_OPEN_URL !in permissions) {
            log("openUrl: permission open_url is missing")
            return
        }

        val clean = url.trim()
        if (clean.length > MAX_URL_LENGTH ||
            !(clean.startsWith("http://") || clean.startsWith("https://"))
        ) {
            log("openUrl: only http:// and https:// links are allowed")
            return
        }

        val uri = runCatching { Uri.parse(clean) }.getOrNull() ?: return
        if (uri.host.isNullOrBlank()) {
            log("openUrl: invalid link")
            return
        }

        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onFailure { log("openUrl failed: ${it.message}") }
    }

    override fun copyToClipboard(text: String) {
        if (PERMISSION_CLIPBOARD !in permissions) {
            log("copyToClipboard: permission clipboard is missing")
            return
        }

        val clean = text.take(MAX_CLIPBOARD_LENGTH)
        main.post {
            runCatching {
                val manager = appContext.getSystemService(ClipboardManager::class.java)
                manager?.setPrimaryClip(ClipData.newPlainText("OpenBridge", clean))
            }.onFailure { log("copyToClipboard failed: ${it.message}") }
        }
    }

    override fun toast(message: String) {
        val clean = message.take(MAX_TOAST_LENGTH)
        main.post {
            runCatching {
                Toast.makeText(appContext, clean, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun log(message: String) {
        Log.i(LOG_TAG, "[$pluginId] ${message.take(MAX_LOG_LENGTH)}")
    }

    private companion object {
        const val MAX_URL_LENGTH = 4096
        const val MAX_CLIPBOARD_LENGTH = 100_000
        const val MAX_TOAST_LENGTH = 240
        const val MAX_LOG_LENGTH = 2000
    }
}

const val LOG_TAG = "OBPlugin"
