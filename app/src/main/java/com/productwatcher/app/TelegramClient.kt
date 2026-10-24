package com.productwatcher.app

import android.content.Context
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/** Local Telegram session. API credentials and TDLib database stay on the device. */
object TelegramClient {
    private var client: Client? = null
    private var appContext: Context? = null
    @Volatile var state: String = "Not connected"
        private set
    @Volatile var ready = false
        private set
    var onUpdate: (() -> Unit)? = null
    var onChats: ((List<TdApi.Chat>) -> Unit)? = null
    var onHistory: ((List<TdApi.Message>) -> Unit)? = null
    private val chats = linkedMapOf<Long, TdApi.Chat>()

    fun start(context: Context, apiId: Int, apiHash: String) {
        if (client != null) return
        appContext = context.applicationContext
        TelegramOfferScanner.init(context)
        context.getSharedPreferences("telegram_auth", Context.MODE_PRIVATE).edit()
            .putInt("api_id", apiId).putString("api_hash", apiHash).apply()
        state = "Connecting"
        try {
            System.loadLibrary("tdjni")
            client = Client.create({ update ->
                when (update) {
                    is TdApi.UpdateAuthorizationState -> auth(update.authorizationState)
                    is TdApi.UpdateNewChat -> { synchronized(chats) { chats[update.chat.id] = update.chat }; onUpdate?.invoke() }
                    is TdApi.UpdateChatTitle -> { synchronized(chats) { chats[update.chatId]?.title = update.title }; onUpdate?.invoke() }
                    is TdApi.UpdateNewMessage -> {
                        val ctx = appContext
                        val selected = ctx?.getSharedPreferences("telegram_source", Context.MODE_PRIVATE)?.getLong("chat_id", 0L)
                        if (ready && ctx != null && MonitorControl.enabled(ctx) && selected == update.message.chatId) {
                            TelegramOfferScanner.scan(update.message.chatId, listOf(update.message))
                        }
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
                val params = TdApi.SetTdlibParameters().apply {
                    databaseDirectory = ctx.getDir("telegram_db", Context.MODE_PRIVATE).absolutePath
                    filesDirectory = ctx.getDir("telegram_files", Context.MODE_PRIVATE).absolutePath
                    useFileDatabase = true
                    useChatInfoDatabase = true
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = p.getInt("api_id", 0)
                    apiHash = p.getString("api_hash", "").orEmpty()
                    systemLanguageCode = "en"
                    deviceModel = android.os.Build.MODEL
                    systemVersion = android.os.Build.VERSION.RELEASE
                    applicationVersion = "0.3"
                }
                send(params)
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

    fun downloadPhoto(fileId: Int, callback: (TdApi.File?) -> Unit) {
        send(TdApi.DownloadFile(fileId, 16, 0, 0, true)) { response ->
            callback(response as? TdApi.File)
        }
    }

    fun loadChats() {
        if (!ready) return
        send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) {
            val snapshot = synchronized(chats) { chats.values.filter { it.type is TdApi.ChatTypeSupergroup }.toList() }
            onChats?.invoke(snapshot)
        }
    }

    fun listChats(): List<TdApi.Chat> = synchronized(chats) { chats.values.filter { it.type is TdApi.ChatTypeSupergroup }.toList() }

    fun scanSelectedChannel() {
        val ctx = appContext ?: return
        val chatId = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE).getLong("chat_id", 0L)
        if (ready && MonitorControl.enabled(ctx) && chatId != 0L) readHistory(chatId)
    }

    fun readHistory(chatId: Long) {
        send(TdApi.GetChatHistory(chatId, 0, 0, 100, false)) { result ->
            if (result is TdApi.Messages) {
                val messages = result.messages.toList()
                val ctx = appContext
                val checkpoint = ctx?.getSharedPreferences("telegram_source", Context.MODE_PRIVATE)?.getLong("last_message_$chatId", 0L) ?: 0L
                val fresh = messages.filter { it.id > checkpoint }.sortedBy { it.id }
                if (fresh.isNotEmpty()) {
                    TelegramOfferScanner.scan(chatId, fresh)
                    ctx?.getSharedPreferences("telegram_source", Context.MODE_PRIVATE)?.edit()?.putLong("last_message_$chatId", fresh.maxOf { it.id })?.apply()
                }
                onHistory?.invoke(messages)
            }
        }
    }
}
