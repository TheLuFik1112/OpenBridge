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
        // 1. Сначала пробуем NDEF (стандарт для меток и HCE Type 4 Tag)
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            runCatching {
                ndef.connect()
                val message = ndef.ndefMessage
                ndef.close()
                if (message != null && message.records.isNotEmpty()) {
                    for (record in message.records) {
                        val uri = record.toUri()?.toString()
                        if (!uri.isNullOrBlank()) {
                            Diag.log("nfc", "NDEF URI read: $uri")
                            vibrate(activity)
                            activity.runOnUiThread { onPayloadReceived(uri) }
                            return
                        }
                    }
                }
            }.onFailure {
                Diag.log("nfc", "NDEF read failed: $it")
            }
        }

        // 2. Если NDEF напрямую не считался — пробуем IsoDep (Type 4 Tag APDU)
        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            runCatching {
                isoDep.connect()
                isoDep.timeout = 2000
                // SELECT AID D2760000850101
                val aidResp = isoDep.transceive(FluxApduService.SELECT_AID_APDU)
                if (aidResp.size >= 2 && aidResp[aidResp.size - 2] == 0x90.toByte()) {
                    // SELECT NDEF file (00 A4 00 0C 02 E1 04)
                    val selectNdef = byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x04)
                    val ndefSelectResp = isoDep.transceive(selectNdef)
                    if (ndefSelectResp.size >= 2 && ndefSelectResp[ndefSelectResp.size - 2] == 0x90.toByte()) {
                        // READ NLEN (00 B0 00 00 02)
                        val readLen = byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x02)
                        val lenResp = isoDep.transceive(readLen)
                        if (lenResp.size >= 4 && lenResp[lenResp.size - 2] == 0x90.toByte()) {
                            val nlen = ((lenResp[0].toInt() and 0xFF) shl 8) or (lenResp[1].toInt() and 0xFF)
                            if (nlen in 1..2048) {
                                val readMsg = byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x02, minOf(nlen, 255).toByte())
                                val msgResp = isoDep.transceive(readMsg)
                                if (msgResp.size >= 2 && msgResp[msgResp.size - 2] == 0x90.toByte()) {
                                    val msgBytes = java.util.Arrays.copyOf(msgResp, msgResp.size - 2)
                                    val parsed = NdefMessage(msgBytes)
                                    val uri = parsed.records.firstOrNull { it.toUri() != null }?.toUri()?.toString()
                                    if (!uri.isNullOrBlank()) {
                                        isoDep.close()
                                        Diag.log("nfc", "IsoDep Type4 read: $uri")
                                        vibrate(activity)
                                        activity.runOnUiThread { onPayloadReceived(uri) }
                                        return
                                    }
                                }
                            }
                        }
                    }
                }
                isoDep.close()
            }.onFailure {
                Diag.log("nfc", "IsoDep transceive error: $it")
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
     * Настраивает NFC Beam (для старых версий Android, где он поддерживался).
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
        return NdefMessage(arrayOf(uriRecord))
    }

    fun extractNfcUri(intent: Intent?): String? {
        if (intent == null) return null

        // 1. Из dataString (приходит с NDEF_DISCOVERED или ACTION_VIEW)
        val dataStr = intent.dataString
        if (!dataStr.isNullOrBlank() && (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED || intent.action == Intent.ACTION_VIEW)) {
            return dataStr
        }

        // 2. Из NDEF сообщений в extras (может прийти при любом action, включая ACTION_MAIN)
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

        // 3. Прямой data URI если доступен
        intent.data?.toString()?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }
}
