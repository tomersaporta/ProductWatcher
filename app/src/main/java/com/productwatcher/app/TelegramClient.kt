package com.productwatcher.app

import android.content.Context
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import android.os.Handler
import android.os.Looper

/** One TDLib client shared by UI, foreground service and periodic catch-up worker. */
object TelegramClient {
    private var client: Client? = null
    private var appContext: Context? = null
    @Volatile var state = "Not connected"
        private set
    @Volatile var ready = false
        private set
    var onUpdate: (() -> Unit)? = null
    var onChats: ((List<TdApi.Chat>) -> Unit)? = null
    var onHistory: ((List<TdApi.Message>) -> Unit)? = null
    private val chats = linkedMapOf<Long, TdApi.Chat>()
    private val downloads = ConcurrentHashMap<Int, MutableList<(TdApi.File?) -> Unit>>()
    private val historyInFlight = ConcurrentHashMap.newKeySet<Long>()

    @Synchronized fun start(context: Context, apiId: Int, apiHash: String) {
        if (client != null) return
        appContext = context.applicationContext
        TelegramOfferScanner.init(context)
        context.getSharedPreferences("telegram_auth", Context.MODE_PRIVATE).edit().putInt("api_id", apiId).putString("api_hash", apiHash).apply()
        state = "Connecting"
        ready = false
        try {
            System.loadLibrary("tdjni")
            client = Client.create({ update ->
                when (update) {
                    is TdApi.UpdateAuthorizationState -> auth(update.authorizationState)
                    is TdApi.UpdateNewChat -> { synchronized(chats) { chats[update.chat.id] = update.chat }; onUpdate?.invoke() }
                    is TdApi.UpdateChatTitle -> { synchronized(chats) { chats[update.chatId]?.title = update.title }; onUpdate?.invoke() }
                    is TdApi.UpdateFile -> completeDownload(update.file)
                    is TdApi.UpdateNewMessage -> {
                        val ctx = appContext
                        val selected = ctx?.getSharedPreferences("telegram_source", Context.MODE_PRIVATE)?.getLong("chat_id", 0L)
                        if (ready && ctx != null && MonitorControl.enabled(ctx) && selected == update.message.chatId)
                            TelegramOfferScanner.scan(update.message.chatId, listOf(update.message))
                    }
                }
            }, { error -> state = error.message ?: "TDLib error"; onUpdate?.invoke() }, null)
        } catch (e: Throwable) { state = "TDLib failed: ${e.message}"; onUpdate?.invoke() }
    }

    fun resume(context: Context) {
        TelegramOfferScanner.init(context)
        val prefs = context.getSharedPreferences("telegram_auth", Context.MODE_PRIVATE)
        val id = prefs.getInt("api_id", 0)
        val hash = prefs.getString("api_hash", "").orEmpty()
        if (id > 0 && hash.isNotBlank()) start(context, id, hash)
    }

