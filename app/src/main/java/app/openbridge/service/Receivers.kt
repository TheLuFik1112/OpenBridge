package app.openbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.openbridge.OpenBridgeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val promptId = intent.getStringExtra(EXTRA_ID) ?: return
        val accept = intent.getBooleanExtra(EXTRA_ACCEPT, false)

        val graph = (context.applicationContext as? OpenBridgeApp)?.graph ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (accept) {
                    graph.presenter.accept(promptId, always = false)
                } else {
                    graph.presenter.decline(promptId)
                }
            } finally {
                graph.notifier.cancel(promptId)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "app.openbridge.DECISION"
        const val EXTRA_ID = "transfer_id"
        const val EXTRA_ACCEPT = "accept"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val graph = (context.applicationContext as? OpenBridgeApp)?.graph ?: return
            if (graph.settings.serviceEnabled.value) {
                RadarService.start(context)
            }
        }
    }
}
