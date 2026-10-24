package com.productwatcher.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object MonitorControl {
    fun enabled(ctx: Context) = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).getBoolean("monitoring_enabled", false)
    fun setEnabled(ctx: Context, value: Boolean) = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).edit().putBoolean("monitoring_enabled", value).apply()
    fun start(ctx: Context) {
        setEnabled(ctx, true)
        ContextCompat.startForegroundService(ctx, Intent(ctx, TelegramMonitorService::class.java))
    }
    fun stop(ctx: Context) {
        setEnabled(ctx, false)
        ctx.stopService(Intent(ctx, TelegramMonitorService::class.java))
    }
}
