package com.hazem.wifiusage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Always-on tracking: a foreground service with a small permanent notification, so Android
 * doesn't kill the app. It samples every 30 seconds and keeps the notification up to date.
 */
class TrackerService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val loop = object : Runnable {
        override fun run() {
            Thread {
                try {
                    WifiUsage.sampleNow(applicationContext)
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
                } catch (e: Exception) { }
            }.start()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "التتبع المستمر", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // "location" type lets Android show us the Wi-Fi name while we run in the background,
                // but it is only allowed once the location permission is granted.
                val hasLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                val type = if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                startForeground(NOTIF_ID, n, type)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(loop)
        handler.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val now = System.currentTimeMillis()
        val today = Fmt.text(UsageDb.get(this).total(null, Billing.startOfDay(0), now))
        val ssid = WifiUsage.currentSsid(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (ssid != null) "متصل بـ $ssid" else "تتبع الواي فاي شغال")
            .setContentText("استهلاك اليوم: $today")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "tracker"
        private const val NOTIF_ID = 1
    }
}

/** Starts everything again after the phone reboots or the app is updated. */
object Boot {
    fun start(context: Context) {
        val work = PeriodicWorkRequestBuilder<SampleWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("wifi_sampler", ExistingPeriodicWorkPolicy.KEEP, work)
        NetworkWatcher.register(context)
        if (Prefs.keepAlive(context)) {
            try {
                context.startForegroundService(Intent(context, TrackerService::class.java))
            } catch (e: Exception) { }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Boot.start(context.applicationContext)
    }
}
