package com.productwatcher.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object MonitorControl {
    private const val WORK = "telegram_catchup"
    fun enabled(ctx: Context) = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).getBoolean("monitoring_enabled", false)
    fun setEnabled(ctx: Context, value: Boolean) = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).edit().putBoolean("monitoring_enabled", value).apply()
    /** Start automatically once a Telegram source exists, unless the user explicitly stopped it. */
    fun ensureAutomatic(ctx: Context) {
        val prefs = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE)
        if (prefs.getLong("chat_id", 0L) == 0L) return
        if (prefs.getBoolean("monitoring_user_stopped", false)) return
        // The UI is foreground: ensure the service is started even if Android killed it earlier.
        // Background/boot starts use WorkManager instead (Android 15 limits dataSync services).
        start(ctx)
    }
    fun ensureScheduled(ctx: Context) {
        if (!enabled(ctx)) return
        val work = PeriodicWorkRequestBuilder<TelegramCatchupWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, work)
    }
    fun start(ctx: Context) {
        ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).edit()
            .putBoolean("monitoring_user_stopped", false).apply()
        setEnabled(ctx, true)
        ensureScheduled(ctx)
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, TelegramMonitorService::class.java)) }
        catch (e: Exception) { Store.setTelegramStatus(ctx, "Live service unavailable: ${e.message}. Scheduled catch-up enabled.") }
        TelegramClient.resume(ctx)
        TelegramClient.scanSelectedChannel()
    }
    fun stop(ctx: Context) {
        ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).edit()
            .putBoolean("monitoring_user_stopped", true).apply()
        setEnabled(ctx, false)
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        ctx.stopService(Intent(ctx, TelegramMonitorService::class.java))
    }
}
