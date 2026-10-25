package com.productwatcher.app

import android.content.Context
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object CloudVisionSettings {
    private const val PREF = "cloud_vision"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun endpoint(ctx: Context) = prefs(ctx).getString("endpoint", "").orEmpty().trim().trimEnd('/')
    fun token(ctx: Context) = prefs(ctx).getString("proxy_token", "").orEmpty()
    fun configure(ctx: Context, enabled: Boolean, endpoint: String, token: String) {
        prefs(ctx).edit().putBoolean("enabled", enabled)
            .putString("endpoint", endpoint.trim()).putString("proxy_token", token.trim()).apply()
    }
    fun ready(ctx: Context) = enabled(ctx) && endpoint(ctx).startsWith("https://") && token(ctx).isNotBlank()
    fun retentionDays(ctx: Context) = prefs(ctx).getInt("retention_days", 30).coerceIn(1, 180)
    fun setRetentionDays(ctx: Context, days: Int) = prefs(ctx).edit().putInt("retention_days", days.coerceIn(1, 180)).apply()
    fun promptVersion(ctx: Context) = prefs(ctx).getString("prompt_version", "vision-v1").orEmpty()
    fun dailyRequests(ctx: Context): Int = prefs(ctx).getInt("requests_${LocalDate.now()}", 0)
    fun dailyCost(ctx: Context): Double = prefs(ctx).getString("cost_${LocalDate.now()}", "0")?.toDoubleOrNull() ?: 0.0
    fun recordUsage(ctx: Context, estimatedCost: Double) {
        val p = prefs(ctx); val day = LocalDate.now().toString()
        p.edit().putInt("requests_$day", p.getInt("requests_$day", 0) + 1)
            .putString("cost_$day", (dailyCost(ctx) + estimatedCost.coerceAtLeast(0.0)).toString()).apply()
    }
}

data class VisionJob(
    val id: String,
    val chatId: Long,
    val messageId: Long,
    val channel: String,
    val postUrl: String,
    val postedAt: Long,
    val detectedAt: Long,
    val caption: String,
    val ocrEvidence: String,
    val ocrLines: List<OcrLine>,
    val watched: List<String>,
    val sourceImagePath: String,
    val thumbnailPath: String,
    val imageIdentifier: String,
    val promptVersion: String
)

object CloudVisionQueue {
    private const val MAX_UPLOAD_BYTES = 6 * 1024 * 1024
    private fun jobs(ctx: Context) = File(ctx.filesDir, "vision_jobs").apply { mkdirs() }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun imageIdentifier(path: String): String? = runCatching { sha256(File(path).readBytes()) }.getOrNull()

