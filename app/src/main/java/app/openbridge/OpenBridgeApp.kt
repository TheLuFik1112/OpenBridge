package app.openbridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.openbridge.core.AppGraph
import app.openbridge.core.CrashReporter
import app.openbridge.core.Diag
import app.openbridge.service.RadarService
import java.util.concurrent.atomic.AtomicInteger

class OpenBridgeApp : Application() {
    lateinit var graph: AppGraph
        private set

    private val startedActivities = AtomicInteger(0)

    val isInForeground: Boolean get() = startedActivities.get() > 0

    override fun onCreate() {
        super.onCreate()

        Diag.install(this)
        CrashReporter.install(this)
        Diag.log("app", "onCreate")

        graph = AppGraph(this)

        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities.incrementAndGet()
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities.decrementAndGet()
                if (startedActivities.get() < 0) startedActivities.set(0)
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }

    fun startRadarServiceIfEnabled() {
        runCatching { createChannels() }
            .onFailure { Diag.log("app", "channels failed: $it") }

        if (!graph.settings.serviceEnabled.value) {
            Diag.log("app", "service disabled in settings")
            return
        }
        Diag.log("app", "starting radar service")
        runCatching { RadarService.start(this) }
            .onFailure { Diag.log("app", "service start failed: $it") }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                RadarService.CHANNEL_ID,
                "Поиск устройств",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Работа в фоне: поиск устройств рядом и приём передач"
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                RadarService.CHANNEL_INCOMING,
                "Входящие передачи",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Запросы на приём файлов и буфера обмена"
                enableVibration(true)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                RadarService.CHANNEL_PROGRESS,
                "Передачи",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }
}
