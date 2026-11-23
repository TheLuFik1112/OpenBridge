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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.fluxdrop.core.Diag
import java.nio.charset.StandardCharsets

object NfcHelper {

    private const val GITHUB_DOWNLOAD_URL = "https://github.com/TheLuFik1112/FluxDrop/releases/latest"
    private const val APP_PACKAGE = "app.fluxdrop.p2p"

    @Volatile
    var currentPayload: String? = null

    fun isNfcAvailable(context: Context): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        return adapter != null
    }

    fun isNfcEnabled(context: Context): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        return adapter != null && adapter.isEnabled
    }

    fun updatePayload(payload: String?) {
        currentPayload = payload ?: GITHUB_DOWNLOAD_URL
    }

    /**
     * Запускает современный режим считывания (Reader Mode).
     * Работает надёжно на всех современных версиях Android (включая Android 10-16).
     * При обнаружении другого телефона с FluxDrop или NFC-метки считывает данные
     * по протоколу ISO-DEP (APDU) или NDEF.
     */
    fun startReaderMode(
        activity: Activity,
        onPayloadReceived: (String) -> Unit,
    ) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        if (!adapter.isEnabled) return

        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        runCatching {
            adapter.enableReaderMode(activity, { tag: Tag ->
                handleTag(tag, activity, onPayloadReceived)
            }, flags, null)
            Diag.log("nfc", "Reader mode enabled")
        }.onFailure {
            Diag.log("nfc", "enableReaderMode failed: $it")
        }
    }

    fun stopReaderMode(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        runCatching {
            adapter.disableReaderMode(activity)
            Diag.log("nfc", "Reader mode disabled")
        }.onFailure {
            Diag.log("nfc", "disableReaderMode failed: $it")
        }
    }

    private fun handleTag(
        tag: Tag,
        activity: Activity,
        onPayloadReceived: (String) -> Unit,
    ) {
        // 1. Сначала пробуем IsoDep (связь телефон-телефон через FluxApduService HCE)
        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            runCatching {
                isoDep.connect()
                isoDep.timeout = 2000
                val response = isoDep.transceive(FluxApduService.SELECT_APDU)
                isoDep.close()

                if (response.size >= 2) {
                    val sw1 = response[response.size - 2]
                    val sw2 = response[response.size - 1]
                    if (sw1 == 0x90.toByte() && sw2 == 0x00.toByte()) {
                        val payload = String(response, 0, response.size - 2, StandardCharsets.UTF_8)
                        Diag.log("nfc", "IsoDep read success: $payload")
                        vibrate(activity)
                        activity.runOnUiThread { onPayloadReceived(payload) }
                        return
                    }
                }
            }.onFailure {
                Diag.log("nfc", "IsoDep transceive error: $it")
            }
        }

        // 2. Если не сработало — пробуем считать стандартный NDEF (метка или Android Beam)
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            runCatching {
                ndef.connect()
                val message = ndef.ndefMessage
                ndef.close()
                if (message != null && message.records.isNotEmpty()) {
                    val record = message.records.firstOrNull { it.toUri() != null }
                    val uri = record?.toUri()?.toString()
                    if (!uri.isNullOrBlank()) {
                        Diag.log("nfc", "NDEF read success: $uri")
                        vibrate(activity)
                        activity.runOnUiThread { onPayloadReceived(uri) }
                        return
                    }
                }
            }.onFailure {
                Diag.log("nfc", "Ndef read error: $it")
            }
        }
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
                vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(80)
            }
        }
    }

    /**
     * Настраивает NFC Beam (для старых версий Android, где он поддерживается).
     */
    fun setupNfcPush(
        activity: Activity,
        shareUrlProvider: () -> String?,
    ) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return

        runCatching {
            val callbackClass = Class.forName("android.nfc.NfcAdapter\$CreateNdefMessageCallback")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                activity.classLoader,
                arrayOf(callbackClass),
            ) { _, method, _ ->
                if (method.name == "createNdefMessage") {
                    val targetUrl = shareUrlProvider() ?: GITHUB_DOWNLOAD_URL
                    createNdefMessage(targetUrl)
                } else null
            }
            val setCallbackMethod = NfcAdapter::class.java.getMethod(
                "setNdefPushMessageCallback",
                callbackClass,
                Activity::class.java,
                Array<Activity>::class.java,
            )
            setCallbackMethod.invoke(
                adapter,
                proxy,
                activity,
                java.lang.reflect.Array.newInstance(Activity::class.java, 0),
            )
        }.onFailure {
            Diag.log("nfc", "setupNfcPush reflection fallback: $it")
        }
    }

    fun buildNdefFile(): ByteArray {
        val url = currentPayload ?: GITHUB_DOWNLOAD_URL
        val ndefMessage = createNdefMessage(url)
        val ndefBytes = ndefMessage.toByteArray()
        val ndefFile = ByteArray(2 + ndefBytes.size)
        ndefFile[0] = ((ndefBytes.size ushr 8) and 0xFF).toByte()
        ndefFile[1] = (ndefBytes.size and 0xFF).toByte()
        System.arraycopy(ndefBytes, 0, ndefFile, 2, ndefBytes.size)
        return ndefFile
    }

    private fun createNdefMessage(url: String): NdefMessage {
        val uriRecord = NdefRecord.createUri(Uri.parse(url))
        val aarRecord = NdefRecord.createApplicationRecord(APP_PACKAGE)
        return NdefMessage(arrayOf(uriRecord, aarRecord))
    }

    fun extractNfcUri(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED || intent.action == Intent.ACTION_VIEW) {
            val dataStr = intent.dataString
            if (!dataStr.isNullOrBlank()) return dataStr

            val rawMsgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            }
            if (rawMsgs != null && rawMsgs.isNotEmpty()) {
                val msg = rawMsgs[0] as? NdefMessage
                val uri = msg?.records?.firstOrNull { it.toUri() != null }?.toUri()?.toString()
                if (!uri.isNullOrBlank()) return uri
            }
        }
        return null
    }
}
