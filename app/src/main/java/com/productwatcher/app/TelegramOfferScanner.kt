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
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Bound downloads/OCR to avoid hundreds of concurrent photos during historical backfill. */
object TelegramOfferScanner {
    private var app: Context? = null
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val queue = ArrayDeque<() -> Unit>()
    private val queueLock = Any()
    private const val MAX_CONCURRENT_PHOTOS = 3
    private var working = 0
    private val pending = AtomicInteger(0)
    @Volatile var onStatus: (() -> Unit)? = null

    fun init(ctx: Context) { app = ctx.applicationContext }
    fun hasPendingWork(): Boolean = pending.get() > 0 || inFlight.isNotEmpty()

    private fun queuePhoto(job: () -> Unit) {
        synchronized(queueLock) { queue.addLast(job) }
        dispatch()
    }
    private fun dispatch() {
        val next = mutableListOf<() -> Unit>()
        synchronized(queueLock) {
            while (working < MAX_CONCURRENT_PHOTOS && queue.isNotEmpty()) {
                working++
                next.add(queue.removeFirst())
            }
        }
        next.forEach { it() }
    }
    private fun releasePhotoSlot() {
        synchronized(queueLock) { working-- }
        dispatch()
    }

    /** The callback is called only after the whole batch has completed (or failed). */
    fun scan(chatId: Long, messages: List<TdApi.Message>, onComplete: () -> Unit = {}) {
        val ctx = app ?: run { onComplete(); return }
        if (!MonitorControl.enabled(ctx) || Store.monitoredChannels(ctx).none { it.first == chatId }) {
            onComplete(); return
        }
        val generation = Store.scanGeneration(ctx)
        val eligible = messages.mapNotNull { message ->
            val key = "tg_processed_${generation}_${chatId}_${message.id}"
            if (Store.seen(ctx, key) || !inFlight.add(key)) null else key to message
        }
        if (eligible.isEmpty()) { onComplete(); return }
        val remaining = AtomicInteger(eligible.size)
        fun itemFinished(key: String) {
            inFlight.remove(key)
            if (remaining.decrementAndGet() == 0) onComplete()
        }
        for ((key, message) in eligible) {
            Store.channelEvent(ctx, chatId, "Checking Telegram post ${message.id}", "posts")
            Store.products(ctx).forEach { Store.productEvent(ctx, it, "Checking post ${message.id}") }
            val caption = when (val content = message.content) {
                is TdApi.MessageText -> content.text.text
                is TdApi.MessagePhoto -> content.caption.text
                else -> ""
            }
            val photo = message.content as? TdApi.MessagePhoto
            if (photo == null) {
                completed(ctx, key, chatId, message.id, caption, emptyList(), true)
                itemFinished(key)
                continue
            }
            val best = photo.photo.sizes.filter { it.photo.id > 0 }
                .maxByOrNull { it.width.toLong() * it.height }
            if (best == null) {
                completed(ctx, key, chatId, message.id, "No downloadable Telegram photo size", emptyList(), false)
                itemFinished(key)
                continue
            }
            pending.incrementAndGet()
            queuePhoto {
                Store.channelEvent(ctx, chatId, "Downloading image from post ${message.id}", "images")
                status(ctx, "Telegram photo OCR: ${pending.get()} remaining (up to 3 at once)")
                TelegramClient.downloadPhoto(best.photo.id) { file ->
                    val path = file?.local?.path.orEmpty()
                    if (file?.local?.isDownloadingCompleted != true || path.isBlank() || !File(path).isFile) {
                        completed(ctx, key, chatId, message.id, "Telegram photo download failed; will retry", emptyList(), false)
                        pending.decrementAndGet()
                        itemFinished(key)
                        releasePhotoSlot()
                    } else {
                        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        try {
                            val image = InputImage.fromFilePath(ctx, android.net.Uri.fromFile(File(path)))
                            recognizer.process(image)
                                .addOnSuccessListener { result ->
                                    val lines = result.textBlocks.flatMap { block -> block.lines.mapNotNull { line ->
                                        line.boundingBox?.let { rect ->
                                            OcrLine(line.text, rect.left, rect.top, rect.right, rect.bottom)
                                        }
                                    } }
                                    Store.channelEvent(ctx, chatId, "OCR read ${lines.size} lines in post ${message.id}", "ocr")
                                    Store.recordOcrDiagnostic(ctx, chatId, message.id, result.text)
                                    // Use position-based product/price linking, never first-price-in-photo.
                                    if (result.text.isBlank() && Store.products(ctx).none { Matcher.matches(caption, it) }) {
                                        completed(ctx, key, chatId, message.id, "OCR returned no text; retry scheduled", emptyList(), false)
                                    } else {
                                        completed(ctx, key, chatId, message.id, "$caption\n${result.text}",
                                            OfferExtractor.extract(lines, Store.products(ctx), caption), true)
                                        if (Store.deleteTelegramImages(ctx)) TelegramClient.deleteDownloadedPhoto(best.photo.id)
                                    }
                                }
                                .addOnFailureListener { e ->
                                    completed(ctx, key, chatId, message.id, "OCR failed: ${e.message}", emptyList(), false)
                                }
                                .addOnCompleteListener {
                                    recognizer.close()
                                    pending.decrementAndGet()
                                    itemFinished(key)
                                    releasePhotoSlot()
                                }
                        } catch (e: Exception) {
                            recognizer.close()
                            completed(ctx, key, chatId, message.id, "Image error: ${e.message}", emptyList(), false)
                            pending.decrementAndGet()
                            itemFinished(key)
                            releasePhotoSlot()
                        }
                    }
                }
            }
        }
    }

