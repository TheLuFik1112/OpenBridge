package app.openbridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.openbridge.core.Diag
import app.openbridge.ui.AppRoot
import app.openbridge.ui.AppViewModel
import app.openbridge.util.NfcHelper

/**
 * Единственная задача Activity — показать первый кадр.
 * Ключи, сертификаты, сокеты и служба стартуют из AppViewModel в фоне.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Сплэш должен быть установлен до super.onCreate — это требование библиотеки.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Diag.log("ui", "MainActivity.onCreate")
        enableEdgeToEdge()

        // Тему выставляет сам AppRoot по настройке «Оформление».
        setContent {
            AppRoot(viewModel)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val nfcData = NfcHelper.extractNfcUri(intent)
        if (nfcData != null) {
            Diag.log("nfc", "received NFC data: $nfcData")
            viewModel.handleNfcReceived(nfcData)
        }

        if (intent?.getBooleanExtra(EXTRA_SEND_CLIPBOARD, false) != true) return
        intent.removeExtra(EXTRA_SEND_CLIPBOARD)
        // Окно ещё не получило фокус — читать буфер обмена сразу нельзя.
        window.decorView.postDelayed({ viewModel.sendClipboardNow() }, 350)
    }

    companion object {
        const val EXTRA_SEND_CLIPBOARD = "send_clipboard"

        fun clipboardIntent(context: android.content.Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SEND_CLIPBOARD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
