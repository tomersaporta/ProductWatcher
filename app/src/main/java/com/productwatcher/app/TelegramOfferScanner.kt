package com.productwatcher.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Scans Telegram captions and downloaded photos on-device. No upload or external OCR. */
object TelegramOfferScanner {
    private var app: Context? = null
    private val pending = AtomicInteger(0)
    @Volatile var summary: String = "No Telegram scan yet"
        private set
    var onStatus: (() -> Unit)? = null

    fun init(context: Context) { app = context.applicationContext }

    fun scan(chatId: Long, messages: List<TdApi.Message>) {
        val ctx = app ?: return
        val products = Store.products(ctx)
        var photos = 0
        var matches = 0
        for (message in messages) {
            val caption = when (val body = message.content) {
                is TdApi.MessageText -> body.text.text
                is TdApi.MessagePhoto -> body.caption.text
                else -> ""
            }
            if (reportMatches(ctx, chatId, message.id, caption, products)) matches++
            val photo = message.content as? TdApi.MessagePhoto ?: continue
            val best = photo.photo.sizes.maxByOrNull { it.width.toLong() * it.height } ?: continue
            photos++
            pending.incrementAndGet()
            TelegramClient.downloadPhoto(best.photo.id) { file ->
                if (file != null && file.local.isDownloadingCompleted && file.local.path.isNotBlank()) {
                    recognize(ctx, chatId, message.id, caption, file.local.path, products)
                } else finishOne()
            }
        }
        summary = "Read ${messages.size} posts · $photos photos · $matches caption matches · ${pending.get()} OCR pending"
        Store.setTelegramStatus(ctx, summary)
        onStatus?.invoke()
    }

    private fun recognize(ctx: Context, chatId: Long, messageId: Long, caption: String, path: String, products: List<String>) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromFilePath(ctx, android.net.Uri.fromFile(File(path)))
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    reportMatches(ctx, chatId, messageId, "$caption\n${result.text}", products)
                }
                .addOnFailureListener { e ->
                    Store.setTelegramStatus(ctx, "OCR error: ${e.message ?: "unknown"}")
                }
                .addOnCompleteListener { recognizer.close(); finishOne() }
        } catch (e: Exception) {
            recognizer.close()
            Store.setTelegramStatus(ctx, "OCR error: ${e.message ?: "unknown"}")
            finishOne()
        }
    }

    private fun finishOne() {
        val left = pending.decrementAndGet().coerceAtLeast(0)
        val ctx = app ?: return
        if (left == 0) {
            summary = "Telegram photo OCR complete · ${Store.telegramStatus(ctx)}"
            onStatus?.invoke()
        }
    }

    private fun reportMatches(ctx: Context, chatId: Long, messageId: Long, text: String, products: List<String>): Boolean {
        var found = false
        for (product in products) {
            if (!Matcher.matches(text, product)) continue
            found = true
            val key = "tg_${chatId}_${messageId}_${product.hashCode()}"
            if (Store.seen(ctx, key)) continue
            val price = Matcher.price(text) ?: "Price not found"
            val branch = Matcher.branch(text) ?: "Branch not identified"
            notifyOffer(ctx, product, price, branch, chatId, messageId)
            Store.markSeen(ctx, key)
            Store.setTelegramStatus(ctx, "Found $product · $price · $branch")
            onStatus?.invoke()
        }
        return found
    }

    private fun notifyOffer(ctx: Context, product: String, price: String, branch: String, chatId: Long, messageId: Long) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("telegram_offers", "Telegram product offers", NotificationManager.IMPORTANCE_HIGH)
        )
        val intent = Intent(ctx, TelegramActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val details = "$price · $branch"
        val notification = NotificationCompat.Builder(ctx, "telegram_offers")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Telegram: $product")
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$details\nChat: $chatId · Post: $messageId\nPrice and branch extracted from unverified post text"))
            .setContentIntent(pendingIntent).setAutoCancel(true).build()
        try { manager.notify((messageId xor chatId).toInt(), notification) } catch (_: SecurityException) { }
    }
}