    private fun completed(ctx: Context, key: String, chatId: Long, messageId: Long,
                          ocr: String, offers: List<OfferMatch>, success: Boolean) {
        Store.recordMessageOutcome(ctx, chatId, messageId, success)
        if (!success) {
            Store.products(ctx).forEach { Store.productEvent(ctx, it, "Post $messageId failed · retry needed") }
            Store.channelEvent(ctx, chatId, "Post $messageId: ${ocr.take(100)}")
            status(ctx, "Post $messageId failed: ${ocr.take(70)}")
            return // Never mark a failed message processed.
        }
        val products = Store.products(ctx)
        val byName = offers.associateBy { it.product }
        products.forEach { product ->
            val match = byName[product]
            Store.productEvent(ctx, product,
                if (match != null) "Found in post $messageId · ${match.price ?: "price uncertain"}"
                else "Checked post $messageId · no match",
                checked = true, matched = match != null)
        }
        for (offer in offers) {
            val offerKey = "offer_${chatId}_${messageId}_${offer.product.hashCode()}"
            Store.recordOffer(ctx, offer, chatId, messageId)
            if (Store.seen(ctx, offerKey)) continue
            val branch = Matcher.branch(ocr) ?: "Branch not identified"
            Store.channelEvent(ctx, chatId, "Matched ${offer.product} · ${offer.price ?: "price unknown"} · $branch", "matches")
            if (notifyOffer(ctx, offer.product, offer.price ?: "Price not identified", branch, chatId, messageId)) {
                Store.markSeen(ctx, offerKey)
                Store.productEvent(ctx, offer.product, "Alert sent · ${offer.price ?: "price uncertain"}", alerted = true)
            }
            status(ctx, "Match: ${offer.product} · ${offer.price ?: "price uncertain"}")
        }
        Store.markSeen(ctx, key)
        if (offers.isEmpty()) status(ctx, "Checked post $messageId · ${pending.get()} photos pending")
    }

    private fun status(ctx: Context, message: String) {
        Store.setTelegramStatus(ctx, message)
        onStatus?.invoke()
    }

    private fun notifyOffer(ctx: Context, product: String, price: String, branch: String, chatId: Long, messageId: Long): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            status(ctx, "Found $product, but notification permission is missing")
            return false
        }
        return try {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel("telegram_offers", "Telegram product offers", NotificationManager.IMPORTANCE_HIGH))
            val intent = Intent(ctx, TelegramActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("offer_chat_id", chatId)
                putExtra("offer_message_id", messageId)
            }
            val notificationId = (chatId xor messageId xor product.hashCode().toLong()).toInt()
            val pendingIntent = PendingIntent.getActivity(ctx, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(notificationId, NotificationCompat.Builder(ctx, "telegram_offers")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Found: $product")
                .setContentText("$price · $branch")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$price · $branch\nTelegram post $messageId · OCR details unverified"))
                .setContentIntent(pendingIntent).setAutoCancel(true).build())
            Store.channelEvent(ctx, chatId, "Notification sent for $product", "alerts")
            true
        } catch (e: Exception) {
            Store.channelEvent(ctx, chatId, "Notification failed: ${e.message}")
            false
        }
    }
}
