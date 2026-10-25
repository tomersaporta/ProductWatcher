package com.productwatcher.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
                completed(ctx, key, chatId, message, caption,
                    OfferExtractor.extract(emptyList(), Store.products(ctx), caption), true)
                itemFinished(key)
                continue
            }
            val best = photo.photo.sizes.filter { it.photo.id > 0 }
                .maxByOrNull { it.width.toLong() * it.height }
            if (best == null) {
                completed(ctx, key, chatId, message, "No downloadable Telegram photo size", emptyList(), false)
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
                        completed(ctx, key, chatId, message, "Telegram photo download failed; will retry", emptyList(), false)
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
                                        completed(ctx, key, chatId, message, "OCR returned no text; retry scheduled", emptyList(), false)
                                    } else {
                                        completed(ctx, key, chatId, message, "$caption\n${result.text}",
                                            OfferExtractor.extract(lines, Store.products(ctx), caption), true)
                                        if (Store.deleteTelegramImages(ctx)) TelegramClient.deleteDownloadedPhoto(best.photo.id)
                                    }
                                }
                                .addOnFailureListener { e ->
                                    completed(ctx, key, chatId, message, "OCR failed: ${e.message}", emptyList(), false)
                                }
                                .addOnCompleteListener {
                                    recognizer.close()
                                    pending.decrementAndGet()
                                    itemFinished(key)
                                    releasePhotoSlot()
                                }
                        } catch (e: Exception) {
                            recognizer.close()
                            completed(ctx, key, chatId, message, "Image error: ${e.message}", emptyList(), false)
                            pending.decrementAndGet()
                            itemFinished(key)
                            releasePhotoSlot()
                        }
                    }
                }
            }
        }
    }

    private fun completed(ctx: Context, key: String, chatId: Long, message: TdApi.Message,
                          ocr: String, offers: List<OfferMatch>, success: Boolean) {
        val messageId = message.id
        Store.recordMessageOutcome(ctx, chatId, messageId, success)
        if (!success) {
            Store.products(ctx).forEach { Store.productEvent(ctx, it, "Post $messageId failed · retry needed") }
            Store.channelEvent(ctx, chatId, "Post $messageId: ${ocr.take(100)}")
            status(ctx, "Post $messageId failed: ${ocr.take(70)}")
            return // Never mark a failed message processed.
        }
        val products = Store.products(ctx)
        val branch = Matcher.branch(ocr) ?: "Branch not identified"
        val channel = Store.monitoredChannels(ctx).firstOrNull { it.first == chatId }?.second ?: "Telegram channel"
        val saved = offers.map { offer ->
            Store.recordOffer(ctx, offer, chatId, messageId, message.date.toLong() * 1000L, branch, channel)
        }
        val byName = saved.associateBy { it.offer.product }
        products.forEach { product ->
            val match = byName[product]
            Store.productEvent(ctx, product,
                if (match != null) "Found in post $messageId · ${match.offer.price}"
                else "Checked post $messageId · no match",
                checked = true, matched = match?.inserted == true)
        }
        saved.filter { it.inserted }.forEach {
            Store.channelEvent(ctx, chatId, "Matched ${it.offer.product} · ${it.offer.price} · $branch", "matches")
        }
        if (saved.isNotEmpty()) TelegramClient.messageLink(chatId, messageId) { link ->
            for (result in saved) {
                val record = if (link != null) Store.updateOfferLink(ctx, result.offer.id, link) ?: result.offer else result.offer
                val offerKey = "offer_${chatId}_${messageId}_${record.product.hashCode()}"
                if (!Store.seen(ctx, offerKey) && notifyOffer(ctx, record)) {
                    Store.markSeen(ctx, offerKey)
                    Store.productEvent(ctx, record.product, "Alert sent · ${record.price}", alerted = true)
                }
                status(ctx, "Match: ${record.product} · ${record.price}")
            }
            onStatus?.invoke()
        }
        Store.markSeen(ctx, key)
        if (offers.isEmpty()) status(ctx, "Checked post $messageId · ${pending.get()} photos pending")
    }

    private fun status(ctx: Context, message: String) {
        Store.setTelegramStatus(ctx, message)
        onStatus?.invoke()
    }

    private fun notifyOffer(ctx: Context, offer: FoundOffer): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            status(ctx, "Found ${offer.product}, but notification permission is missing")
            return false
        }
        return try {
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel("telegram_offers", "Telegram product offers", NotificationManager.IMPORTANCE_HIGH))
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("found_offer_id", offer.id)
            }
            val notificationId = offer.id.hashCode()
            val pendingIntent = PendingIntent.getActivity(ctx, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(ctx, "telegram_offers")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Found: ${offer.product}")
                .setContentText("${offer.price} · ${offer.branch}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "${offer.price} · ${offer.branch}\n${offer.channel} · OCR price is unverified"))
                .setContentIntent(pendingIntent).setAutoCancel(true)
            if (offer.messageLink.isNotBlank()) {
                val telegramIntent = Intent(Intent.ACTION_VIEW, Uri.parse(offer.messageLink)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val telegramPending = PendingIntent.getActivity(ctx, notificationId xor 0x54e1, telegramIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                notification.addAction(android.R.drawable.ic_menu_view, "Open Telegram post", telegramPending)
            }
            manager.notify(notificationId, notification.build())
            Store.channelEvent(ctx, offer.chatId, "Notification sent for ${offer.product}", "alerts")
            true
        } catch (e: Exception) {
            Store.channelEvent(ctx, offer.chatId, "Notification failed: ${e.message}")
            false
        }
    }
}
