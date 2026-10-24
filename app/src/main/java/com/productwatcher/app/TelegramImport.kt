package com.productwatcher.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Imports content explicitly shared by the user from Telegram. No Telegram account access. */
object TelegramImport {
    fun import(ctx: Context, intent: Intent): Int {
        val parts = mutableListOf<String>()
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let(parts::add)
        intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let(parts::add)
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
        }
        val raw = parts.joinToString("\n").trim()
        val keyMaterial = raw + uris.joinToString("|")
        if (keyMaterial.isBlank()) return 0
        val key = MessageDigest.getInstance("SHA-256").digest(keyMaterial.toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (Store.seen(ctx, "telegram_$key")) return 0
        val matches = Store.products(ctx).filter { Matcher.matches(raw, it) }
        val prefs = ctx.getSharedPreferences("product_watcher", Context.MODE_PRIVATE)
        val entries = runCatching { JSONArray(prefs.getString("shared_offers", "[]")) }.getOrDefault(JSONArray())
        val entry = JSONObject().put("date", System.currentTimeMillis())
            .put("text", raw.take(2000)).put("price", Matcher.price(raw) ?: "")
            .put("products", JSONArray(matches)).put("attachments", uris.size)
            .put("source", "Shared from Telegram / Android")
        entries.put(entry)
        // Keep latest 100 imports. Images remain in the source app; no persistent access is assumed.
        val bounded = JSONArray()
        for (i in maxOf(0, entries.length() - 100) until entries.length()) bounded.put(entries.get(i))
        prefs.edit().putString("shared_offers", bounded.toString()).apply()
        Store.markSeen(ctx, "telegram_$key")
        Store.setStatus(ctx, "Telegram share imported: ${matches.size} matching products")
        return matches.size
    }

    fun report(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("product_watcher", Context.MODE_PRIVATE)
        val entries = runCatching { JSONArray(prefs.getString("shared_offers", "[]")) }.getOrDefault(JSONArray())
        val matches = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.optJSONArray("products")?.length() ?: 0 > 0 }
        return "Shared posts: ${entries.length()} · Product matches: ${matches.size}" +
            matches.takeLast(5).joinToString("") { "\n• ${it.optJSONArray("products")?.optString(0)} ${it.optString("price")} — ${it.optString("text").take(120)}" }
    }
}
