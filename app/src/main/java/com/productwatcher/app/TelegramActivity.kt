package com.productwatcher.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import org.drinkless.tdlib.TdApi

class TelegramActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var field: EditText
    private lateinit var chatList: LinearLayout
    private fun label(s: String) = TextView(this).apply { text = s; textSize = 17f; setPadding(8, 12, 8, 12) }
    private fun button(s: String, f: () -> Unit) = Button(this).apply { text = s; setOnClickListener { f() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)
        root.addView(label("Telegram direct connection (TDLib)"))
        status = label("Starting"); root.addView(status)
        val apiId = EditText(this).apply { hint = "API ID (from my.telegram.org)"; inputType = InputType.TYPE_CLASS_NUMBER }
        val apiHash = EditText(this).apply { hint = "API Hash"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(apiId); root.addView(apiHash)
        root.addView(button("Connect") {
            val id = apiId.text.toString().toIntOrNull()
            if (id == null || apiHash.text.isBlank()) Toast.makeText(this, "Enter API ID and hash", Toast.LENGTH_SHORT).show()
            else { TelegramClient.start(this, id, apiHash.text.toString()); apiHash.text.clear(); refresh() }
        })
        field = EditText(this).apply { hint = "Phone / code / password (depending on status)" }
        root.addView(field)
        root.addView(button("Submit login step") { TelegramClient.submit(field.text.toString()); field.text.clear() })
        root.addView(button("Load my channels") { TelegramClient.loadChats(); showChats() })
        chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(chatList)
        TelegramClient.onUpdate = { runOnUiThread { refresh() } }
        TelegramClient.onChats = { runOnUiThread { showChats() } }
        TelegramClient.onHistory = { messages -> runOnUiThread { process(messages) } }
        TelegramClient.resume(this)
        refresh()
    }
    private fun refresh() { status.text = "Status: ${TelegramClient.state}"; if (TelegramClient.ready) showChats() }
    private fun showChats() {
        chatList.removeAllViews()
        val chats = TelegramClient.listChats().sortedBy { it.title }
        chatList.addView(label("Channels / supergroups (${chats.size}). Select 'קונים באושר':"))
        for (chat in chats) chatList.addView(button(chat.title) {
            getSharedPreferences("telegram_source", MODE_PRIVATE).edit().putLong("chat_id", chat.id).putString("chat_title", chat.title).apply()
            TelegramClient.readHistory(chat.id)
            status.text = "Reading: ${chat.title}"
        })
    }
    private fun process(messages: List<TdApi.Message>) {
        val matched = messages.count { msg ->
            val content = msg.content
            val text = when (content) {
                is TdApi.MessageText -> content.text.text
                is TdApi.MessagePhoto -> content.caption.text
                else -> ""
            }
            Store.products(this).any { Matcher.matches(text, it) }
        }
        status.text = "Read ${messages.size} recent posts. Text/caption matches: $matched. Image OCR is not yet connected."
    }
    override fun onDestroy() {
        TelegramClient.onUpdate = null; TelegramClient.onChats = null; TelegramClient.onHistory = null
        super.onDestroy()
    }
}
