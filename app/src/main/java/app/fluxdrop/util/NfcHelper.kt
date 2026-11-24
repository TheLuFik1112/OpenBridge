package app.fluxdrop.util

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
import app.fluxdrop.core.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NFC в FluxDrop работает в обе стороны одновременно и без режимов:
 *
 *  - отправка — HostApduService эмулирует Type 4 Tag с NDEF-ссылкой на портал
 *    (см. [FluxApduService]). Читает её сама система второго телефона, поэтому
 *    FluxDrop ничего не «включает» перед касанием;
 *  - приём — Android сам разбирает чужую метку и отдаёт ссылку в MainActivity
 *    как NDEF_DISCOVERED. Reader mode намеренно не используется: он занимает
 *    контроллер чтением и тогда телефон не может отвечать как карта.
 */
object NfcHelper {

    private const val GITHUB_DOWNLOAD_URL = "https://github.com/TheLuFik1112/FluxDrop/releases/latest"

    @Volatile
    private var payload: String? = null

    @Volatile
    private var cachedFile: ByteArray? = null

    @Volatile
    private var cachedFileKey: String? = null

    /** Счётчик удачных считываний метки: по нему UI показывает «Отправлено». */
    private val _served = MutableStateFlow(0)
    val served: StateFlow<Int> = _served.asStateFlow()

    /** Вызывается из [FluxApduService], когда второй телефон дочитал NDEF-файл. */
    fun notifyServed() {
        _served.value += 1
        Diag.log("nfc", "NDEF read by peer")
    }

    fun isNfcEnabled(context: Context): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return false
        return runCatching { adapter.isEnabled }.getOrDefault(false)
    }

    /**
     * Публикуемая по NFC ссылка. null означает «нет локального адреса» —
     * тогда отдаём страницу релизов, чтобы получатель всё равно смог скачать приложение.
     */
    fun updatePayload(url: String?) {
        val clean = url?.trim()?.takeIf { it.isNotEmpty() }
        payload = clean
        cachedFile = null
        cachedFileKey = null
        Diag.log("nfc", "payload: ${clean ?: "release link"}")
    }

    fun shareUrl(): String = payload ?: GITHUB_DOWNLOAD_URL

    /**
     * Готовый NDEF-файл формата Type 4 Tag: два байта NLEN + само сообщение.
     * Кешируется, потому что HCE читает его на каждый READ BINARY (иногда десятки раз).
     */
    fun buildNdefFile(): ByteArray {
        val key = shareUrl()
        cachedFile?.let { if (cachedFileKey == key) return it }

        val message = buildNdefMessage(key)
        val bytes = message.toByteArray()
        val file = ByteArray(2 + bytes.size)
        file[0] = ((bytes.size ushr 8) and 0xFF).toByte()
        file[1] = (bytes.size and 0xFF).toByte()
        System.arraycopy(bytes, 0, file, 2, bytes.size)

        cachedFileKey = key
        cachedFile = file
        return file
    }

    /**
     * Только URI-запись, без Android Application Record: AAR заставлял систему
     * открывать магазин приложений, если FluxDrop не установлен. Без него телефон
     * с FluxDrop открывает приложение (фильтр NDEF_DISCOVERED в манифесте),
     * а телефон без приложения — обычный браузер с живой страницей портала.
     */
    private fun buildNdefMessage(url: String): NdefMessage = NdefMessage(
        arrayOf(NdefRecord.createUri(Uri.parse(url)))
    )

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
