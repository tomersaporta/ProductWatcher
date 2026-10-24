package com.productwatcher.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

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

    fun recordOffer(ctx: Context, offer: OfferMatch, chatId: Long, messageId: Long) {
        val description = "${offer.price ?: "price not identified"} · post $messageId · ${offer.source}"
        prefs(ctx).edit().putString(productPrefix(offer.product) + "last_offer", description).apply()
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
