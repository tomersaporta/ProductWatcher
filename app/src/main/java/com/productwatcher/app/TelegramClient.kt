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
    fun hasHistoryWork(): Boolean = historyInFlight.isNotEmpty()

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
                        val watched = ctx?.let { Store.monitoredChannels(it).any { entry -> entry.first == update.message.chatId } } == true
                        if (ready && ctx != null && MonitorControl.enabled(ctx) && watched)
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
            is TdApi.AuthorizationStateReady -> { state = "Connected"; ready = true; appContext?.let { MonitorControl.ensureScheduled(it) }; loadChats(); scanSelectedChannel() }
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

    /** Ask TDLib to remove only its cached local copy, never the Telegram message. */
    fun deleteDownloadedPhoto(fileId: Int) {
        send(TdApi.DeleteFile(fileId)) { response ->
            if (response is TdApi.Error) appContext?.let {
                Store.setTelegramStatus(it, "OCR complete; cache cleanup failed: ${response.message}")
            }
        }
    }

    fun loadChats() {
        if (!ready) return
        send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) {
            onChats?.invoke(listChats())
        }
    }
    fun listChats(): List<TdApi.Chat> = synchronized(chats) { chats.values.filter { it.type is TdApi.ChatTypeSupergroup }.toList() }

    /** Historical backfill is persistent and bounded; fresh posts are always checked first. */
    fun scanSelectedChannel() {
        val ctx = appContext ?: return
        if (!ready || !MonitorControl.enabled(ctx)) return
        Store.monitoredChannels(ctx).forEach { (id, _) -> readHistory(id) }
    }

    fun readHistory(chatId: Long) {
        if (!ready || !historyInFlight.add(chatId)) return
        val ctx = appContext ?: run { historyInFlight.remove(chatId); return }
        if (!MonitorControl.enabled(ctx) || Store.monitoredChannels(ctx).none { it.first == chatId }) {
            historyInFlight.remove(chatId); return
        }
        val source = ctx.getSharedPreferences("telegram_source", Context.MODE_PRIVATE)
        val currentGeneration = Store.scanGeneration(ctx)
        val latestKey = "last_discovered_$chatId"
        val historyKey = "history_pending_$chatId"
        val cursorKey = "history_cursor_$chatId"
        val countKey = "history_checked_$chatId"
        val gapCursorKey = "catchup_cursor_$chatId"
        val gapTargetKey = "catchup_target_$chatId"
        Store.channelEvent(ctx, chatId, "Checking latest Telegram posts")
        fun release() { historyInFlight.remove(chatId) }
        fun request(from: Long, callback: (List<TdApi.Message>?) -> Unit) {
            send(TdApi.GetChatHistory(chatId, from, 0, 100, false)) { obj ->
                callback((obj as? TdApi.Messages)?.messages?.toList())
            }
        }
        fun historical(from: Long, remainingPages: Int) {
            if (!source.getBoolean(historyKey, false)) { release(); return }
            request(from) { batch ->
                if (batch == null) {
                    Store.channelEvent(ctx, chatId, "History fetch failed; retry on next scheduled pass")
                    release()
                    return@request
                }
                if (batch.isEmpty()) {
                    source.edit().putBoolean(historyKey, false).apply()
                    Store.channelEvent(ctx, chatId, "Historical rescan completed")
                    release()
                    return@request
                }
                val oldest = batch.minOf { it.id }
                val count = source.getLong(countKey, 0L) + batch.size
                onHistory?.invoke(batch)
                TelegramOfferScanner.scan(chatId, batch.sortedBy { it.id }) {
                    // Save progress after OCR / downloads resolve; failure IDs are retained for retry.
                    if (Store.scanGeneration(ctx) != currentGeneration) {
                        // Another product was added while this page was running; restart new generation at page one.
                        release()
                        return@scan
                    }
                    source.edit().putLong(cursorKey, oldest).putLong(countKey, count).apply()
                    val end = batch.size < 100 || (from != 0L && oldest >= from)
                    if (end) {
                        source.edit().putBoolean(historyKey, false).apply()
                        Store.channelEvent(ctx, chatId, "Historical rescan completed · $count posts visited")
                        release()
                    } else if (remainingPages > 1) {
                        historical(oldest, remainingPages - 1)
                    } else {
                        Store.channelEvent(ctx, chatId, "Historical scan paused · $count visited; resumes next cycle")
                        release()
                    }
                }
            }
        }
        // Fill gaps if Android suspended the app long enough for more than 100 new posts.
        fun catchupGap(from: Long, target: Long, remainingPages: Int) {
            request(from) { batch ->
                if (batch == null) {
                    Store.channelEvent(ctx, chatId, "Gap catch-up fetch failed; will retry")
                    release()
                    return@request
                }
                if (batch.isEmpty()) {
                    source.edit().remove(gapCursorKey).remove(gapTargetKey).apply()
                    release()
                    return@request
                }
                val newest = batch.maxOf { it.id }
                val oldest = batch.minOf { it.id }
                val newMessages = batch.filter { it.id > target }
                onHistory?.invoke(batch)
                TelegramOfferScanner.scan(chatId, newMessages.sortedBy { it.id }) {
                    if (newMessages.isEmpty() || oldest <= target || batch.size < 100 ||
                        (from != 0L && oldest >= from)) {
                        source.edit().remove(gapCursorKey).remove(gapTargetKey).apply()
                        Store.channelEvent(ctx, chatId, "Missed-post catch-up completed")
                        release()
                    } else {
                        source.edit().putLong(gapCursorKey, oldest).apply()
                        if (remainingPages > 1) catchupGap(oldest, target, remainingPages - 1)
                        else {
                            Store.channelEvent(ctx, chatId, "Missed-post catch-up paused; will resume")
                            release()
                        }
                    }
                }
            }
        }
        fun retryFailures(then: () -> Unit) {
            val retries = Store.failedMessages(ctx, chatId).take(15)
            if (retries.isEmpty()) { then(); return }
            val left = java.util.concurrent.atomic.AtomicInteger(retries.size)
            for (id in retries) {
                send(TdApi.GetMessage(chatId, id)) { obj ->
                    if (obj is TdApi.Message) {
                        TelegramOfferScanner.scan(chatId, listOf(obj)) {
                            if (left.decrementAndGet() == 0) then()
                        }
                    } else {
                        // Unavailable or removed message: don't endlessly retry.
                        Store.recordMessageOutcome(ctx, chatId, id, true)
                        if (left.decrementAndGet() == 0) then()
                    }
                }
            }
        }
        request(0L) { latest ->
            if (latest == null) {
                Store.channelEvent(ctx, chatId, "Latest Telegram history unavailable; will retry")
                release()
                return@request
            }
            val previous = source.getLong(latestKey, 0L)
            val newest = latest.maxOfOrNull { it.id } ?: previous
            val oldest = latest.minOfOrNull { it.id } ?: previous
            // Persist the catch-up boundary before advancing the latest checkpoint.
            if (previous != 0L && oldest > previous && !source.contains(gapCursorKey)) {
                source.edit().putLong(gapCursorKey, oldest).putLong(gapTargetKey, previous).apply()
            }
            onHistory?.invoke(latest)
            TelegramOfferScanner.scan(chatId, latest.sortedBy { it.id }) {
                source.edit().putLong(latestKey, maxOf(newest, previous)).apply()
                retryFailures {
                    if (source.getBoolean(historyKey, false)) {
                        historical(source.getLong(cursorKey, 0L), 4)
                    } else if (source.contains(gapCursorKey)) {
                        catchupGap(source.getLong(gapCursorKey, 0L),
                            source.getLong(gapTargetKey, previous), 4)
                    } else {
                        Store.channelEvent(ctx, chatId, "Latest scan completed · ${latest.size} posts checked")
                        release()
                    }
                }
            }
        }
    }
}