    private fun auth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                state = "Initializing TDLib"
                val ctx = appContext ?: return
                val p = ctx.getSharedPreferences("telegram_auth", Context.MODE_PRIVATE)
                send(TdApi.SetTdlibParameters().apply {
                    databaseDirectory = ctx.getDir("telegram_db", Context.MODE_PRIVATE).absolutePath
                    filesDirectory = ctx.getDir("telegram_files", Context.MODE_PRIVATE).absolutePath
                    useFileDatabase = true; useChatInfoDatabase = true; useMessageDatabase = true; useSecretChats = false
                    apiId = p.getInt("api_id", 0); apiHash = p.getString("api_hash", "").orEmpty()
                    systemLanguageCode = "en"; deviceModel = android.os.Build.MODEL
                    systemVersion = android.os.Build.VERSION.RELEASE; applicationVersion = "0.4"
                })
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> state = "Enter phone number"
            is TdApi.AuthorizationStateWaitCode -> state = "Enter Telegram login code"
            is TdApi.AuthorizationStateWaitPassword -> state = "Enter 2-step password"
            is TdApi.AuthorizationStateWaitEmailAddress -> state = "Enter recovery email"
            is TdApi.AuthorizationStateWaitEmailCode -> state = "Enter email code"
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> state = "Confirm on another device: ${s.link}"
            is TdApi.AuthorizationStateReady -> { state = "Connected"; ready = true; loadChats(); scanSelectedChannel() }
            is TdApi.AuthorizationStateClosed -> { state = "Session closed"; ready = false; client = null }
            else -> state = s.javaClass.simpleName
        }
        onUpdate?.invoke()
    }

    fun submit(value: String) {
        when (state) {
            "Enter phone number" -> send(TdApi.SetAuthenticationPhoneNumber(value, null))
            "Enter Telegram login code" -> send(TdApi.CheckAuthenticationCode(value))
            "Enter 2-step password" -> send(TdApi.CheckAuthenticationPassword(value))
            "Enter recovery email" -> send(TdApi.SetAuthenticationEmailAddress(value))
            "Enter email code" -> send(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(value)))
        }
    }

    private fun send(request: TdApi.Function<*>, callback: ((TdApi.Object) -> Unit)? = null) {
        client?.send(request) { response ->
            if (response is TdApi.Error && !(request is TdApi.LoadChats && response.code == 404)) {
                state = "Telegram error ${response.code}: ${response.message}"; onUpdate?.invoke()
            }
            callback?.invoke(response)
        }
    }

    /** DownloadFile can return before the download is finished. Wait for UpdateFile. */
    fun downloadPhoto(fileId: Int, callback: (TdApi.File?) -> Unit) {
        downloads.compute(fileId) { _, old -> (old ?: mutableListOf()).apply { add(callback) } }
        // Fail a stalled transfer so the next scheduled catch-up can retry it.
        Handler(Looper.getMainLooper()).postDelayed({ failDownload(fileId) }, 90_000L)
        send(TdApi.DownloadFile(fileId, 16, 0, 0, false)) { response ->
            when (response) {
                is TdApi.File -> if (response.local.isDownloadingCompleted) completeDownload(response)
                is TdApi.Error -> failDownload(fileId)
            }
        }
    }
    private fun completeDownload(file: TdApi.File) {
        if (!file.local.isDownloadingCompleted) return
        val callbacks = downloads.remove(file.id) ?: return
        callbacks.forEach { it(file) }
    }
    private fun failDownload(fileId: Int) { downloads.remove(fileId)?.forEach { it(null) } }


    fun deleteDownloadedPhoto(fileId: Int) {
        send(TdApi.DeleteFile(fileId))
    }

    fun loadChats() {
        if (!ready) return
        send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) {
            onChats?.invoke(listChats())
        }
    }
    fun listChats(): List<TdApi.Chat> = synchronized(chats) { chats.values.filter { it.type is TdApi.ChatTypeSupergroup }.toList() }

    fun scanSelectedChannel() {
        val ctx = appContext ?: return
        val chatId = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).getLong("chat_id", 0L)
        if (ready && MonitorControl.enabled(ctx) && chatId != 0L) readHistory(chatId)
    }

    /** Page backwards through recent history, not just one fixed batch of 100. */
    fun readHistory(chatId: Long) {
        if (!ready || !historyInFlight.add(chatId)) return
        val ctx = appContext ?: run { historyInFlight.remove(chatId); return }
        val checkpoint = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).getLong("last_discovered_$chatId", 0L)
        var newest = checkpoint
        var pages = 0
        fun page(from: Long) {
            send(TdApi.GetChatHistory(chatId, from, 0, 100, false)) { result ->
                if (result !is TdApi.Messages) { historyInFlight.remove(chatId); return@send }
                val batch = result.messages.toList()
                if (batch.isEmpty()) { historyInFlight.remove(chatId); return@send }
                newest = maxOf(newest, batch.maxOf { it.id })
                val fresh = if (pages == 0) batch else batch.filter { it.id > checkpoint }
                if (fresh.isNotEmpty()) TelegramOfferScanner.scan(chatId, fresh.sortedBy { it.id })
                onHistory?.invoke(batch)
                pages++
                val oldest = batch.minOf { it.id }
                if (pages < 10 && batch.size >= 100 && oldest > checkpoint && oldest != from) page(oldest)
                else {
                    // Discovery cursor is NOT the processing checkpoint. Failed images remain retryable
                    // because the scanner tracks completion per message, not only this cursor.
                    ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).edit().putLong("last_discovered_$chatId", newest).apply()
                    historyInFlight.remove(chatId)
                }
            }
        }
        page(0L)
    }
}
