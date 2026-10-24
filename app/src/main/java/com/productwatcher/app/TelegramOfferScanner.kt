package com.productwatcher.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** On-device Telegram photo OCR. Message completion is persisted only after OCR succeeds. */
object TelegramOfferScanner {
    private var app: Context? = null
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val pending = AtomicInteger()
    @Volatile var onStatus: (() -> Unit)? = null
    fun init(ctx: Context) { app = ctx.applicationContext }
    fun hasPendingWork(): Boolean = pending.get() > 0 || inFlight.isNotEmpty()

    fun scan(chatId: Long, messages: List<TdApi.Message>) {
        val ctx = app ?: return
        if (!MonitorControl.enabled(ctx)) return
        val selected = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).getLong("chat_id", 0L)
        if (selected != chatId) return
        for (message in messages) {
            val key = "tg_processed_${chatId}_${message.id}"
            if (Store.seen(ctx, key) || !inFlight.add(key)) continue
            val caption = when (val body = message.content) {
                is TdApi.MessageText -> body.text.text
                is TdApi.MessagePhoto -> body.caption.text
                else -> ""
            }
            val photo = message.content as? TdApi.MessagePhoto
            if (photo == null) {
                finish(ctx, key, chatId, message.id, caption, true)
                continue
            }
            val best = photo.photo.sizes.filter { it.photo.id > 0 }
                .maxByOrNull { it.width.toLong() * it.height }
            if (best == null) { inFlight.remove(key); continue }
            pending.incrementAndGet()
            status(ctx, "Processing ${pending.get()} Telegram image(s)…")
            TelegramClient.downloadPhoto(best.photo.id) { file ->
                val path = file?.local?.path.orEmpty()
                if (file?.local?.isDownloadingCompleted == true && path.isNotBlank() && File(path).exists()) {
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    try {
                        val image = InputImage.fromFilePath(ctx, android.net.Uri.fromFile(File(path)))
                        recognizer.process(image)
                            .addOnSuccessListener { result -> finish(ctx, key, chatId, message.id, "$caption\n${result.text}", true) }
                            .addOnFailureListener { e -> finish(ctx, key, chatId, message.id, "OCR failed: ${e.message}", false) }
                            .addOnCompleteListener { recognizer.close(); pending.decrementAndGet() }
                    } catch (e: Exception) {
                        recognizer.close()
                        finish(ctx, key, chatId, message.id, "Image error: ${e.message}", false)
                        pending.decrementAndGet()
                    }
                } else {
                    finish(ctx, key, chatId, message.id, "Telegram photo download failed", false)
                    pending.decrementAndGet()
                }
            }
        }
    }

    private fun finish(ctx: Context, key: String, chatId: Long, messageId: Long, text: String, success: Boolean) {
        try {
            if (success) {
                val matches = Store.products(ctx).filter { Matcher.matches(text, it) }
                matches.forEach { product ->
                    val offerKey = "offer_${chatId}_${messageId}_${product.hashCode()}"
                    if (!Store.seen(ctx, offerKey)) {
                        val price = Matcher.price(text) ?: "Price not identified"
                        val branch = Matcher.branch(text) ?: "Branch not identified"
                        notifyOffer(ctx, product, price, branch, chatId, messageId)
                        Store.markSeen(ctx, offerKey)
                        status(ctx, "Match: $product · $price · $branch")
                    }
                }
                Store.markSeen(ctx, key)
                if (matches.isEmpty()) status(ctx, "Checked Telegram post $messageId · ${pending.get()} images pending")
            } else status(ctx, text + " · will retry on next catch-up")
        } finally { inFlight.remove(key) }
    }

    private fun status(ctx: Context, message: String) {
        Store.setTelegramStatus(ctx, message)
        onStatus?.invoke()
    }

    private fun notifyOffer(ctx: Context, product: String, price: String, branch: String, chatId: Long, messageId: Long) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            status(ctx, "Match found, but notification permission is missing: $product")
            return
        }
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("telegram_offers", "Telegram product offers", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(ctx, TelegramActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("offer_chat_id", chatId)
            putExtra("offer_message_id", messageId)
        }
        val notificationId = (chatId xor messageId).toInt()
        val pendingIntent = PendingIntent.getActivity(ctx, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val detail = "$price · $branch"
        val notification = NotificationCompat.Builder(ctx, "telegram_offers")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Found: $product")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\nTelegram post $messageId · Price and branch unverified"))
            .setContentIntent(pendingIntent).setAutoCancel(true).build()
        try { manager.notify(notificationId, notification) } catch (_: SecurityException) { }
    }
}
