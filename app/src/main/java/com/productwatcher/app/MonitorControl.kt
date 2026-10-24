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

    fun ensureAutomatic(ctx: Context) {
        if (!enabled(ctx)) start(ctx)
    }

    fun start(ctx: Context) {
        setEnabled(ctx, true)
        val work = PeriodicWorkRequestBuilder<TelegramCatchupWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, work)
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, TelegramMonitorService::class.java)) }
        catch (e: Exception) { Store.setTelegramStatus(ctx, "Live service unavailable: ${e.message}. Scheduled catch-up enabled.") }
        TelegramClient.resume(ctx)
        TelegramClient.scanSelectedChannel()
    }
    fun stop(ctx: Context) {
        setEnabled(ctx, false)
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        ctx.stopService(Intent(ctx, TelegramMonitorService::class.java))
    }
}
