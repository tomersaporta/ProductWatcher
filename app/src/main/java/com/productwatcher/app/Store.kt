package com.productwatcher.app

import android.content.Context
import org.json.JSONArray

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
    fun seen(ctx: Context, key: String): Boolean = prefs(ctx).getBoolean("seen_$key", false)
    fun markSeen(ctx: Context, key: String) = prefs(ctx).edit().putBoolean("seen_$key", true).apply()
    fun status(ctx: Context) = prefs(ctx).getString("status", "No scan yet") ?: "No scan yet"
    fun setStatus(ctx: Context, status: String) = prefs(ctx).edit().putString("status", status).apply()
    fun telegramStatus(ctx: Context) = prefs(ctx).getString("telegram_status", "No Telegram scan yet") ?: "No Telegram scan yet"
    fun setTelegramStatus(ctx: Context, status: String) = prefs(ctx).edit().putString("telegram_status", status).apply()
}
