package com.productwatcher.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

data class FoundOffer(
    val id: String,
    val product: String,
    val price: String,
    val branch: String,
    val channel: String,
    val chatId: Long,
    val messageId: Long,
    val postTime: Long,
    val detectedTime: Long,
    val sourceDate: String,
    val evidence: String,
    val source: String,
    val confidence: String,
    val confidenceScore: Double,
    val reviewState: String,
    val associationReason: String,
    val branchSource: String,
    val verified: Boolean,
    val messageLink: String,
    val photoPath: String,
    val exactModel: String,
    val priceIls: Int?,
    val caption: String,
    val visionEvidence: String,
    val priceRegion: String,
    val productRegion: String,
    val sourceImagePath: String,
    val thumbnailPath: String,
    val imageIdentifier: String,
    val modelVersion: String,
    val analysisTimestamp: Long,
    val auditTrail: String
)

data class FoundOfferSave(val offer: FoundOffer, val inserted: Boolean)

object Store {
    private const val PREF = "product_watcher"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun folder(ctx: Context): String? = prefs(ctx).getString("folder", null)
    fun setFolder(ctx: Context, uri: String) = prefs(ctx).edit().putString("folder", uri).apply()
    fun products(ctx: Context): List<String> {
        val raw = prefs(ctx).getString("products", null) ?: return listOf("Dreame X60 Pro Ultra")
        return runCatching { val arr = JSONArray(raw); (0 until arr.length()).map { arr.getString(it) } }.getOrDefault(emptyList())
    }
    fun setProducts(ctx: Context, items: List<String>) = prefs(ctx).edit().putString("products", JSONArray(items).toString()).apply()
    fun deleteTelegramImages(ctx: Context): Boolean = prefs(ctx).getBoolean("delete_telegram_images", true)
    fun setDeleteTelegramImages(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("delete_telegram_images", enabled).apply()
    fun scanGeneration(ctx: Context): Long = prefs(ctx).getLong("scan_generation", 0L)
    @Synchronized fun requestVisionRescan(ctx: Context, maxPosts: Int = 50) {
        prefs(ctx).edit().putInt("vision_rescan_budget", maxPosts.coerceIn(1, 200)).apply()
        requestFullRescan(ctx)
        setTelegramStatus(ctx, "Bounded cloud-image rescan requested · up to ${maxPosts.coerceIn(1, 200)} candidate posts")
    }
    @Synchronized fun visionCandidates(ctx: Context, automatic: List<String>): List<String> {
        val p = prefs(ctx); val remaining = p.getInt("vision_rescan_budget", 0)
        if (remaining <= 0) return automatic
        p.edit().putInt("vision_rescan_budget", remaining - 1).apply()
        return products(ctx)
    }
    /** One-time, non-destructive backfill for the current structured offer schema. */
    @Synchronized fun ensureStructuredOfferBackfill(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("found_offers_v2_enriched", false)) return
        p.edit().putBoolean("found_offers_v1_initialized", true)
            .putBoolean("found_offers_v2_enriched", true).apply()
        requestFullRescan(ctx)
    }
    /** A new product requires historical re-evaluation, without clearing past alert deduplication. */
    @Synchronized fun requestFullRescan(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putLong("scan_generation", scanGeneration(ctx) + 1L).apply()
        val source = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE)
        val edit = source.edit()
        monitoredChannels(ctx).forEach { (id, _) ->
            edit.putBoolean("history_pending_$id", true)
                .putLong("history_cursor_$id", 0L)
                .putLong("history_checked_$id", 0L)
                .remove("catchup_cursor_$id")
                .remove("catchup_target_$id")
        }
        edit.apply()
        setTelegramStatus(ctx, "Watchlist changed · historical Telegram rescan requested")
    }
    fun seen(ctx: Context, key: String): Boolean = prefs(ctx).getBoolean("seen_$key", false)
    fun markSeen(ctx: Context, key: String) = prefs(ctx).edit().putBoolean("seen_$key", true).apply()
    fun status(ctx: Context) = prefs(ctx).getString("status", "No scan yet") ?: "No scan yet"
    fun setStatus(ctx: Context, status: String) = prefs(ctx).edit().putString("status", status).apply()
    fun telegramStatus(ctx: Context) = prefs(ctx).getString("telegram_status", "No Telegram scan yet") ?: "No Telegram scan yet"
    fun setTelegramStatus(ctx: Context, status: String) = prefs(ctx).edit().putString("telegram_status", status).apply()
    /** Durable, independent counters for each tracked product. */
    private fun productPrefix(product: String): String = "product_" + product.trim().lowercase().hashCode() + "_"
    @Synchronized fun productEvent(ctx: Context, product: String, state: String, checked: Boolean = false, matched: Boolean = false, alerted: Boolean = false) {
        val p = prefs(ctx)
        val prefix = productPrefix(product)
        val edit = p.edit().putString(prefix + "state", state).putLong(prefix + "last", System.currentTimeMillis())
        if (checked) edit.putLong(prefix + "checked", p.getLong(prefix + "checked", 0L) + 1L)
        if (matched) edit.putLong(prefix + "matches", p.getLong(prefix + "matches", 0L) + 1L)
        if (alerted) edit.putLong(prefix + "alerts", p.getLong(prefix + "alerts", 0L) + 1L)
        edit.apply()
    }
    fun productReport(ctx: Context, product: String): String {
        val p = prefs(ctx)
        val prefix = productPrefix(product)
        val time = p.getLong(prefix + "last", 0L)
        val last = if (time == 0L) "Not checked yet" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))
        val lastOffer = p.getString(prefix + "last_offer", null)
        return "Last activity: $last\n" +
            "Posts checked: ${p.getLong(prefix + "checked", 0L)} · Matches: ${p.getLong(prefix + "matches", 0L)} · Alerts: ${p.getLong(prefix + "alerts", 0L)}\n" +
            "Status: ${p.getString(prefix + "state", "Waiting for Telegram scan")}" +
            (if (lastOffer != null) "\nLast match: $lastOffer" else "")
    }
    /** Per-channel telemetry; separate from the selected channel and image-folder legacy state. */
    @Synchronized fun recordChannel(ctx: Context, chatId: Long, title: String) {
        val p = prefs(ctx)
        val channels = runCatching { JSONObject(p.getString("monitored_channels", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val firstAdded = !channels.has(chatId.toString())
        channels.put(chatId.toString(), title)
        p.edit().putString("monitored_channels", channels.toString()).apply()
        if (firstAdded) {
            ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).edit()
                .putBoolean("history_pending_$chatId", true)
                .putLong("history_cursor_$chatId", 0L).apply()
        }
    }
    fun monitoredChannels(ctx: Context): List<Pair<Long, String>> {
        val p = prefs(ctx)
        val channels = runCatching { JSONObject(p.getString("monitored_channels", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val result = mutableListOf<Pair<Long, String>>()
        val keys = channels.keys()
        while (keys.hasNext()) { val key = keys.next(); key.toLongOrNull()?.let { result.add(it to channels.optString(key)) } }
        // Migrate the previously selected Telegram channel.
        val legacyId = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).getLong("chat_id", 0L)
        val legacyTitle = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).getString("chat_title", "Telegram channel") ?: "Telegram channel"
        if (legacyId != 0L && result.none { it.first == legacyId }) {
            recordChannel(ctx, legacyId, legacyTitle)
            result.add(legacyId to legacyTitle)
        }
        return result
    }
    @Synchronized fun channelEvent(ctx: Context, chatId: Long, event: String, increment: String? = null) {
        val p = prefs(ctx)
        val prefix = "channel_${chatId}_"
        val editor = p.edit().putString(prefix + "status", event).putLong(prefix + "last_run", System.currentTimeMillis())
        if (increment != null) editor.putLong(prefix + increment, p.getLong(prefix + increment, 0L) + 1)
        editor.apply()
    }
    @Synchronized fun failedMessages(ctx: Context, chatId: Long): List<Long> {
        val raw = prefs(ctx).getString("retry_messages_$chatId", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getLong(it) }
        }.getOrDefault(emptyList())
    }
    @Synchronized fun recordMessageOutcome(ctx: Context, chatId: Long, messageId: Long, success: Boolean) {
        val old = failedMessages(ctx, chatId).toMutableList()
        old.remove(messageId)
        if (!success) old.add(messageId)
        prefs(ctx).edit().putString("retry_messages_$chatId", JSONArray(old.takeLast(150)).toString()).apply()
    }

    /** OCR diagnostics contain only the last recognized excerpt and stay on device. */
    fun recordOcrDiagnostic(ctx: Context, chatId: Long, messageId: Long, text: String) {
        val compact = text.replace('\n', ' ')
        val found = compact.indexOf("x60", ignoreCase = true)
        val start = if (found >= 0) (found - 50).coerceAtLeast(0) else 0
        prefs(ctx).edit().putString("channel_${chatId}_last_ocr",
            "Post $messageId: " + compact.drop(start).take(260)).apply()
    }

    private fun offerJson(offer: FoundOffer) = JSONObject().apply {
        put("id", offer.id); put("product", offer.product); put("price", offer.price)
        put("branch", offer.branch); put("channel", offer.channel); put("chat_id", offer.chatId)
        put("message_id", offer.messageId); put("post_time", offer.postTime)
        put("detected_time", offer.detectedTime); put("source_date", offer.sourceDate)
        put("evidence", offer.evidence)
        put("source", offer.source); put("confidence", offer.confidence)
        put("confidence_score", offer.confidenceScore); put("review_state", offer.reviewState)
        put("association_reason", offer.associationReason); put("branch_source", offer.branchSource)
        put("verified", offer.verified); put("message_link", offer.messageLink); put("photo_path", offer.photoPath)
        put("exact_model", offer.exactModel); put("price_ils", offer.priceIls ?: JSONObject.NULL)
        put("caption", offer.caption); put("vision_evidence", offer.visionEvidence)
        put("price_region", offer.priceRegion); put("product_region", offer.productRegion)
        put("source_image_path", offer.sourceImagePath); put("thumbnail_path", offer.thumbnailPath)
        put("image_identifier", offer.imageIdentifier); put("model_version", offer.modelVersion)
        put("analysis_timestamp", offer.analysisTimestamp); put("audit_trail", offer.auditTrail)
    }

    private fun parseOffer(obj: JSONObject): FoundOffer? = runCatching {
        FoundOffer(
            id = obj.getString("id"), product = obj.getString("product"),
            price = obj.optString("price", "Price not identified"),
            branch = obj.optString("branch", "Branch not identified"),
            channel = obj.optString("channel", "Telegram channel"),
            chatId = obj.getLong("chat_id"), messageId = obj.getLong("message_id"),
            postTime = obj.optLong("post_time", 0L), detectedTime = obj.optLong("detected_time", 0L),
            sourceDate = obj.optString("source_date", "Unknown"),
            evidence = obj.optString("evidence", "No OCR evidence saved"),
            source = obj.optString("source", "OCR"),
            confidence = obj.optString("confidence", "OCR match"),
            confidenceScore = obj.optDouble("confidence_score", 0.0),
            reviewState = obj.optString("review_state", "REVIEW_NEEDED"),
            associationReason = obj.optString("association_reason", "Legacy match requires review"),
            branchSource = obj.optString("branch_source", "Unknown"),
            // Historical automated detections must never silently become human/store verified.
            verified = false,
            messageLink = obj.optString("message_link", ""),
            photoPath = obj.optString("photo_path", ""),
            exactModel = obj.optString("exact_model", obj.optString("product")),
            priceIls = if (obj.isNull("price_ils") || !obj.has("price_ils")) null else obj.optInt("price_ils"),
            caption = obj.optString("caption", ""), visionEvidence = obj.optString("vision_evidence", ""),
            priceRegion = obj.optString("price_region", ""), productRegion = obj.optString("product_region", ""),
            sourceImagePath = obj.optString("source_image_path", ""),
            thumbnailPath = obj.optString("thumbnail_path", obj.optString("photo_path", "")),
            imageIdentifier = obj.optString("image_identifier", ""), modelVersion = obj.optString("model_version", ""),
            analysisTimestamp = obj.optLong("analysis_timestamp", 0L),
            auditTrail = obj.optString("audit_trail", "[]")
        )
    }.getOrNull()

    /** Historical structured matches. Existing entries are updated, never duplicated or discarded. */
    @Synchronized fun foundOffers(ctx: Context): List<FoundOffer> {
        val raw = prefs(ctx).getString("found_offers_v1", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { parseOffer(arr.getJSONObject(it)) }
                .sortedByDescending { it.detectedTime }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun recordOffer(
        ctx: Context,
        offer: OfferMatch,
        chatId: Long,
        messageId: Long,
        postTime: Long,
        metadata: SourceMetadata,
        channel: String,
        sourceImagePath: String?,
        thumbnailPath: String?,
        imageIdentifier: String?,
        caption: String
    ): FoundOfferSave {
        val id = OfferIdentity.key(chatId, messageId, offer.product)
        val items = foundOffers(ctx).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        val previous = items.getOrNull(index)
        val evidence = offer.evidence.ifBlank { previous?.evidence ?: "Model name recognized in Telegram post" }
        val record = FoundOffer(
            id = id,
            product = offer.product,
            // A conservative rescan is authoritative: a formerly guessed price becomes unknown.
            price = offer.price ?: "Price unknown · review required",
            branch = metadata.branch
                ?: previous?.branch ?: "Branch not identified",
            channel = channel.ifBlank { previous?.channel ?: "Telegram channel" },
            chatId = chatId,
            messageId = messageId,
            postTime = postTime,
            detectedTime = previous?.detectedTime ?: System.currentTimeMillis(),
            sourceDate = metadata.sourceDate ?: previous?.sourceDate ?: "Unknown",
            evidence = evidence.take(260),
            source = offer.source,
            confidence = offer.confidence,
            confidenceScore = offer.confidenceScore,
            reviewState = offer.reviewState,
            associationReason = offer.associationReason,
            branchSource = metadata.branchSource ?: previous?.branchSource ?: "Unknown",
            verified = false,
            messageLink = previous?.messageLink.orEmpty(),
            photoPath = thumbnailPath ?: previous?.photoPath.orEmpty(),
            exactModel = previous?.exactModel ?: offer.product,
            priceIls = offer.price?.let { OfferExtractor.prices(it).singleOrNull()?.toIntOrNull() },
            caption = caption.take(1000), visionEvidence = previous?.visionEvidence.orEmpty(),
            priceRegion = previous?.priceRegion.orEmpty(), productRegion = previous?.productRegion.orEmpty(),
            sourceImagePath = sourceImagePath ?: previous?.sourceImagePath.orEmpty(),
            thumbnailPath = thumbnailPath ?: previous?.thumbnailPath.orEmpty(),
            imageIdentifier = imageIdentifier ?: previous?.imageIdentifier.orEmpty(),
            modelVersion = previous?.modelVersion.orEmpty(), analysisTimestamp = previous?.analysisTimestamp ?: 0L,
            auditTrail = appendAudit(previous?.auditTrail, "LOCAL_OCR", offer.price, offer.associationReason)
        )
        if (index >= 0) items[index] = record else items.add(record)
        val arr = JSONArray(); items.forEach { arr.put(offerJson(it)) }
        val description = "${record.price} · ${record.channel} · post $messageId · ${record.source}"
        prefs(ctx).edit()
            .putString("found_offers_v1", arr.toString())
            .putString(productPrefix(offer.product) + "last_offer", description)
            .apply()
        return FoundOfferSave(record, index < 0)
    }

    private fun appendAudit(existing: String?, status: String, price: String?, reason: String): String {
        val arr = runCatching { JSONArray(existing ?: "[]") }.getOrDefault(JSONArray())
        arr.put(JSONObject().apply {
            put("at", System.currentTimeMillis()); put("status", status); put("price", price ?: JSONObject.NULL)
            put("reason", reason.take(240))
        })
        val compact = JSONArray()
        for (i in maxOf(0, arr.length() - 20) until arr.length()) compact.put(arr.get(i))
        return compact.toString()
    }

    @Synchronized fun applyVisionResults(
        ctx: Context, job: VisionJob, results: List<ValidatedVisionMatch>, modelVersion: String, analyzedAt: Long
    ): List<FoundOffer> {
        val items = foundOffers(ctx).toMutableList()
        val updated = mutableListOf<FoundOffer>()
        results.forEach { result ->
            val id = OfferIdentity.key(job.chatId, job.messageId, result.product)
            val index = items.indexOfFirst { it.id == id }
            val previous = items.getOrNull(index) ?: return@forEach
            val priceText = result.priceIls?.let { "₪$it (image-supported)" } ?: "Price unknown · review required"
            val next = previous.copy(
                price = priceText, priceIls = result.priceIls, exactModel = result.exactModel,
                branch = previous.branch.takeUnless { it == "Branch not identified" } ?: result.branch ?: previous.branch,
                evidence = previous.evidence, visionEvidence = result.evidence,
                source = "local OCR + cloud image analysis", confidence = "${(result.confidence * 100).toInt()}% · ${result.reason}",
                confidenceScore = result.confidence, reviewState = result.verificationStatus,
                associationReason = result.reason, verified = false,
                priceRegion = result.priceRegion, productRegion = result.productRegion,
                modelVersion = modelVersion, analysisTimestamp = analyzedAt,
                auditTrail = appendAudit(previous.auditTrail, result.verificationStatus, priceText, result.reason)
            )
            items[index] = next; updated.add(next)
        }
        val matchedIds = results.map { OfferIdentity.key(job.chatId, job.messageId, it.product) }.toSet()
        items.indices.filter { items[it].chatId == job.chatId && items[it].messageId == job.messageId && items[it].id !in matchedIds && items[it].reviewState == "PENDING_ANALYSIS" }
            .forEach { i -> items[i] = items[i].copy(reviewState = "UNCERTAIN", associationReason = "Cloud analysis did not confirm the watched model",
                auditTrail = appendAudit(items[i].auditTrail, "UNCERTAIN", null, "Cloud analysis did not confirm the watched model")) }
        val arr = JSONArray(); items.forEach { arr.put(offerJson(it)) }
        prefs(ctx).edit().putString("found_offers_v1", arr.toString()).apply()
        return updated
    }

    @Synchronized fun markVisionFailed(ctx: Context, chatId: Long, messageId: Long, reason: String) {
        val items = foundOffers(ctx).toMutableList()
        items.indices.filter { items[it].chatId == chatId && items[it].messageId == messageId && items[it].reviewState == "PENDING_ANALYSIS" }
            .forEach { i -> items[i] = items[i].copy(reviewState = "ANALYSIS_FAILED", associationReason = reason,
                auditTrail = appendAudit(items[i].auditTrail, "ANALYSIS_FAILED", null, reason)) }
        val arr = JSONArray(); items.forEach { arr.put(offerJson(it)) }
        prefs(ctx).edit().putString("found_offers_v1", arr.toString()).apply()
    }

    @Synchronized fun expireOfferImages(ctx: Context, cutoff: Long): Int {
        val items = foundOffers(ctx).toMutableList(); var removed = 0
        items.indices.filter { items[it].detectedTime < cutoff }.forEach { i ->
            val offer = items[i]
            listOf(offer.sourceImagePath, offer.thumbnailPath).filter { it.isNotBlank() }.distinct().forEach { path ->
                if (java.io.File(path).delete()) removed++
            }
            items[i] = offer.copy(sourceImagePath = "", thumbnailPath = "", photoPath = "")
        }
        val arr = JSONArray(); items.forEach { arr.put(offerJson(it)) }
        prefs(ctx).edit().putString("found_offers_v1", arr.toString()).apply()
        return removed
    }

    @Synchronized fun updateOfferLink(ctx: Context, id: String, link: String): FoundOffer? {
        val safeLink = MessageLinkPolicy.normalize(link)
            ?: return foundOffers(ctx).firstOrNull { it.id == id }
        val items = foundOffers(ctx).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        items[index] = items[index].copy(messageLink = safeLink)
        val arr = JSONArray(); items.forEach { arr.put(offerJson(it)) }
        prefs(ctx).edit().putString("found_offers_v1", arr.toString()).apply()
        return items[index]
    }

    fun historyProgress(ctx: Context, chatId: Long): String {
        val source = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE)
        val count = source.getLong("history_checked_$chatId", 0L)
        val gap = if (source.contains("catchup_cursor_$chatId"))
            "\nMissed-post catch-up running (after background suspension)" else ""
        return (if (source.getBoolean("history_pending_$chatId", false))
            "Historical rescan in progress · $count posts visited; resumes automatically"
        else if (source.contains("history_checked_$chatId"))
            "Historical rescan finished · $count posts visited"
        else "Historical scan not started") + gap
    }

    fun channelReport(ctx: Context, chatId: Long): String {
        val p = prefs(ctx)
        val prefix = "channel_${chatId}_"
        val time = p.getLong(prefix + "last_run", 0L)
        val last = if (time == 0L) "Never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))
        return "Last activity: $last\n" +
            "Posts: ${p.getLong(prefix + "posts", 0)} · Images: ${p.getLong(prefix + "images", 0)} · " +
            "OCR: ${p.getLong(prefix + "ocr", 0)} · Matches: ${p.getLong(prefix + "matches", 0)} · " +
            "Alerts: ${p.getLong(prefix + "alerts", 0)}\n" +
            "Status: ${p.getString(prefix + "status", "Waiting for first scan")}\n" +
            historyProgress(ctx, chatId) + "\n" +
            "Last OCR: ${p.getString(prefix + "last_ocr", "No recognized text yet")}"
    }
}
