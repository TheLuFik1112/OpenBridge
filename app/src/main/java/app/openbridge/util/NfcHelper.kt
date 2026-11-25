package app.openbridge.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.openbridge.core.Diag
import app.openbridge.core.IdentityStore
import app.openbridge.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NFC в OpenBridge работает в обе стороны одновременно и без режимов:
 *
 *  - отправка — HostApduService эмулирует Type 4 Tag (см. [OpenBridgeApduService]).
 *    В метке лежит только свой `openbridge://nfc?...` с именем и id
 *    устройства-отправителя. Телефон с OpenBridge откроет приложение,
 *    найдёт отправителя по Nearby и заберёт пакет; при закрытой панели
 *    NFC-метка остаётся пустой и касание ничего не открывает;
 *  - приём — Android сам разбирает чужую метку и отдаёт ссылку в MainActivity
 *    как NDEF_DISCOVERED. Reader mode намеренно не используется: он занимает
 *    контроллер чтением и тогда телефон не может отвечать как карта.
 *
 * Локальный веб-портал по NFC больше не открывается: он остался отдельной
 * функцией для QR-кода.
 */
object NfcHelper {

    /** Схема, которую понимает только OpenBridge: браузер её открыть не может. */
    private const val SCHEME = "openbridge"
    private const val HOST = "nfc"

    /** Отправитель из метки: id нужен для поиска по Nearby, имя — для показа. */
    data class Sender(val deviceId: String, val name: String)

    @Volatile
    private var senderId: String = ""

    @Volatile
    private var senderName: String = ""

    /**
     * Метка отвечает, только пока на телефоне открыта панель NFC и в пакете
     * есть что отдать. Закрытая панель = пустая метка: второе устройство
     * ничего не откроет.
     */
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

    /** Счётчик удачных считываний метки: по нему UI показывает «Отправлено». */
    private val _served = MutableStateFlow(0)
    val served: StateFlow<Int> = _served.asStateFlow()

    /** Вызывается из [OpenBridgeApduService], когда второй телефон дочитал NDEF-файл. */
    fun notifyServed() {
        lastServedAt = System.currentTimeMillis()
        _served.value += 1
        Diag.log("nfc", "NDEF read by peer")
    }

    /**
     * Метку только что прочитали. В это окно получатель вправе соединиться
     * по Nearby без отдельного подтверждения — иначе после касания пришлось бы
     * ещё раз нажимать «Принять» на телефоне отправителя.
     */
    fun recentlyServed(windowMs: Long = 120_000L): Boolean =
        lastServedAt > 0L && System.currentTimeMillis() - lastServedAt <= windowMs

    /**
     * Кто мы для метки. Id берём из сертификата, имя — из настроек.
     * Пустые значения не затирают уже известные.
     */
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

    /** Открылась или закрылась панель NFC: метка начинает или перестаёт отвечать. */
    fun setTagActive(active: Boolean) {
        if (active != tagActive) {
            tagActive = active
            invalidate()
        }
    }

    /** В метке есть адрес отправителя: читателю есть что открыть. */
    fun isTagActive(): Boolean = senderUri() != null

    /**
     * Заполняет имя и id до первого касания. HCE-служба может быть запущена
     * системой, когда приложение вообще не открывали, поэтому имя читаем сразу
     * (это prefs), а сертификат — в фоне, чтобы не тормозить APDU.
     */
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
     * Готовый NDEF-файл формата Type 4 Tag: два байта NLEN + само сообщение.
     * Кешируется, потому что HCE читает его на каждый READ BINARY (иногда десятки раз).
     */
    fun buildNdefFile(): ByteArray {
        val uri = senderUri()
        val key = uri ?: "empty"
        cachedFile?.let { if (cachedFileKey == key) return it }

        // Панель закрыта — папка NDEF пустая: ни приложения, ни браузера,
        // ни страницы скачивания. Касание двух телефонов без пакета молчит.
        if (uri == null) {
            val empty = byteArrayOf(0x00, 0x00)
            cachedFileKey = key
            cachedFile = empty
            return empty
        }

        val records = ArrayList<NdefRecord>(2)
        records.add(NdefRecord.createUri(Uri.parse(uri)))
        val bytes = NdefMessage(records.toTypedArray()).toByteArray()
        val file = ByteArray(2 + bytes.size)
        file[0] = ((bytes.size ushr 8) and 0xFF).toByte()
        file[1] = (bytes.size and 0xFF).toByte()
        System.arraycopy(bytes, 0, file, 2, bytes.size)

        cachedFileKey = key
        cachedFile = file
        return file
    }

    /** Адрес отправителя для метки; null — панель закрыта, метка пустая. */
    private fun senderUri(): String? {
        if (!tagActive) return null
        val name = senderName.ifBlank { "Android" }
        if (senderId.isBlank() && name.isBlank()) return null
        val builder = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendQueryParameter("v", "1")
            .appendQueryParameter("n", name.take(40))
        if (senderId.isNotBlank()) builder.appendQueryParameter("i", senderId)
        return builder.build().toString()
    }

    private fun invalidate() {
        cachedFile = null
        cachedFileKey = null
    }

    /** Разбор метки OpenBridge: `openbridge://nfc?v=1&i=<id>&n=<имя>`. */
    fun parseSender(raw: String): Sender? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        val id = uri.getQueryParameter("i").orEmpty().trim()
        val name = uri.getQueryParameter("n").orEmpty().trim()
        if (id.isBlank() && name.isBlank()) return null
        return Sender(deviceId = id, name = name)
    }

    // ── мелочи ───────────────────────────────────────────────────────────

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

    /** Ссылка из intent'а NDEF_DISCOVERED / ACTION_VIEW. */
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
}
