package com.productwatcher.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object LocalVisionSettings {
    const val MODEL_REPOSITORY = "google/gemma-3n-E2B-it-litert-lm"
    const val MODEL_VERSION = "Gemma 3n E2B INT4 · LiteRT-LM"
    private const val PREF = "local_vision"
    private const val MODEL_FILE = "gemma-3n-E2B-it-int4.litertlm"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("enabled", enabled).apply()
    fun modelFile(ctx: Context) = File(File(ctx.filesDir, "models").apply { mkdirs() }, MODEL_FILE)
    fun modelReady(ctx: Context) = modelFile(ctx).let { it.isFile && it.length() > 100_000_000L }
    fun ready(ctx: Context) = enabled(ctx) && modelReady(ctx)
    fun status(ctx: Context): String = when {
        modelReady(ctx) -> "Ready offline · ${formatBytes(modelFile(ctx).length())}"
        else -> prefs(ctx).getString("status", "Model not downloaded").orEmpty()
    }
    fun setStatus(ctx: Context, value: String) = prefs(ctx).edit().putString("status", value).apply()
    fun token(ctx: Context) = prefs(ctx).getString("hf_token", "").orEmpty()
    fun setToken(ctx: Context, token: String) = prefs(ctx).edit().putString("hf_token", token.trim()).apply()
    fun clearToken(ctx: Context) = prefs(ctx).edit().remove("hf_token").apply()
    fun removeModel(ctx: Context): Boolean {
        val removed = modelFile(ctx).delete()
        if (removed) setStatus(ctx, "Model removed")
        return removed
    }
    fun retentionDays(ctx: Context) = prefs(ctx).getInt("retention_days", 30).coerceIn(1, 180)
    fun setRetentionDays(ctx: Context, days: Int) = prefs(ctx).edit().putInt("retention_days", days.coerceIn(1, 180)).apply()
    private fun formatBytes(value: Long): String = "%.1f GB".format(value / 1_073_741_824.0)
}

data class LocalVisionJob(
    val id: String, val chatId: Long, val messageId: Long, val channel: String, val postUrl: String,
    val postedAt: Long, val detectedAt: Long, val caption: String, val ocrEvidence: String,
    val ocrLines: List<OcrLine>, val watched: List<String>, val sourceImagePath: String,
    val thumbnailPath: String, val imageIdentifier: String
)

