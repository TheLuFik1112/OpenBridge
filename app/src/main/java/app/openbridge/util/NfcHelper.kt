package app.openbridge.util

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import app.openbridge.core.Diag
import app.openbridge.core.IdentityStore
import app.openbridge.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

object NfcHelper {
    /** Legacy scheme, still parsed so older tags keep working. */
    private const val SCHEME = "openbridge"
    private const val HOST = "nfc"

    /**
     * The tag is a plain HTTPS link. Phones without OpenBridge open the
     * GitHub page in a browser instead of saying "no app for this tag";
     * phones with OpenBridge match the NDEF_DISCOVERED filter and start the
     * transfer from the `#ob=` fragment.
     */
    private const val PAGE_URL = "https://github.com/TheLuFik1112/OpenBridge"
    private const val FRAGMENT_PREFIX = "ob="

    data class Sender(val deviceId: String, val name: String)

    @Volatile
    private var senderId: String = ""

    @Volatile
    private var senderName: String = ""

    @Volatile
    private var tagActive: Boolean = false

    @Volatile
    private var lastServedAt: Long = 0L

    @Volatile
    private var primed: Boolean = false

    @Volatile
    private var cachedFile: ByteArray? = null

    @Volatile
    private var cachedFileKey: String? = null

    private val _served = MutableStateFlow(0)
    val served: StateFlow<Int> = _served.asStateFlow()

    fun notifyServed() {
        lastServedAt = System.currentTimeMillis()
        _served.value += 1
        Diag.log("nfc", "NDEF read by peer")
    }

    fun recentlyServed(windowMs: Long = 120_000L): Boolean =
        lastServedAt > 0L && System.currentTimeMillis() - lastServedAt <= windowMs

    /** Ends the NFC hand-off window so the next tap starts a fresh session. */
    fun clearHandoff() {
        lastServedAt = 0L
    }

    fun updateSender(id: String? = null, name: String? = null) {
        if (!id.isNullOrBlank() && id != senderId) {
            senderId = id.trim()
            invalidate()
        }
        if (name != null && name.trim() != senderName) {
            senderName = name.trim()
            invalidate()
        }
    }

