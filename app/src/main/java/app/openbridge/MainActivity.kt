package app.openbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.openbridge.core.Diag
import app.openbridge.ui.AppRoot
import app.openbridge.ui.AppViewModel
import app.openbridge.util.NfcHelper

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Diag.log("ui", "MainActivity.onCreate")
        enableEdgeToEdge()

        setContent {
            AppRoot(viewModel)
        }

        handleIntent(intent, savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, true)
    }

    private fun handleIntent(intent: Intent?, allowShare: Boolean) {
        val nfcData = NfcHelper.extractNfcUri(intent)
        if (nfcData != null) {
            Diag.log("nfc", "received NFC data: $nfcData")
            viewModel.handleNfcReceived(nfcData)
        }

        if (allowShare && intent != null) handleShare(intent)

        if (intent?.getBooleanExtra(EXTRA_SEND_CLIPBOARD, false) != true) return
        intent.removeExtra(EXTRA_SEND_CLIPBOARD)

        window.decorView.postDelayed({ viewModel.sendClipboardNow() }, 350)
    }

    private fun handleShare(intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val uris = sharedUris(intent)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.action = null

        if (uris.isEmpty() && text.isEmpty()) return

        Diag.log("share", "share intent: ${uris.size} uri(s), ${text.length} chars")
        if (uris.isNotEmpty()) {
            viewModel.addUris(uris)
        } else {
            viewModel.addText(text)
        }
        viewModel.requestSendTab()
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val direct = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
            )

            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java,
            ).orEmpty()

            else -> emptyList()
        }
        val clip = intent.clipData?.let { data ->
            (0 until data.itemCount).mapNotNull { index -> data.getItemAt(index).uri }
        }.orEmpty()
        return (direct + clip).distinct()
    }

    companion object {
        const val EXTRA_SEND_CLIPBOARD = "send_clipboard"

        fun clipboardIntent(context: android.content.Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SEND_CLIPBOARD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
