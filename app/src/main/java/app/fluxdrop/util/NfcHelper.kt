package app.fluxdrop.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import app.fluxdrop.core.Diag

object NfcHelper {

    private const val GITHUB_DOWNLOAD_URL = "https://github.com/TheLuFik1112/FluxDrop/releases/latest"
    private const val APP_PACKAGE = "app.fluxdrop.p2p"

    fun isNfcAvailable(context: Context): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        return adapter != null
    }

    fun isNfcEnabled(context: Context): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        return adapter != null && adapter.isEnabled
    }

    /**
     * Настраивает NFC передачу при касании телефонов.
     * Если у второго телефона есть FluxDrop — он откроет приложение и примет данные.
     * Если FluxDrop не установлен — откроет браузер со ссылкой на скачивание APK.
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

    private fun createNdefMessage(url: String): NdefMessage {
        val uriRecord = NdefRecord.createUri(Uri.parse(url))
        // Android Application Record: запускает app.fluxdrop.p2p, если установлено
        val aarRecord = NdefRecord.createApplicationRecord(APP_PACKAGE)
        return NdefMessage(arrayOf(uriRecord, aarRecord))
    }

    /**
     * Разбирает входящий NFC Intent. Возвращает переданную ссылку/текст, если есть.
     */
    fun extractNfcUri(intent: Intent?): String? {
        if (intent == null || NfcAdapter.ACTION_NDEF_DISCOVERED != intent.action) return null
        return intent.dataString
    }
}