    fun setTagActive(context: Context, active: Boolean) {
        if (active != tagActive) {
            tagActive = active
            invalidate()
        }

        // Выключенный компонент важнее STATUS_FAILED: Android не должен видеть пустую метку.
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, OpenBridgeApduService::class.java),
                if (active) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { Diag.log("nfc", "toggle HCE failed: $it") }
    }

    fun isTagActive(): Boolean = senderUri() != null

    fun prime(context: Context) {
        if (primed) return
        primed = true
        runCatching {
            val name = SettingsRepository(context).deviceName.value
            updateSender(name = name)
        }
        val app = context.applicationContext
        Thread {
            runCatching {
                val id = IdentityStore(app).identity.id
                updateSender(id = id)
                Diag.log("nfc", "sender id ready for tag")
            }.onFailure { Diag.log("nfc", "sender id failed: $it") }
        }.apply {
            name = "openbridge-nfc-prime"
            isDaemon = true
        }.start()
    }

    fun isNfcEnabled(context: Context): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return false
        return runCatching { adapter.isEnabled }.getOrDefault(false)
    }

    /**
     * True only when Bluetooth is actually usable: the adapter is on and, on
     * Android 12+, the app holds the Bluetooth runtime permissions. Nearby
     * runs inside Play Services, but a disabled radio still breaks it.
     */
    fun isBluetoothReady(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return false
        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        if (!enabled) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = runCatching {
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (!granted) return false
        }
        return true
    }

    fun buildNdefFile(): ByteArray {
        val uri = senderUri()
        val key = uri ?: "empty"
        cachedFile?.let { if (cachedFileKey == key) return it }

        if (uri == null) {
            val empty = byteArrayOf(0x00, 0x00)
            cachedFileKey = key
            cachedFile = empty
            return empty
        }

        // One standard well-known URI record, no AAR: Android hands the link to
        // OpenBridge when it is installed, and to a browser when it is not.
        val message = NdefMessage(arrayOf(NdefRecord.createUri(uri)))
        val bytes = message.toByteArray()

        if (bytes.size > MAX_NDEF_BYTES) {
            Diag.log("nfc", "[ERR] NDEF слишком велик: ${bytes.size} > $MAX_NDEF_BYTES")
            val empty = byteArrayOf(0x00, 0x00)
            cachedFileKey = key
            cachedFile = empty
            return empty
        }

        // Type 4 Tag NDEF file: 2-byte big-endian NLEN, then the NDEF message.
        val file = ByteArray(2 + bytes.size)
        file[0] = ((bytes.size ushr 8) and 0xFF).toByte()
        file[1] = (bytes.size and 0xFF).toByte()
        System.arraycopy(bytes, 0, file, 2, bytes.size)

        cachedFileKey = key
        cachedFile = file
        return file
    }

    private fun senderUri(): String? {
        if (!tagActive) return null
        val name = senderName.ifBlank { "Android" }
        if (senderId.isBlank() && name.isBlank()) return null

        val payload = JSONObject().apply {
            put("v", 1)
            put("n", name.take(40))
            if (senderId.isNotBlank()) put("i", senderId)
        }
        val encoded = Base64.encodeToString(
            payload.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "$PAGE_URL#$FRAGMENT_PREFIX$encoded"
    }

    private fun invalidate() {
        cachedFile = null
        cachedFileKey = null
    }

    fun parseSender(raw: String): Sender? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null

        // New form: https://github.com/TheLuFik1112/OpenBridge#ob=<base64url>
        val fragment = uri.fragment.orEmpty()
        if (fragment.startsWith(FRAGMENT_PREFIX)) {
            parseFragment(fragment.removePrefix(FRAGMENT_PREFIX))?.let { return it }
        }

        // Legacy custom scheme: openbridge://nfc?v=1&n=…&i=…
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        val id = uri.getQueryParameter("i").orEmpty().trim()
        val name = uri.getQueryParameter("n").orEmpty().trim()
        if (id.isBlank() && name.isBlank()) return null
        return Sender(deviceId = id, name = name)
    }

    private fun parseFragment(encoded: String): Sender? {
        if (encoded.isBlank()) return null

        val json = runCatching {
            val bytes = Base64.decode(
                encoded,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrNull()

        if (json != null) {
            val id = json.optString("i").trim()
            val name = json.optString("n").trim()
            if (id.isBlank() && name.isBlank()) return null
            return Sender(deviceId = id, name = name)
        }

        // Older experimental form: v1|<deviceId>|<name>
        val plain = runCatching {
            val bytes = Base64.decode(
                encoded,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            String(bytes, Charsets.UTF_8)
        }.getOrNull() ?: return null

        val parts = plain.split('|')
        if (parts.size < 2) return null
        val id = parts.getOrElse(1) { "" }.trim()
        val name = parts.getOrElse(2) { "" }.trim()
        if (id.isBlank() && name.isBlank()) return null
        return Sender(deviceId = id, name = name)
    }

    fun vibrate(context: Context) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(80)
            }
        }
    }

    fun extractNfcUri(intent: Intent?): String? {
        if (intent == null) return null

        val dataStr = intent.dataString
        if (!dataStr.isNullOrBlank() &&
            (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED || intent.action == Intent.ACTION_VIEW)
        ) {
            return dataStr
        }

        val rawMsgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }
        if (rawMsgs != null && rawMsgs.isNotEmpty()) {
            val message = rawMsgs[0] as? NdefMessage
            val uri = message?.records
                ?.firstNotNullOfOrNull { record -> record.toUri()?.toString() }
            if (!uri.isNullOrBlank()) return uri
        }

        return intent.data?.toString()?.takeIf { it.isNotBlank() }
    }

    /** Max NDEF message size advertised in the CC file (0x0FFE - NLEN). */
    private const val MAX_NDEF_BYTES = 0x0FFE - 2
}
