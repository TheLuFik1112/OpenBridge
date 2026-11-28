package app.openbridge.service

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.openbridge.OpenBridgeApp
import app.openbridge.R
import app.openbridge.core.AppGraph
import app.openbridge.core.IncomingStage
import app.openbridge.core.PeerInfo
import app.openbridge.core.Payload
import app.openbridge.core.TransferDirection
import app.openbridge.core.TransferOffer
import app.openbridge.core.TransferProgress
import app.openbridge.core.filesOrEmpty
import app.openbridge.core.formatBytes
import app.openbridge.core.formatSpeed
import app.openbridge.discovery.NearbyRequest
import app.openbridge.overlay.IncomingRequestActivity
import app.openbridge.overlay.OverlaySheetHost
import app.openbridge.util.canUseFullScreenIntent
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class IncomingPresenter(private val context: Context, private val g: AppGraph) {
    private class Pending(
        val prompt: IncomingPrompt,
        val accept: (Boolean) -> Unit,
        val decline: () -> Unit,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    private val overlay by lazy {
        OverlaySheetHost(
            context = context,
            onAccept = { id, always -> accept(id, always) },
            onDecline = { id -> decline(id) },
        )
    }

    fun prompt(id: String): IncomingPrompt? = pending[id]?.prompt

    fun present(offer: TransferOffer) {
        present(
            prompt = IncomingPrompt.fromOffer(offer),
            accept = { always -> g.incoming.accept(offer.transferId, always) },
            decline = { g.incoming.decline(offer.transferId) },
        )
    }

    fun presentNearby(request: NearbyRequest) {
        present(
            prompt = IncomingPrompt.fromNearby(request),
            accept = { always ->
                g.scope.launch {
                    if (always && request.deviceId.isNotBlank()) {
                        g.peers.trust(
                            PeerInfo(
                                id = request.deviceId,
                                name = request.name,
                                certFingerprint = request.deviceId,
                            )
                        )
                    }
                    g.nearby.accept(request.endpointId)
                }
            },
            decline = { g.nearby.decline(request.endpointId) },
        )
    }

    private fun present(
        prompt: IncomingPrompt,
        accept: (Boolean) -> Unit,
        decline: () -> Unit,
    ) {
        pending[prompt.id] = Pending(prompt, accept, decline)

        val app = context.applicationContext as? OpenBridgeApp
        when {
            app?.isInForeground == true -> startActivity(prompt)
            overlay.canShow() && !isDeviceLocked() -> {
                if (!overlay.show(prompt)) g.notifier.headsUp(prompt)
            }
            else -> g.notifier.headsUp(prompt)
        }
    }

    fun accept(id: String, always: Boolean) {
        val item = pending.remove(id) ?: return
        item.accept(always)
        g.notifier.cancel(id)
        if (!item.prompt.trackProgress) {
            overlay.close()
            g.notifier.clearProgress(id)
        }
    }

    fun decline(id: String) {
        val item = pending.remove(id) ?: return
        overlay.close()
        g.notifier.clearProgress(id)
        item.decline()
    }

    fun onStage(transferId: String, stage: IncomingStage) {
        overlay.updateStage(transferId, stage)
        if (stage is IncomingStage.Done) g.notifier.clearProgress(transferId)
    }

    fun dismiss(transferId: String) {
        pending.remove(transferId)
        overlay.close()
        g.notifier.clearProgress(transferId)
    }

    private fun startActivity(prompt: IncomingPrompt) {
        val intent = IncomingRequestActivity.intent(context, prompt.id)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }
                context.startActivity(intent, options.toBundle())
            } else {
                context.startActivity(intent)
            }
        }.onFailure { g.notifier.headsUp(prompt) }
    }

    private fun isDeviceLocked(): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java)
        return km?.isKeyguardLocked == true
    }
}

class IncomingNotifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun headsUp(prompt: IncomingPrompt) {
        val open = openIntent(prompt.id, prompt.id.hashCode())
        val accept = decisionIntent(prompt.id, accept = true, requestCode = 11)
        val decline = decisionIntent(prompt.id, accept = false, requestCode = 12)

        val builder = NotificationCompat.Builder(context, RadarService.CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_incoming)
            .setContentTitle(prompt.peerName)
            .setContentText("${prompt.headline} · ${prompt.transport.lowercase()}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Принять", accept)
            .addAction(0, "Отклонить", decline)

        if (canUseFullScreenIntent(context)) {
            builder.setFullScreenIntent(open, true)
        }
        runCatching { nm.notify(INCOMING_ID, builder.build()) }
    }

    fun autoAcceptStarted(offer: TransferOffer) {
        val notification = NotificationCompat.Builder(context, RadarService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_incoming)
            .setContentTitle("Приём от ${offer.sender.name}")
            .setContentText(summary(offer))
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        runCatching { nm.notify(PROGRESS_ID, notification) }
    }

    fun transferProgress(state: TransferProgress) {
        if (state.direction != TransferDirection.RECEIVING) return
        val indeterminate = state.totalBytes <= 0L
        val percent = if (indeterminate) {
            0
        } else {
            ((state.bytesTransferred * 100L) / state.totalBytes).toInt().coerceIn(0, 100)
        }
        val text = buildString {
            if (indeterminate) {
                append(formatBytes(state.bytesTransferred))
            } else {
                append(formatBytes(state.bytesTransferred))
                append(" / ")
                append(formatBytes(state.totalBytes))
            }
            if (state.speedBytesPerSec > 0L) {
                append(" · ")
                append(formatSpeed(state.speedBytesPerSec))
            }
        }
        val notification = NotificationCompat.Builder(context, RadarService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_incoming)
            .setContentTitle("Приём от ${state.peerName.ifBlank { "устройства" }}")
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        runCatching { nm.notify(PROGRESS_ID, notification) }
    }

    fun clearProgress(transferId: String) {
        runCatching { nm.cancel(PROGRESS_ID) }
        runCatching { nm.cancel(INCOMING_ID) }
    }

    fun clearTransferProgress() {
        runCatching { nm.cancel(PROGRESS_ID) }
    }

    fun cancel(transferId: String) {
        runCatching { nm.cancel(INCOMING_ID) }
    }

    private fun summary(offer: TransferOffer): String = when (val payload = offer.payload) {
        is Payload.Text -> "Текст из буфера • ${payload.text.length} симв."
        is Payload.Files -> "${payload.files.size} файл(ов) • ${formatBytes(payload.filesOrEmpty.sumOf { it.size })}"
        is Payload.Mixed -> "Файлы (${payload.files.size}) и текст"
    }

    private fun openIntent(promptId: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            IncomingRequestActivity.intent(context, promptId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }.toBundle()
            } else {
                null
            },
        )

    private fun decisionIntent(promptId: String, accept: Boolean, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode + promptId.hashCode(),
            Intent(context, DecisionReceiver::class.java)
                .setAction(DecisionReceiver.ACTION)
                .putExtra(DecisionReceiver.EXTRA_ID, promptId)
                .putExtra(DecisionReceiver.EXTRA_ACCEPT, accept),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val INCOMING_ID = 2001
        const val PROGRESS_ID = 2002
    }
}
