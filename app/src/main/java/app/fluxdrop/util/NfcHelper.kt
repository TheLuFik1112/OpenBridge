package app.fluxdrop.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.fluxdrop.core.Diag

/**
 * Роли NFC. Очень важно: Android не умеет одновременно быть считывателем
 * (enableReaderMode) и эмулировать карту (HCE). Пока включён reader mode,
 * контроллер занят только чтением, поэтому второе устройство не увидит
 * этот телефон как NFC-метку. Отсюда два явных режима:
 *
 *  - SHARE   — reader mode выключен, работает HostApduService (Type 4 Tag);
 *  - RECEIVE — включён reader mode, телефон читает чужую метку.
 */
enum class NfcMode { OFF, SHARE, RECEIVE }

object NfcHelper {

    private const val GITHUB_DOWNLOAD_URL = "https://github.com/TheLuFik1112/FluxDrop/releases/latest"
    private const val APP_PACKAGE = "app.fluxdrop.p2p"

    /** Максимальный размер NDEF-файла, который мы объявляем в Capability Container. */
    private const val NDEF_FILE_MAX = 0x0FFE

    /** Сколько байт за одно чтение: 254 укладывается в короткий APDU (Le ≤ 255). */
    private const val READ_CHUNK = 0xFE

    private val SELECT_AID = byteArrayOf(
        0x00, 0xA4.toByte(), 0x04, 0x00, 0x07,
        0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01, 0x00,
    )

    @Volatile
    private var payload: String? = null

    @Volatile
    private var cachedFile: ByteArray? = null

    @Volatile
    private var cachedFileKey: String? = null

    fun isNfcAvailable(context: Context): Boolean =
        NfcAdapter.getDefaultAdapter(context) != null

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
     * URI-запись + Android Application Record: получатель с FluxDrop откроет приложение,
     * без FluxDrop — браузер со ссылкой на скачивание.
     */
    private fun buildNdefMessage(url: String): NdefMessage = NdefMessage(
        arrayOf(
            NdefRecord.createUri(Uri.parse(url)),
            NdefRecord.createApplicationRecord(APP_PACKAGE),
        )
    )

    // ── чтение ───────────────────────────────────────────────────────────

    fun startReaderMode(activity: Activity, onPayloadReceived: (String) -> Unit) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            Diag.log("nfc", "reader mode skipped: NFC disabled")
            return
        }

        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }

        runCatching {
            adapter.enableReaderMode(
                activity,
                { tag: Tag -> handleTag(tag, activity, onPayloadReceived) },
                flags,
                extras,
            )
            Diag.log("nfc", "reader mode ON")
        }.onFailure { Diag.log("nfc", "enableReaderMode failed: $it") }
    }

    fun stopReaderMode(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        runCatching {
            adapter.disableReaderMode(activity)
            Diag.log("nfc", "reader mode OFF")
        }.onFailure { Diag.log("nfc", "disableReaderMode failed: $it") }
    }

    private fun handleTag(
        tag: Tag,
        activity: Activity,
        onPayloadReceived: (String) -> Unit,
    ) {
        // 1. Обычная NDEF-метка или HCE-карта, которую система уже разобрала.
        val uri = readNdef(tag) ?: readIsoDep(tag)

        if (uri.isNullOrBlank()) {
            Diag.log("nfc", "tag without URI: ${tag.techList.joinToString()}")
            return
        }
        Diag.log("nfc", "tag URI: $uri")
        vibrate(activity)
        activity.runOnUiThread { onPayloadReceived(uri) }
    }

    private fun readNdef(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            ndef.ndefMessage
                ?.records
                ?.firstNotNullOfOrNull { record -> record.toUri()?.toString() }
        } catch (t: Throwable) {
            Diag.log("nfc", "NDEF read failed: ${t.message}")
            null
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun readIsoDep(tag: Tag): String? {
        val iso = IsoDep.get(tag) ?: return null
        return try {
            iso.connect()
            iso.timeout = 3_000
            readType4(iso)
        } catch (t: Throwable) {
            Diag.log("nfc", "IsoDep read failed: ${t.message}")
            null
        } finally {
            runCatching { iso.close() }
        }
    }

    /** Чтение NDEF-файла E104 из стандартного Type 4 Tag по APDU. */
    private fun readType4(iso: IsoDep): String? {
        if (!isOk(iso.transceive(SELECT_AID))) {
            Diag.log("nfc", "SELECT Type4 AID rejected")
            return null
        }
        if (!isOk(iso.transceive(selectFile(0xE1, 0x04)))) {
            Diag.log("nfc", "SELECT NDEF file rejected")
            return null
        }

        val lenResponse = iso.transceive(
            byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x02)
        )
        if (!isOk(lenResponse) || lenResponse.size < 4) return null

        val nlen = ((lenResponse[0].toInt() and 0xFF) shl 8) or
            (lenResponse[1].toInt() and 0xFF)
        if (nlen <= 0 || nlen > NDEF_FILE_MAX) {
            Diag.log("nfc", "bad NLEN: $nlen")
            return null
        }

        val buffer = ByteArray(nlen)
        var offset = 0
        while (offset < nlen) {
            val chunk = minOf(READ_CHUNK, nlen - offset)
            val apdu = byteArrayOf(
                0x00, 0xB0.toByte(),
                ((offset ushr 8) and 0xFF).toByte(),
                (offset and 0xFF).toByte(),
                chunk.toByte(),
            )
            val response = iso.transceive(apdu)
            if (!isOk(response) || response.size < 3) return null
            val data = response.size - 2
            System.arraycopy(response, 0, buffer, offset, data)
            offset += data
        }

        return NdefMessage(buffer).records
            .firstNotNullOfOrNull { record -> record.toUri()?.toString() }
    }

    private fun isOk(response: ByteArray?): Boolean =
        response != null && response.size >= 2 &&
            response[response.size - 2] == 0x90.toByte() &&
            response[response.size - 1] == 0x00.toByte()

    private fun selectFile(hi: Int, lo: Int): ByteArray = byteArrayOf(
        0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, hi.toByte(), lo.toByte(),
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

    /**
     * Ссылка из intent'а NDEF_DISCOVERED / ACTION_VIEW.
     * AAR приводит к запуску FluxDrop с тем же NDEF-сообщением в extras.
     */
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
