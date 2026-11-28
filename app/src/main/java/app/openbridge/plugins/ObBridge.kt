package app.openbridge.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.Keep
import app.openbridge.ui.EffectPlayer
import app.openbridge.ui.parseEffectColor

@Keep
interface ObBridge {
    fun openUrl(url: String)
    fun copyToClipboard(text: String)
    fun toast(message: String)
    fun log(message: String)
    fun playEffect(type: String, optionsJson: String)
    fun vibrate(durationMs: Int)
}

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

    override fun playEffect(type: String, optionsJson: String) {
        if (PERMISSION_EFFECTS !in permissions) {
            log("playEffect: permission effects is missing")
            return
        }

        val effect = type.trim().lowercase()
        if (effect !in EFFECT_TYPES) {
            log("playEffect: unknown type \"$type\"")
            return
        }

        main.post {
            runCatching {
                val options = runCatching { parsePluginJson(optionsJson) }.getOrNull()
                EffectPlayer.play(
                    context = appContext,
                    type = effect,
                    color = parseEffectColor(options?.optString("color").orEmpty()),
                    durationMs = options?.optInt("durationMs", DEFAULT_DURATION_MS)
                        ?: DEFAULT_DURATION_MS,
                    origin = options?.optString("origin").orEmpty(),
                )
            }.onFailure { log("playEffect failed: ${it.message}") }
        }
    }

    override fun vibrate(durationMs: Int) {
        if (PERMISSION_VIBRATE !in permissions) {
            log("vibrate: permission vibrate is missing")
            return
        }

        val ms = durationMs.coerceIn(MIN_VIBRATION_MS, MAX_VIBRATION_MS)
        main.post {
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager =
                        appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    manager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (vibrator?.hasVibrator() == true) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                }
            }.onFailure { log("vibrate failed: ${it.message}") }
        }
    }

    private companion object {
        const val MAX_URL_LENGTH = 4096
        const val MAX_CLIPBOARD_LENGTH = 100_000
        const val MAX_TOAST_LENGTH = 240
        const val MAX_LOG_LENGTH = 2000
        const val MIN_VIBRATION_MS = 10
        const val MAX_VIBRATION_MS = 200
        const val DEFAULT_DURATION_MS = 900
        val EFFECT_TYPES = setOf("wave", "glow")
    }
}

const val LOG_TAG = "OBPlugin"

internal fun parsePluginJson(raw: String): org.json.JSONObject? {
    val clean = raw.trim()
    if (clean.isEmpty() || clean == "undefined" || clean == "null") return null
    return runCatching { org.json.JSONObject(clean) }.getOrNull()
}
