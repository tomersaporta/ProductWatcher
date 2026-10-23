package com.productwatcher.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.security.MessageDigest

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val uri = Store.folder(context)?.let(Uri::parse) ?: return Result.success()
        val products = Store.products(context)
        val resolver = context.contentResolver
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var scanned = 0
        var found = 0
        return try {
            val treeId = DocumentsContract.getTreeDocumentId(uri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(uri, treeId)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeId)
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
                val docs = mutableListOf<Triple<String, Long, Long>>()
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val mime = cursor.getString(1) ?: ""
                    if (mime.startsWith("image/")) docs.add(Triple(id, cursor.getLong(2), cursor.getLong(3)))
                }
                // Limit each pass to recent files; older images can be reached on later passes
                // only if under the cap. Avoid claiming a full historical scan.
                for ((id, modified, size) in docs.sortedByDescending { it.second }.take(150)) {
                    val key = MessageDigest.getInstance("SHA-256").digest("$id:$modified:$size".toByteArray()).joinToString("") { "%02x".format(it) }
                    if (Store.seen(context, key)) continue
                    val imageUri = DocumentsContract.buildDocumentUriUsingTree(uri, id)
                    try {
                        val image = InputImage.fromFilePath(context, imageUri)
                        val result = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
                            recognizer.process(image).addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                                .addOnFailureListener { if (cont.isActive) cont.cancel(it) }
                        }
                        scanned++
                        for (product in products) {
                            if (Matcher.matches(result.text, product)) {
                                found++
                                notifyMatch(context, product, Matcher.price(result.text), imageUri, result.text)
                            }
                        }
                        Store.markSeen(context, key)
                    } catch (_: Exception) { /* retry this image on next scan */ }
                }
            }
            Store.setStatus(context, "Scanned $scanned new images; matches $found. Latest 150 files considered.")
            Result.success()
        } catch (e: Exception) {
            Store.setStatus(context, "Scan failed: ${e.message}")
            Result.retry()
        } finally { recognizer.close() }
    }

    private fun notifyMatch(ctx: Context, product: String, price: String?, imageUri: Uri, text: String) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("matches", "Product matches", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pending = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val details = listOfNotNull(price, "Tap to open app").joinToString(" · ")
        val notification = NotificationCompat.Builder(ctx, "matches")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Found: $product")
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$details\nOCR excerpt: ${text.take(240)}"))
            .setContentIntent(pending).setAutoCancel(true).build()
        try { manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification) }
        catch (_: SecurityException) { }
    }
}