object LocalVisionQueue {
    private fun jobs(ctx: Context) = File(ctx.filesDir, "local_vision_jobs").apply { mkdirs() }
    fun imageIdentifier(path: String): String? = runCatching {
        MessageDigest.getInstance("SHA-256").digest(File(path).readBytes()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun enqueue(ctx: Context, job: LocalVisionJob) {
        if (!LocalVisionSettings.ready(ctx)) return
        val file = File(jobs(ctx), "${job.id}.json")
        if (!file.exists()) file.writeText(toJson(job).toString())
        val request = OneTimeWorkRequestBuilder<LocalVisionWorker>()
            .setInputData(Data.Builder().putString("job_id", job.id).build())
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("local_vision_${job.id}", ExistingWorkPolicy.KEEP, request)
    }

    fun load(ctx: Context, id: String): LocalVisionJob? = runCatching {
        val o = JSONObject(File(jobs(ctx), "$id.json").readText())
        val lines = o.getJSONArray("ocrLines").let { a -> (0 until a.length()).map { i ->
            val l = a.getJSONObject(i); OcrLine(l.getString("text"), l.getInt("left"), l.getInt("top"), l.getInt("right"), l.getInt("bottom"))
        } }
        val watched = o.getJSONArray("watched").let { a -> (0 until a.length()).map { a.getString(it) } }
        LocalVisionJob(o.getString("id"), o.getLong("chatId"), o.getLong("messageId"), o.getString("channel"),
            o.optString("postUrl"), o.getLong("postedAt"), o.getLong("detectedAt"), o.optString("caption"),
            o.optString("ocrEvidence"), lines, watched, o.getString("sourceImagePath"), o.getString("thumbnailPath"),
            o.getString("imageIdentifier"))
    }.getOrNull()

    private fun toJson(job: LocalVisionJob) = JSONObject().apply {
        put("id", job.id); put("chatId", job.chatId); put("messageId", job.messageId); put("channel", job.channel)
        put("postUrl", job.postUrl); put("postedAt", job.postedAt); put("detectedAt", job.detectedAt)
        put("caption", job.caption); put("ocrEvidence", job.ocrEvidence); put("watched", JSONArray(job.watched))
        put("sourceImagePath", job.sourceImagePath); put("thumbnailPath", job.thumbnailPath); put("imageIdentifier", job.imageIdentifier)
        put("ocrLines", JSONArray(job.ocrLines.map { JSONObject().apply {
            put("text", it.text); put("left", it.left); put("top", it.top); put("right", it.right); put("bottom", it.bottom)
        } }))
    }
    fun markComplete(ctx: Context, id: String) { File(jobs(ctx), "$id.json").delete() }
    fun cleanup(ctx: Context): Int {
        val cutoff = System.currentTimeMillis() - LocalVisionSettings.retentionDays(ctx) * 86_400_000L
        val expired = Store.expireOfferImages(ctx, cutoff)
        val referenced = Store.foundOffers(ctx).flatMap { listOf(it.sourceImagePath, it.thumbnailPath) }.toSet()
        return expired + listOf("offer_sources", "offer_thumbnails").sumOf { folder ->
            File(ctx.filesDir, folder).listFiles()?.count { file -> file.lastModified() < cutoff && file.absolutePath !in referenced && file.delete() } ?: 0
        }
    }
}

class LocalModelDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = LocalVisionSettings.token(applicationContext)
        if (token.isBlank()) { LocalVisionSettings.setStatus(applicationContext, "Hugging Face token required"); return@withContext Result.failure() }
        setForeground(downloadForeground("Preparing model download", 0))
        try {
            val metadata = connection("https://huggingface.co/api/models/${LocalVisionSettings.MODEL_REPOSITORY}", token)
            val modelName = JSONObject(metadata.inputStream.bufferedReader().use { it.readText() }).getJSONArray("siblings").let { siblings ->
                (0 until siblings.length()).map { siblings.getJSONObject(it).getString("rfilename") }.single { it.endsWith(".litertlm") }
            }
            metadata.disconnect()
            val target = LocalVisionSettings.modelFile(applicationContext); val part = File(target.parentFile, target.name + ".part")
            val download = connection("https://huggingface.co/${LocalVisionSettings.MODEL_REPOSITORY}/resolve/main/$modelName", token,
                if (part.exists()) part.length() else 0L)
            val existing = if (download.responseCode == 206) part.length() else 0L
            if (existing == 0L && part.exists()) part.delete()
            val total = existing + download.contentLengthLong.coerceAtLeast(0L)
            FileOutputStream(part, existing > 0L).use { out ->
                download.inputStream.use { input ->
                    val buffer = ByteArray(1024 * 1024); var downloaded = existing; var lastProgress = -1
                    while (true) {
                        val read = input.read(buffer); if (read < 0) break
                        out.write(buffer, 0, read); downloaded += read
                        if (total > 0) {
                            val progress = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                            setProgress(Data.Builder().putInt("progress", progress).build())
                            setForeground(downloadForeground("Downloading Gemma 3n · $progress%", progress))
                            if (progress != lastProgress) { LocalVisionSettings.setStatus(applicationContext, "Downloading Gemma 3n · $progress%"); lastProgress = progress }
                        }
                    }
                }
            }
            download.disconnect()
            require(part.length() > 100_000_000L) { "Downloaded model is incomplete" }
            if (target.exists()) target.delete()
            require(part.renameTo(target)) { "Unable to finalize model file" }
            LocalVisionSettings.clearToken(applicationContext)
            LocalVisionSettings.setStatus(applicationContext, "Model ready for offline analysis")
            Result.success()
        } catch (e: Exception) {
            LocalVisionSettings.setStatus(applicationContext, "Model download failed: ${e.message}")
            if (runAttemptCount < 3 && e is java.io.IOException) Result.retry() else Result.failure()
        }
    }
    private fun connection(url: String, token: String, offset: Long = 0L): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("Authorization", "Bearer $token")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            if (responseCode !in 200..299) error("Download service returned HTTP $responseCode; accept the Gemma license and verify the token")
        }
    private fun downloadForeground(text: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("model_download", "On-device AI model", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "model_download")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("ProductWatcher local AI")
            .setContentText(text).setOnlyAlertOnce(true).setOngoing(progress < 100)
            .setProgress(100, progress, progress == 0).build()
        return ForegroundInfo(7701, notification)
    }
    companion object {
        fun enqueue(ctx: Context) {
            val work = OneTimeWorkRequestBuilder<LocalModelDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("gemma_3n_model_download", ExistingWorkPolicy.REPLACE, work)
        }
    }
}

