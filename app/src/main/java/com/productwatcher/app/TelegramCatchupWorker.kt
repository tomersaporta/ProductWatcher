package com.productwatcher.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay

/** Android-scheduled catch-up, not a promise of exact 15-minute delivery. */
class TelegramCatchupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!MonitorControl.enabled(applicationContext)) return Result.success()
        val ctx = applicationContext
        TelegramClient.resume(ctx)
        repeat(20) {
            if (TelegramClient.ready) {
                TelegramClient.scanSelectedChannel()
                // Keep the worker process alive while asynchronous TDLib/ML Kit work finishes.
                delay(5000)
                repeat(50) {
                    if (!TelegramOfferScanner.hasPendingWork()) return Result.success()
                    delay(1000)
                }
                return Result.retry()
            }
            delay(1000)
        }
        Store.setTelegramStatus(ctx, "Telegram not ready for scheduled catch-up: ${TelegramClient.state}")
        return Result.retry()
    }
}
