package com.productwatcher.app

import android.content.Context
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/** Local TDLib session. No bot, remote relay or API keys in source control. */
object TelegramClient {
    private var client: Client? = null
    private var appContext: Context? = null
    var state: String = "Not connected"
        private set
    var ready = false
        private set
    var onUpdate: (() -> Unit)? = null
    var onChats: ((List<TdApi.Chat>) -> Unit)? = null
    var onHistory: ((List<TdApi.Message>) -> Unit)? = null
    private val chats = linkedMapOf<Long, TdApi.Chat>()

    fun start(context: Context, apiId: Int, apiHash: String) {
        if (client != null) return
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences("telegram_auth", Context.MODE_PRIVATE)
        prefs.edit().putInt("api_id", apiId).putString("api_hash", apiHash).apply()
        state = "Connecting"
        try {
            client = Client.create({ update ->
                when (update) {
                    is TdApi.UpdateAuthorizationState -> auth(update.authorizationState)
                    is TdApi.UpdateNewChat -> { synchronized(chats) { chats[update.chat.id] = update.chat }; onUpdate?.invoke() }
                    is TdApi.UpdateChatTitle -> { synchronized(chats) { chats[update.chatId]?.title = update.title }; onUpdate?.invoke() }
                }
            }, { error -> state = error.message ?: "TDLib error"; onUpdate?.invoke() }, null)
        } catch (e: Throwable) { state = "TDLib failed: ${e.message}"; onUpdate?.invoke() }
    }

    fun resume(context: Context) {
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
                    applicationVersion = "0.2"
                }
                send(params)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> state = "Enter phone number"
            is TdApi.AuthorizationStateWaitCode -> state = "Enter Telegram login code"
            is TdApi.AuthorizationStateWaitPassword -> state = "Enter 2-step password"
            is TdApi.AuthorizationStateWaitEmailAddress -> state = "Enter recovery email"
            is TdApi.AuthorizationStateWaitEmailCode -> state = "Enter email code"
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> state = "Confirm on another device: ${s.link}"
            is TdApi.AuthorizationStateReady -> { state = "Connected"; ready = true; loadChats() }
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
            if (response is TdApi.Error) { state = "Telegram error ${response.code}: ${response.message}"; onUpdate?.invoke() }
            callback?.invoke(response)
        }
    }

    fun loadChats() {
        if (!ready) return
        send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) { response ->
            // Chats are delivered through UpdateNewChat. LoadChats may return 404 when exhausted.
            if (response is TdApi.Error && response.code == 404) state = "Connected"
            val snapshot = synchronized(chats) { chats.values.filter { it.type is TdApi.ChatTypeSupergroup }.toList() }
            onChats?.invoke(snapshot)
        }
    }

    fun listChats(): List<TdApi.Chat> = synchronized(chats) { chats.values.filter { it.type is TdApi.ChatTypeSupergroup }.toList() }

    fun readHistory(chatId: Long) {
        send(TdApi.GetChatHistory(chatId, 0, 0, 0, 100, false)) { result ->
            if (result is TdApi.Messages) onHistory?.invoke(result.messages.toList())
        }
    }
}