private object LocalVisionEngine {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var modelPath: String? = null

    suspend fun analyze(ctx: Context, job: LocalVisionJob): String = mutex.withLock { withContext(Dispatchers.IO) {
        val active = ensureEngine(ctx)
        val prompt = """Return JSON only: {"matches":[{"exactModel":"...","priceIls":1234|null,"branch":"..."|null,"modelEvidence":"...","priceEvidence":"...","association":"same_tile|labeled_pair|nearby_only|unrelated|unknown","productRegion":{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}|null,"priceRegion":{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}|null}]}.
Analyze this retail collage conservatively. Watched exact variants: ${job.watched}. Pro, Max, Complete and base variants are distinct. A price is valid only when visibly in the same tile or explicitly paired with that exact product. Otherwise priceIls must be null. Reject accessory, installment, crossed-out and adjacent-column prices. Coordinates are normalized 0..1. Do not invent missing values.
Caption: ${job.caption.take(3000)}
OCR lines: ${job.ocrLines.take(100).joinToString { "${it.text}[${it.left},${it.top},${it.right},${it.bottom}]" }}"""
        active.createConversation(ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 0.1, temperature = 0.0), maxOutputToken = 1600
        )).use { conversation ->
            conversation.sendMessage(Contents.of(Content.ImageFile(job.sourceImagePath), Content.Text(prompt))).toString()
        }
    } }

    private fun ensureEngine(ctx: Context): Engine {
        val path = LocalVisionSettings.modelFile(ctx).absolutePath
        if (engine != null && modelPath == path) return engine!!
        engine?.close(); engine = null
        fun initialize(config: EngineConfig): Engine = Engine(config).also { candidate ->
            try { candidate.initialize() } catch (e: Throwable) { candidate.close(); throw e }
        }
        engine = try {
            initialize(EngineConfig(modelPath = path, backend = Backend.GPU(), visionBackend = Backend.GPU(),
                audioBackend = Backend.CPU(), cacheDir = ctx.cacheDir.absolutePath))
        } catch (_: Throwable) {
            initialize(EngineConfig(modelPath = path, backend = Backend.CPU(), visionBackend = Backend.CPU(),
                audioBackend = Backend.CPU(), cacheDir = ctx.cacheDir.absolutePath))
        }
        modelPath = path
        return engine!!
    }
}

class LocalVisionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("job_id") ?: return Result.failure()
        val job = LocalVisionQueue.load(applicationContext, id) ?: return Result.success()
        if (!LocalVisionSettings.modelReady(applicationContext)) return Result.retry()
        return try {
            val raw = LocalVisionEngine.analyze(applicationContext, job)
            val parsed = parseMatches(raw).mapNotNull { VisionResultValidator.validate(it, job.watched) }
            val updated = Store.applyVisionResults(applicationContext, job, parsed, LocalVisionSettings.MODEL_VERSION, System.currentTimeMillis())
            updated.forEach { TelegramOfferScanner.notifyVisionResult(applicationContext, it) }
            LocalVisionQueue.markComplete(applicationContext, id)
            Result.success()
        } catch (e: Throwable) {
            if (runAttemptCount < 2) Result.retry() else {
                Store.markVisionFailed(applicationContext, job.chatId, job.messageId, "Local image analysis failed: ${e.message}")
                LocalVisionQueue.markComplete(applicationContext, id); Result.failure()
            }
        }
    }
    private fun parseMatches(raw: String): List<VisionMatch> {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "Model did not return JSON" }
        val matches = JSONObject(raw.substring(start, end + 1)).getJSONArray("matches")
        return (0 until matches.length()).map { i ->
            val o = matches.getJSONObject(i)
            VisionMatch(o.optString("exactModel"), if (o.isNull("priceIls")) null else o.optInt("priceIls"),
                o.optString("branch").takeIf { it.isNotBlank() }, o.optString("modelEvidence"), o.optString("priceEvidence"),
                o.optString("association"), region(o.optJSONObject("productRegion")), region(o.optJSONObject("priceRegion")))
        }
    }
    private fun region(o: JSONObject?): VisionRegion? = o?.let {
        VisionRegion(it.optDouble("left"), it.optDouble("top"), it.optDouble("right"), it.optDouble("bottom"))
    }
}
