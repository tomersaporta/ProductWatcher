package com.productwatcher.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/** User-started foreground monitoring. Keeps TDLib alive while Android permits it. */
class TelegramMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            if (!MonitorControl.enabled(this@TelegramMonitorService)) return
            TelegramClient.resume(this@TelegramMonitorService)
            if (TelegramClient.ready) TelegramClient.scanSelectedChannel()
            updateNotification()
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Telegram monitoring", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !MonitorControl.enabled(this)) {
            MonitorControl.setEnabled(this, false)
            if (intent?.action == ACTION_STOP) {
                getSharedPreferences("telegram_source", MODE_PRIVATE).edit()
                    .putBoolean("monitoring_user_stopped", true).apply()
                androidx.work.WorkManager.getInstance(this).cancelUniqueWork("telegram_catchup")
            }
            handler.removeCallbacks(refresh)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = notification()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Store.setTelegramStatus(this, "Live service blocked: ${e.message}; periodic catch-up remains enabled")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, TelegramActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, TelegramMonitorService::class.java).apply { action = ACTION_STOP }
        val stop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = getSharedPreferences("telegram_source", MODE_PRIVATE).getString("chat_title", "No channel selected")
        val detail = if (TelegramClient.ready) "Connected · $channel" else "${TelegramClient.state} · $channel"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ProductWatcher · monitoring")
            .setContentText(detail)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop monitoring", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
    }

    override fun onDestroy() { handler.removeCallbacks(refresh); super.onDestroy() }
    // Android 15+ imposes a time budget on dataSync foreground services.
    // Stop cleanly; WorkManager continues catch-up checks when permitted.
    override fun onTimeout(startId: Int, fgsType: Int) {
        handler.removeCallbacks(refresh)
        Store.setTelegramStatus(this, "Live monitoring timed out; scheduled catch-up remains enabled")
        stopSelf(startId)
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "telegram_monitor"
        private const val NOTIFICATION_ID = 2048
        const val ACTION_STOP = "com.productwatcher.app.STOP_MONITOR"
    }
}