    fun enqueue(ctx: Context, job: VisionJob) {
        if (!CloudVisionSettings.ready(ctx)) return
        val file = File(jobs(ctx), "${job.id}.json")
        if (!file.exists()) file.writeText(toJson(job).toString())
        val request = OneTimeWorkRequestBuilder<CloudVisionWorker>()
            .setInputData(Data.Builder().putString("job_id", job.id).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("vision_${job.id}", ExistingWorkPolicy.KEEP, request)
    }

    fun load(ctx: Context, id: String): VisionJob? = runCatching {
        val o = JSONObject(File(jobs(ctx), "$id.json").readText())
        val lines = o.getJSONArray("ocrLines").let { a -> (0 until a.length()).map { i ->
            val l = a.getJSONObject(i); OcrLine(l.getString("text"), l.getInt("left"), l.getInt("top"), l.getInt("right"), l.getInt("bottom"))
        } }
        val watched = o.getJSONArray("watched").let { a -> (0 until a.length()).map { a.getString(it) } }
        VisionJob(o.getString("id"), o.getLong("chatId"), o.getLong("messageId"), o.getString("channel"),
            o.optString("postUrl"), o.getLong("postedAt"), o.getLong("detectedAt"), o.optString("caption"),
            o.optString("ocrEvidence"), lines, watched, o.getString("sourceImagePath"), o.getString("thumbnailPath"),
            o.getString("imageIdentifier"), o.getString("promptVersion"))
    }.getOrNull()

    private fun toJson(job: VisionJob) = JSONObject().apply {
        put("id", job.id); put("chatId", job.chatId); put("messageId", job.messageId); put("channel", job.channel)
        put("postUrl", job.postUrl); put("postedAt", job.postedAt); put("detectedAt", job.detectedAt)
        put("caption", job.caption); put("ocrEvidence", job.ocrEvidence); put("watched", JSONArray(job.watched))
        put("sourceImagePath", job.sourceImagePath); put("thumbnailPath", job.thumbnailPath)
        put("imageIdentifier", job.imageIdentifier); put("promptVersion", job.promptVersion)
        put("ocrLines", JSONArray(job.ocrLines.map { JSONObject().apply {
            put("text", it.text); put("left", it.left); put("top", it.top); put("right", it.right); put("bottom", it.bottom)
        } }))
    }

    fun requestBody(job: VisionJob): JSONObject {
        val image = File(job.sourceImagePath)
        require(image.isFile && image.length() in 1..MAX_UPLOAD_BYTES) { "Source image is missing or exceeds 6 MB" }
        return JSONObject().apply {
            put("chatId", job.chatId); put("messageId", job.messageId); put("channel", job.channel)
            put("postUrl", job.postUrl); put("postedAt", job.postedAt); put("detectedAt", job.detectedAt)
            put("caption", job.caption); put("ocrEvidence", job.ocrEvidence)
            put("ocrLines", toJson(job).getJSONArray("ocrLines")); put("watchedProducts", JSONArray(job.watched))
            put("imageIdentifier", job.imageIdentifier); put("promptVersion", job.promptVersion)
            put("imageBase64", Base64.encodeToString(image.readBytes(), Base64.NO_WRAP))
            put("mimeType", "image/jpeg")
        }
    }

    fun markComplete(ctx: Context, id: String) { File(jobs(ctx), "$id.json").delete() }

    fun cleanup(ctx: Context): Int {
        val cutoff = System.currentTimeMillis() - CloudVisionSettings.retentionDays(ctx) * 86_400_000L
        val expired = Store.expireOfferImages(ctx, cutoff)
        val referenced = Store.foundOffers(ctx).flatMap { listOf(it.sourceImagePath, it.thumbnailPath) }.toSet()
        return expired + listOf("offer_sources", "offer_thumbnails").sumOf { folder ->
            File(ctx.filesDir, folder).listFiles()?.count { file ->
                file.lastModified() < cutoff && file.absolutePath !in referenced && file.delete()
            } ?: 0
        }
    }
}

class CloudVisionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("job_id") ?: return@withContext Result.failure()
        val job = CloudVisionQueue.load(applicationContext, id) ?: return@withContext Result.success()
        if (runAttemptCount >= 4) {
            Store.markVisionFailed(applicationContext, job.chatId, job.messageId, "Cloud analysis retry limit reached")
            CloudVisionQueue.markComplete(applicationContext, id)
            return@withContext Result.failure()
        }
        if (!CloudVisionSettings.ready(applicationContext)) return@withContext Result.retry()
        try {
            val endpoint = CloudVisionSettings.endpoint(applicationContext) + "/analyze"
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"; connection.connectTimeout = 20_000; connection.readTimeout = 60_000
            connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${CloudVisionSettings.token(applicationContext)}")
            connection.outputStream.use { it.write(CloudVisionQueue.requestBody(job).toString().toByteArray()) }
            val code = connection.responseCode
            if (code == 429 || code >= 500) return@withContext Result.retry()
            if (code !in 200..299) {
                Store.markVisionFailed(applicationContext, job.chatId, job.messageId, "Cloud analysis rejected ($code)")
                CloudVisionQueue.markComplete(applicationContext, id)
                return@withContext Result.failure()
            }
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val modelVersion = response.optString("modelVersion", "backend-configured")
            val analyzedAt = response.optLong("analysisTimestamp", System.currentTimeMillis())
            val matches = response.optJSONArray("matches") ?: JSONArray()
            val validated = (0 until matches.length()).mapNotNull { i ->
                VisionResultValidator.validate(parseMatch(matches.getJSONObject(i)), job.watched)
            }
            val updated = Store.applyVisionResults(applicationContext, job, validated, modelVersion, analyzedAt)
            updated.forEach { TelegramOfferScanner.notifyCloudResult(applicationContext, it) }
            CloudVisionSettings.recordUsage(applicationContext, response.optDouble("estimatedCostUsd", 0.0))
            CloudVisionQueue.markComplete(applicationContext, id)
            Result.success()
        } catch (_: java.net.SocketTimeoutException) { Result.retry() }
        catch (_: java.io.IOException) { Result.retry() }
        catch (e: Exception) {
            Store.markVisionFailed(applicationContext, job.chatId, job.messageId, e.message ?: "Invalid cloud response")
            CloudVisionQueue.markComplete(applicationContext, id)
            Result.failure()
        }
    }

    private fun parseMatch(o: JSONObject): VisionMatch = VisionMatch(
        exactModel = o.optString("exactModel"),
        priceIls = if (o.isNull("priceIls")) null else o.optInt("priceIls"),
        branch = o.optString("branch").takeIf { it.isNotBlank() },
        modelEvidence = o.optString("modelEvidence"), priceEvidence = o.optString("priceEvidence"),
        association = o.optString("association"),
        productRegion = region(o.optJSONObject("productRegion")), priceRegion = region(o.optJSONObject("priceRegion"))
    )
    private fun region(o: JSONObject?): VisionRegion? = o?.let {
        VisionRegion(it.optDouble("left"), it.optDouble("top"), it.optDouble("right"), it.optDouble("bottom"))
    }
}
