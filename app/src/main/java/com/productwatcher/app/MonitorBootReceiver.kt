package com.productwatcher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Re-register periodic catch-up after reboot; does not launch a prohibited dataSync FGS from boot. */
class MonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Do not launch a dataSync foreground service from BOOT_COMPLETED (Android 15+).
        // WorkManager will resume scanning when background execution is permitted.
        if (!MonitorControl.enabled(context)) return
        val work = PeriodicWorkRequestBuilder<TelegramCatchupWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("telegram_catchup", ExistingPeriodicWorkPolicy.KEEP, work)
    }
}
