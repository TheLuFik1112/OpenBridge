package app.openbridge.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.openbridge.OpenBridgeApp
import app.openbridge.MainActivity
import app.openbridge.R
import app.openbridge.core.AppGraph
import app.openbridge.core.Diag
import kotlinx.coroutines.launch

/**
 * Постоянный фон: TLS-сервер, UDP/mDNS/BLE-обнаружение, приём передач.
 * FGS type connectedDevice; если BT/Wi-Fi разрешения не выданы — откат на dataSync.
 */
class RadarService : Service() {

    private lateinit var graph: AppGraph
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        Diag.log("service", "onCreate")
        graph = (application as OpenBridgeApp).graph
        acquireMulticastLock()
        startForegroundCompat(buildNotification(0))
        Diag.log("service", "foreground started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Всё тяжёлое (ключи, сертификат, сокеты, обнаружение) — в фоне:
        // onStartCommand нельзя задерживать, иначе белый экран и ANR.
        graph.scope.launch {
            runCatching { graph.startRuntime() }
                .onFailure {
                    Diag.log("service", "startRuntime failed: $it")
                    Diag.dump("runtime", it)
                }
            updateNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { graph.stopRuntime() }
        releaseMulticastLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireMulticastLock() {
        runCatching {
            val wm = getSystemService(WifiManager::class.java) ?: return
            multicastLock = wm.createMulticastLock("openbridge-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private fun startForegroundCompat(notification: Notification) {
        val connectedDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val type = if (connectedDevice) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        }.onFailure {
            val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            runCatching { ServiceCompat.startForeground(this, NOTIF_ID, notification, fallback) }
                .onFailure { stopSelf() }
        }
    }

    private fun updateNotification() {
        val peers = graph.peers.discovered.value.size
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification(peers))
        }
    }

    private fun buildNotification(peerCount: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RadarService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle("OpenBridge рядом")
            .setContentText(
                if (peerCount > 0) "Найдено устройств: $peerCount" else "Ищу устройства в сети…"
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Остановить", stop)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "radar"
        const val CHANNEL_INCOMING = "incoming"
        const val CHANNEL_PROGRESS = "progress"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "app.openbridge.STOP_RADAR"

        fun start(context: Context) {
            val intent = Intent(context, RadarService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, RadarService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
