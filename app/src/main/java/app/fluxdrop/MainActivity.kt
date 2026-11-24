package app.fluxdrop

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fluxdrop.core.Diag
import app.fluxdrop.ui.AppRoot
import app.fluxdrop.ui.AppViewModel
import app.fluxdrop.ui.FluxTheme
import app.fluxdrop.util.NfcHelper
import app.fluxdrop.util.NfcMode
import kotlinx.coroutines.launch

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

        setContent {
            FluxTheme {
                AppRoot(viewModel)
            }
        }

        // Reader mode живёт ровно столько, сколько экран активен, и только
        // в режиме «Принять». В режиме «Поделиться» он выключен — иначе
        // контроллер занят чтением и телефон не может эмулировать карту.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    viewModel.nfcMode.collect { mode -> applyNfcMode(mode) }
                } finally {
                    NfcHelper.stopReaderMode(this@MainActivity)
                }
            }
        }

        handleIntent(intent)
    }

    private fun applyNfcMode(mode: NfcMode) {
        when (mode) {
            NfcMode.RECEIVE -> NfcHelper.startReaderMode(this) { payload ->
                viewModel.handleNfcReceived(payload)
            }

            NfcMode.SHARE, NfcMode.OFF -> NfcHelper.stopReaderMode(this)
        }
        Diag.log("nfc", "mode=$mode")
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
