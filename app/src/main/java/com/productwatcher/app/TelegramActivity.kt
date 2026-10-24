package com.productwatcher.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.drinkless.tdlib.TdApi

class TelegramActivity : Activity() {
    private val bg = Color.rgb(11, 18, 32)
    private val surface = Color.rgb(24, 36, 54)
    private val accent = Color.rgb(89, 222, 189)
    private val muted = Color.rgb(175, 190, 211)
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var scanStatus: TextView
    private lateinit var field: EditText
    private lateinit var chatList: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(s: String, size: Float = 16f, color: Int = Color.WHITE) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); setPadding(0, dp(8), 0, dp(8))
    }
    private fun button(s: String, f: () -> Unit) = TextView(this).apply {
        text = s; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; setTextColor(bg); setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(12).toFloat() }
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }
    private fun input(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        hint = hintText; inputType = type; setTextColor(Color.WHITE); setHintTextColor(muted)
        textSize = 15f; isSingleLine = true
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg; window.navigationBarColor = bg
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(32)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }; scroll.addView(root); setContentView(scroll)
        root.addView(label("TELEGRAM CONNECTION", 13f, accent))
        root.addView(label("Channel monitor", 27f))
        status = label("Starting", 16f); root.addView(status)
        scanStatus = label("No scan yet", 13f, muted); root.addView(scanStatus)
        val apiId = input("API ID (my.telegram.org)", InputType.TYPE_CLASS_NUMBER)
        val apiHash = input("API Hash", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        root.addView(apiId); root.addView(apiHash)
        if (getSharedPreferences("telegram_auth", MODE_PRIVATE).getInt("api_id", 0) > 0) {
            apiId.visibility = View.GONE
            apiHash.visibility = View.GONE
        }
        root.addView(button("CONNECT") {
            val id = apiId.text.toString().toIntOrNull()
            if (id == null || apiHash.text.isBlank()) Toast.makeText(this, "Enter API ID and hash", Toast.LENGTH_SHORT).show()
            else { TelegramClient.start(this, id, apiHash.text.toString()); apiHash.text.clear(); MonitorControl.start(this); refresh() }
        })
        field = input("Phone / code / 2FA password")
        root.addView(field)
        root.addView(button("SUBMIT LOGIN STEP") { TelegramClient.submit(field.text.toString()); field.text.clear() })
        root.addView(button("START BACKGROUND MONITORING") {
            try { MonitorControl.start(this); refresh() }
            catch (e: Exception) { Toast.makeText(this, "Could not start monitoring: ${e.message}", Toast.LENGTH_LONG).show() }
        })
        root.addView(button("STOP BACKGROUND MONITORING") { MonitorControl.stop(this); refresh() })
        root.addView(button("LOAD MY CHANNELS") { TelegramClient.loadChats(); showChats() })
        val selected = getSharedPreferences("telegram_source", MODE_PRIVATE).getString("chat_title", null)
        root.addView(label("Selected: ${selected ?: "No channel selected"}", 14f, muted))
        chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(chatList)
        TelegramClient.onUpdate = { runOnUiThread { refresh() } }
        TelegramClient.onChats = { runOnUiThread { showChats() } }
        TelegramClient.onHistory = { messages -> runOnUiThread { process(messages) } }
        TelegramOfferScanner.onStatus = { runOnUiThread { refresh() } }
        TelegramClient.resume(this)
        refresh()
    }
    private fun refresh() {
        status.text = "● ${TelegramClient.state}"
        status.setTextColor(if (TelegramClient.ready) accent else muted)
        scanStatus.text = (if (MonitorControl.enabled(this)) "Background monitor enabled" else "Background monitor stopped") + "\n" + Store.telegramStatus(this)
    }
    private fun showChats() {
        chatList.removeAllViews()
        val chats = TelegramClient.listChats().sortedBy { it.title }
        chatList.addView(label("CHANNELS (${chats.size}) · Select קונים באושר", 13f, accent))
        for (chat in chats) chatList.addView(button(chat.title) {
            getSharedPreferences("telegram_source", MODE_PRIVATE).edit().putLong("chat_id", chat.id).putString("chat_title", chat.title).apply()
            if (!MonitorControl.enabled(this)) MonitorControl.start(this)
            TelegramClient.readHistory(chat.id)
            status.text = "Automatic monitoring: ${chat.title}"
        })
    }
    private fun process(messages: List<TdApi.Message>) {
        scanStatus.text = "Received ${messages.size} posts. Downloading images and running OCR…"
    }
    override fun onDestroy() {
        TelegramClient.onUpdate = null; TelegramClient.onChats = null; TelegramClient.onHistory = null
        TelegramOfferScanner.onStatus = null
        super.onDestroy()
    }
}
