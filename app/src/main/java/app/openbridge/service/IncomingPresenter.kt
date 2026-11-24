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
import app.openbridge.core.Payload
import app.openbridge.core.TransferOffer
import app.openbridge.core.filesOrEmpty
import app.openbridge.core.formatBytes
import app.openbridge.overlay.IncomingRequestActivity
import app.openbridge.overlay.OverlaySheetHost
import app.openbridge.util.canUseFullScreenIntent

/**
 * Каскад показа входящего запроса:
 *  1. приложение на экране → прозрачная Activity с ModalBottomSheet;
 *  2. фон + SYSTEM_ALERT_WINDOW + экран разблокирован → overlay-шторка;
 *  3. иначе (в т.ч. keyguard) → heads-up уведомление с действиями.
 */
class IncomingPresenter(private val context: Context, private val g: AppGraph) {

    private val overlay by lazy {
        OverlaySheetHost(
            context = context,
            onAccept = { transferId, always -> g.incoming.accept(transferId, always) },
            onDecline = { transferId -> g.incoming.decline(transferId) },
        )
    }

    fun present(offer: TransferOffer) {
        val app = context.applicationContext as? OpenBridgeApp
        when {
            app?.isInForeground == true -> startActivity(offer)
            overlay.canShow() && !isDeviceLocked() -> overlay.show(offer)
            else -> g.notifier.headsUp(offer)
        }
    }

    fun onStage(transferId: String, stage: IncomingStage) {
        overlay.updateStage(stage)
        if (stage is IncomingStage.Done) g.notifier.clearProgress(transferId)
    }

    fun dismiss(transferId: String) {
        overlay.close()
        g.notifier.clearProgress(transferId)
    }

    private fun startActivity(offer: TransferOffer) {
        val intent = IncomingRequestActivity.intent(context, offer.transferId)
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
        }.onFailure { g.notifier.headsUp(offer) }
    }

    private fun isDeviceLocked(): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java)
        return km?.isKeyguardLocked == true
    }
}

class IncomingNotifier(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)

    fun headsUp(offer: TransferOffer) {
        val open = openIntent(offer.transferId, offer.transferId.hashCode())
        val accept = decisionIntent(offer.transferId, accept = true, requestCode = 11)
        val decline = decisionIntent(offer.transferId, accept = false, requestCode = 12)

        val builder = NotificationCompat.Builder(context, RadarService.CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_incoming)
            .setContentTitle(offer.sender.name)
            .setContentText(summary(offer))
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

    /** Тихий старт для доверенных: без шторки, только прогресс. */
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

    fun progress(offer: TransferOffer, done: Long, total: Long) {
        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val notification = NotificationCompat.Builder(context, RadarService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_incoming)
            .setContentTitle("Приём от ${offer.sender.name}")
            .setContentText("${formatBytes(done)} из ${formatBytes(total)}")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        runCatching { nm.notify(PROGRESS_ID, notification) }
    }

    fun clearProgress(transferId: String) {
        runCatching { nm.cancel(PROGRESS_ID) }
        runCatching { nm.cancel(INCOMING_ID) }
    }

    fun cancel(transferId: String) {
        runCatching { nm.cancel(INCOMING_ID) }
    }

    private fun summary(offer: TransferOffer): String = when (val payload = offer.payload) {
        is Payload.Text -> "Текст из буфера • ${payload.text.length} симв."
        is Payload.Files -> "${payload.files.size} файл(ов) • ${formatBytes(payload.filesOrEmpty.sumOf { it.size })}"
        is Payload.Mixed -> "Файлы (${payload.files.size}) и текст"
    }

    private fun openIntent(transferId: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            IncomingRequestActivity.intent(context, transferId),
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

    private fun decisionIntent(transferId: String, accept: Boolean, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode + transferId.hashCode(),
            Intent(context, DecisionReceiver::class.java)
                .setAction(DecisionReceiver.ACTION)
                .putExtra(DecisionReceiver.EXTRA_ID, transferId)
                .putExtra(DecisionReceiver.EXTRA_ACCEPT, accept),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val INCOMING_ID = 2001
        const val PROGRESS_ID = 2002
    }
}
