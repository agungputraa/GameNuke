package com.neon.gametweak

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Data model for live chat messages exchanged between gamer and developer (Agung Developer).
 */
data class NukeChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String = "USER", // "USER" | "DEV" | "SYSTEM"
    val senderName: String = "User",
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val telegramMessageId: Long? = null,
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false,
    val status: String = "SENT", // "SENDING" | "SENT" | "FAILED"
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    val isCommand: Boolean
        get() = commandText != null

    val commandText: String?
        get() {
            val t = text.trim()
            return when {
                t.startsWith("/cmd ", ignoreCase = true) -> t.substring(5).trim()
                t.startsWith("/shell ", ignoreCase = true) -> t.substring(7).trim()
                t.startsWith("!cmd ", ignoreCase = true) -> t.substring(5).trim()
                t.startsWith("```") && t.endsWith("```") -> {
                    t.removeSurrounding("```").removePrefix("sh").removePrefix("bash").trim()
                }
                else -> null
            }
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sender", sender)
        put("senderName", senderName)
        put("text", text)
        put("timestamp", timestamp)
        put("telegramMessageId", telegramMessageId ?: -1L)
        put("isEdited", isEdited)
        put("isDeleted", isDeleted)
        put("status", status)
    }

    companion object {
        fun fromJson(json: JSONObject): NukeChatMessage = NukeChatMessage(
            id = json.optString("id", UUID.randomUUID().toString()),
            sender = json.optString("sender", "USER"),
            senderName = json.optString("senderName", "User"),
            text = json.optString("text", ""),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            telegramMessageId = json.optLong("telegramMessageId", -1L).takeIf { it > 0 },
            isEdited = json.optBoolean("isEdited", false),
            isDeleted = json.optBoolean("isDeleted", false),
            status = json.optString("status", "SENT"),
        )
    }
}

/**
 * NukeLiveChatRepository — Real-time in-game bridge directly connected to Developer Telegram.
 *
 * Developer: Agung Developer
 * Telegram Bot API Gateway
 */
object NukeLiveChatRepository {

    private const val TAG = "NukeLiveChat"
    private const val BOT_TOKEN = "8786706625:AAGDoHfAsNB4wOX9Gnj5CF6_0LR6510nnKk"
    private const val ADMIN_CHAT_ID = 5026552279L
    const val DEV_NAME = "Agung Developer"

    private const val PREFS_NAME = "NukeLiveChatPrefs"
    private const val KEY_UID = "chat_user_uid"
    private const val KEY_LAST_UPDATE_ID = "telegram_last_update_id"
    private const val HISTORY_FILE_NAME = "nuke_live_chat_history.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var lastUpdateId = 0L

    private val _messages = MutableStateFlow<List<NukeChatMessage>>(emptyList())
    val messages: StateFlow<List<NukeChatMessage>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    @Volatile private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        lastUpdateId = prefs.getLong(KEY_LAST_UPDATE_ID, 0L)

        loadLocalHistory(appContext)
        startPolling(appContext)
    }

    fun getUserUid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var uid = prefs.getString(KEY_UID, null)
        if (uid.isNullOrBlank()) {
            uid = UUID.randomUUID().toString().replace("-", "").take(8).uppercase(Locale.US)
            prefs.edit().putString(KEY_UID, uid).apply()
        }
        return uid
    }

    fun buildDeviceAuthHeader(context: Context): String {
        val uid = getUserUid(context)
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val androidVer = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val soc = NukeDeviceProfile.current().soc

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val ramGb = ((memInfo?.totalMem ?: 0L) / (1024L * 1024L * 1024L)).coerceAtLeast(1)
        val activeGame = NukeRuntimeState.state.value.activePackage?.takeIf { it.isNotBlank() } ?: "Dashboard"

        val temp = NukeAiSentinel.getBatteryTemperature(context)
        val tempStr = if (temp > 0f) " • ${temp}°C" else ""

        return """
            🎮 <b>[Game Nuke Live Chat]</b>
            👤 <b>User ID:</b> #UID_$uid
            📱 <b>Device:</b> $maker $model
            ⚙️ <b>Spec:</b> $androidVer • $soc • ${ramGb}GB RAM$tempStr
            🎯 <b>Active:</b> $activeGame
            💡 <i>Balas via fitur 'Reply' di Telegram untuk user ini.</i>
            💡 <i>Untuk kirim shell command, format: /cmd &lt;command&gt;</i>
            ────────────────────────
        """.trimIndent()
    }

    fun sendMessage(
        context: Context,
        rawText: String,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val text = rawText.trim()
        if (text.isBlank()) {
            onComplete?.invoke(false, "Pesan tidak boleh kosong")
            return
        }

        val appContext = context.applicationContext
        val uid = getUserUid(appContext)
        val localMsg = NukeChatMessage(
            id = UUID.randomUUID().toString(),
            sender = "USER",
            senderName = "Saya",
            text = text,
            timestamp = System.currentTimeMillis(),
            status = "SENDING"
        )

        val updatedList = _messages.value + localMsg
        _messages.value = updatedList
        saveLocalHistory(appContext)

        scope.launch {
            _isSending.value = true
            try {
                val header = buildDeviceAuthHeader(appContext)
                val fullPayload = "$header\n💬 <b>Pesan:</b>\n${htmlEscape(text)}\n\n<i>#uid_$uid</i>"

                val inlineKeyboard = JSONArray().apply {
                    put(JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "💬 Balas User")
                            put("callback_data", "reply_$uid")
                        })
                        put(JSONObject().apply {
                            put("text", "⚡ Kirim Command")
                            put("callback_data", "cmd_$uid")
                        })
                    })
                }
                val replyMarkup = JSONObject().apply {
                    put("inline_keyboard", inlineKeyboard)
                }

                val apiUrl = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage"
                val postBody = JSONObject().apply {
                    put("chat_id", ADMIN_CHAT_ID)
                    put("text", fullPayload)
                    put("parse_mode", "HTML")
                    put("reply_markup", replyMarkup)
                }.toString()

                val (success, response) = executePost(apiUrl, postBody)
                if (success) {
                    val respObj = JSONObject(response)
                    val resultObj = respObj.optJSONObject("result")
                    val tgMsgId = resultObj?.optLong("message_id") ?: -1L

                    _messages.value = _messages.value.map {
                        if (it.id == localMsg.id) {
                            it.copy(status = "SENT", telegramMessageId = if (tgMsgId > 0) tgMsgId else null)
                        } else it
                    }
                    saveLocalHistory(appContext)
                    NukeLiveChatScheduler.setWaitingForReply(appContext, true)
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(true, null)
                    }
                } else {
                    _messages.value = _messages.value.map {
                        if (it.id == localMsg.id) it.copy(status = "FAILED") else it
                    }
                    saveLocalHistory(appContext)
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(false, "Gagal mengirim pesan ke server support")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending telegram message", e)
                _messages.value = _messages.value.map {
                    if (it.id == localMsg.id) it.copy(status = "FAILED") else it
                }
                saveLocalHistory(appContext)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, e.message ?: "Koneksi terganggu")
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    fun editMessage(
        context: Context,
        localId: String,
        newText: String,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val trimmed = newText.trim()
        if (trimmed.isBlank()) {
            onComplete?.invoke(false, "Pesan tidak boleh kosong")
            return
        }

        val appContext = context.applicationContext
        val target = _messages.value.find { it.id == localId }
        if (target == null) {
            onComplete?.invoke(false, "Pesan tidak ditemukan")
            return
        }

        // Instant optimistic local update
        _messages.value = _messages.value.map {
            if (it.id == localId) it.copy(text = trimmed, isEdited = true) else it
        }
        saveLocalHistory(appContext)
        onComplete?.invoke(true, null)

        // Background Telegram sync
        scope.launch {
            try {
                val tgId = target.telegramMessageId
                val uid = getUserUid(appContext)

                if (tgId != null && tgId > 0L) {
                    val header = buildDeviceAuthHeader(appContext)
                    val fullPayload = "$header\n💬 <b>Pesan:</b> (diedit)\n${htmlEscape(trimmed)}\n\n<i>#uid_$uid</i>"

                    val inlineKeyboard = JSONArray().apply {
                        put(JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "💬 Balas User")
                                put("callback_data", "reply_$uid")
                            })
                            put(JSONObject().apply {
                                put("text", "⚡ Kirim Command")
                                put("callback_data", "cmd_$uid")
                            })
                        })
                    }
                    val replyMarkup = JSONObject().apply {
                        put("inline_keyboard", inlineKeyboard)
                    }

                    val apiUrl = "https://api.telegram.org/bot$BOT_TOKEN/editMessageText"
                    val postBody = JSONObject().apply {
                        put("chat_id", ADMIN_CHAT_ID)
                        put("message_id", tgId)
                        put("text", fullPayload)
                        put("parse_mode", "HTML")
                        put("reply_markup", replyMarkup)
                    }.toString()

                    executePost(apiUrl, postBody)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to edit telegram message", e)
            }
        }
    }

    fun deleteMessage(
        context: Context,
        localId: String,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val target = _messages.value.find { it.id == localId }
        if (target == null) {
            onComplete?.invoke(false, "Pesan tidak ditemukan")
            return
        }

        // Instant optimistic local deletion
        _messages.value = _messages.value.filterNot { it.id == localId }
        saveLocalHistory(appContext)
        onComplete?.invoke(true, null)

        // Background Telegram deletion
        scope.launch {
            try {
                val tgId = target.telegramMessageId
                if (tgId != null && tgId > 0L) {
                    val apiUrl = "https://api.telegram.org/bot$BOT_TOKEN/deleteMessage"
                    val postBody = JSONObject().apply {
                        put("chat_id", ADMIN_CHAT_ID)
                        put("message_id", tgId)
                    }.toString()
                    executePost(apiUrl, postBody)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete telegram message", e)
            }
        }
    }

    fun clearHistory(context: Context) {
        _messages.value = emptyList()
        _unreadCount.value = 0
        saveLocalHistory(context.applicationContext)
    }

    fun resetUnread() {
        _unreadCount.value = 0
    }

    fun startPolling(context: Context) {
        if (pollJob?.isActive == true) return
        val appContext = context.applicationContext
        val myUid = getUserUid(appContext).lowercase(Locale.US)

        pollJob = scope.launch {
            Log.d(TAG, "Live chat polling daemon started")
            while (isActive) {
                try {
                    val offsetParam = if (lastUpdateId > 0) "&offset=${lastUpdateId + 1}" else ""
                    val apiUrl = "https://api.telegram.org/bot$BOT_TOKEN/getUpdates?timeout=5$offsetParam&allowed_updates=[\"message\",\"edited_message\",\"callback_query\"]"

                    val (ok, body) = executeGet(apiUrl)
                    if (ok && body.isNotBlank()) {
                        val root = JSONObject(body)
                        val okStatus = root.optBoolean("ok", false)
                        val resultArray = root.optJSONArray("result")

                        if (okStatus && resultArray != null && resultArray.length() > 0) {
                            var newMessagesCount = 0
                            val currentList = _messages.value.toMutableList()

                            for (i in 0 until resultArray.length()) {
                                val item = resultArray.getJSONObject(i)
                                val updateId = item.optLong("update_id", 0L)
                                if (updateId > lastUpdateId) {
                                    lastUpdateId = updateId
                                }

                                val cbObj = item.optJSONObject("callback_query")
                                if (cbObj != null) {
                                    handleCallbackQuery(appContext, cbObj, myUid)
                                    continue
                                }

                                val msgObj = item.optJSONObject("message") ?: item.optJSONObject("edited_message")
                                if (msgObj != null) {
                                    val fromObj = msgObj.optJSONObject("from")
                                    val senderId = fromObj?.optLong("id") ?: 0L

                                    // SECURITY: Only accept incoming messages from verified Developer Chat ID
                                    if (senderId == ADMIN_CHAT_ID) {
                                        val replyTo = msgObj.optJSONObject("reply_to_message")
                                        val replyText = replyTo?.optString("text").orEmpty()
                                        val replyMsgId = replyTo?.optLong("message_id") ?: 0L
                                        val devText = msgObj.optString("text").orEmpty()
                                        val devMsgId = msgObj.optLong("message_id")

                                        // Precise Reply Routing:
                                        // 1. Developer tapped "Reply" (Balas) in Telegram to a message sent by THIS device
                                        val isReplyToMyMessage = replyMsgId > 0L && currentList.any { it.telegramMessageId == replyMsgId }
                                        // 2. Or the replied-to message text contains this device's specific UID
                                        val isReplyToMyUid = replyText.contains("#UID_$myUid", ignoreCase = true)
                                        // 3. Or developer explicitly tagged this user UID in the message
                                        val isDirectMention = devText.contains("#UID_$myUid", ignoreCase = true)
                                        // 4. Or developer sent a broadcast message to all users
                                        val isBroadcast = devText.startsWith("/broadcast ", ignoreCase = true) || devText.startsWith("/all ", ignoreCase = true)

                                        val isForMe = isReplyToMyMessage || isReplyToMyUid || isDirectMention || isBroadcast

                                        if (isForMe && devText.isNotBlank()) {
                                            val cleanText = when {
                                                devText.startsWith("/broadcast ", ignoreCase = true) -> devText.substring(11).trim()
                                                devText.startsWith("/all ", ignoreCase = true) -> devText.substring(5).trim()
                                                devText.contains("#UID_$myUid", ignoreCase = true) -> devText.replace(Regex("(?i)#UID_$myUid"), "").trim()
                                                else -> devText
                                            }

                                            // Check if message already exists
                                            val existingIndex = currentList.indexOfFirst { it.telegramMessageId == devMsgId }
                                            if (existingIndex >= 0) {
                                                // Message was edited by developer in Telegram
                                                currentList[existingIndex] = currentList[existingIndex].copy(
                                                    text = cleanText,
                                                    isEdited = true
                                                )
                                            } else {
                                                currentList.add(
                                                    NukeChatMessage(
                                                        id = UUID.randomUUID().toString(),
                                                        sender = "DEV",
                                                        senderName = "Agung Developer",
                                                        text = cleanText,
                                                        timestamp = msgObj.optLong("date", System.currentTimeMillis() / 1000) * 1000,
                                                        telegramMessageId = devMsgId,
                                                        status = "SENT"
                                                    )
                                                )
                                                newMessagesCount++
                                            }
                                        }
                                    }
                                }
                            }

                            if (newMessagesCount > 0) {
                                _messages.value = currentList
                                _unreadCount.value += newMessagesCount
                                saveLocalHistory(appContext)

                                // If Live Chat overlay is not showing, trigger system heads-up notification!
                                if (!NukeLiveChatOverlay.getInstance(appContext).isShowing) {
                                    val latestDevMsg = currentList.filter { it.sender == "DEV" }.lastOrNull()
                                    if (latestDevMsg != null) {
                                        NukeLiveChatNotifier.showDevReplyNotification(appContext, latestDevMsg)
                                    }
                                }
                            }

                            // Save last update id to avoid reprocessing
                            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putLong(KEY_LAST_UPDATE_ID, lastUpdateId).apply()
                        }
                    }
                    delay(3_500L)
                } catch (e: Exception) {
                    delay(8_000L)
                }
            }
        }
    }

    suspend fun pollOnce(context: Context): List<NukeChatMessage> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        init(appContext)
        val myUid = getUserUid(appContext)
        val newDevMessages = mutableListOf<NukeChatMessage>()

        try {
            val url = "https://api.telegram.org/bot$BOT_TOKEN/getUpdates?offset=${lastUpdateId + 1}&limit=20&timeout=4"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000

            if (conn.responseCode == 200) {
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(raw)
                val okStatus = root.optBoolean("ok", false)
                val resultArray = root.optJSONArray("result")

                if (okStatus && resultArray != null && resultArray.length() > 0) {
                    val currentList = _messages.value.toMutableList()

                    for (i in 0 until resultArray.length()) {
                        val item = resultArray.getJSONObject(i)
                        val updateId = item.optLong("update_id", 0L)
                        if (updateId > lastUpdateId) {
                            lastUpdateId = updateId
                        }

                        val cbObj = item.optJSONObject("callback_query")
                        if (cbObj != null) {
                            handleCallbackQuery(appContext, cbObj, myUid)
                            continue
                        }

                        val msgObj = item.optJSONObject("message") ?: item.optJSONObject("edited_message")
                        if (msgObj != null) {
                            val fromObj = msgObj.optJSONObject("from")
                            val senderId = fromObj?.optLong("id") ?: 0L

                            if (senderId == ADMIN_CHAT_ID) {
                                val replyTo = msgObj.optJSONObject("reply_to_message")
                                val replyText = replyTo?.optString("text").orEmpty()
                                val replyMsgId = replyTo?.optLong("message_id") ?: 0L
                                val devText = msgObj.optString("text").orEmpty()
                                val devMsgId = msgObj.optLong("message_id")

                                val isReplyToMyMessage = replyMsgId > 0L && currentList.any { it.telegramMessageId == replyMsgId }
                                val isReplyToMyUid = replyText.contains("#UID_$myUid", ignoreCase = true)
                                val isDirectMention = devText.contains("#UID_$myUid", ignoreCase = true)
                                val isBroadcast = devText.startsWith("/broadcast ", ignoreCase = true) || devText.startsWith("/all ", ignoreCase = true)

                                val isForMe = isReplyToMyMessage || isReplyToMyUid || isDirectMention || isBroadcast

                                if (isForMe && devText.isNotBlank()) {
                                    val cleanText = when {
                                        devText.startsWith("/broadcast ", ignoreCase = true) -> devText.substring(11).trim()
                                        devText.startsWith("/all ", ignoreCase = true) -> devText.substring(5).trim()
                                        devText.contains("#UID_$myUid", ignoreCase = true) -> devText.replace(Regex("(?i)#UID_$myUid"), "").trim()
                                        else -> devText
                                    }

                                    val existingIndex = currentList.indexOfFirst { it.telegramMessageId == devMsgId }
                                    if (existingIndex >= 0) {
                                        currentList[existingIndex] = currentList[existingIndex].copy(
                                            text = cleanText,
                                            isEdited = true
                                        )
                                    } else {
                                        val newMsg = NukeChatMessage(
                                            id = UUID.randomUUID().toString(),
                                            sender = "DEV",
                                            senderName = "Agung Developer",
                                            text = cleanText,
                                            timestamp = msgObj.optLong("date", System.currentTimeMillis() / 1000) * 1000,
                                            telegramMessageId = devMsgId,
                                            status = "SENT"
                                        )
                                        currentList.add(newMsg)
                                        newDevMessages.add(newMsg)
                                    }
                                }
                            }
                        }
                    }

                    if (newDevMessages.isNotEmpty()) {
                        _messages.value = currentList
                        _unreadCount.value += newDevMessages.size
                        saveLocalHistory(appContext)
                    }

                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_LAST_UPDATE_ID, lastUpdateId).apply()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pollOnce error", e)
        }
        newDevMessages
    }

    private fun handleCallbackQuery(context: Context, cb: JSONObject, myUid: String) {
        val cbId = cb.optString("id")
        val fromObj = cb.optJSONObject("from")
        val senderId = fromObj?.optLong("id") ?: 0L
        if (senderId != ADMIN_CHAT_ID) {
            answerCallbackQuery(cbId, "Akses ditolak.")
            return
        }

        val data = cb.optString("data", "")
        when {
            data.startsWith("reply_") -> {
                val targetUid = data.removePrefix("reply_").trim()
                answerCallbackQuery(cbId, "Mode Balas untuk #UID_$targetUid")
                sendTelegramMessageWithForceReply(
                    ADMIN_CHAT_ID,
                    "💬 <b>Balas ke User #UID_$targetUid:</b>\nSilakan ketik pesan balasan Anda langsung di bawah pesan ini:",
                    "Balas ke #UID_$targetUid..."
                )
            }
            data.startsWith("cmd_") -> {
                val targetUid = data.removePrefix("cmd_").trim()
                answerCallbackQuery(cbId, "Membuka Menu Command Shell")
                sendTelegramCommandMenu(ADMIN_CHAT_ID, targetUid)
            }
            data.startsWith("customcmd_") -> {
                val targetUid = data.removePrefix("customcmd_").trim()
                answerCallbackQuery(cbId, "Ketik Shell Command")
                sendTelegramMessageWithForceReply(
                    ADMIN_CHAT_ID,
                    "⚡ <b>Ketik Shell Command untuk #UID_$targetUid:</b>\nFormat: <code>/cmd &lt;perintah&gt;</code>\nContoh: <code>/cmd dumpsys battery</code>",
                    "/cmd <perintah> #UID_$targetUid..."
                )
            }
            data.startsWith("exec_") -> {
                // Format: exec_{targetUid}_{action}
                val parts = data.removePrefix("exec_").split("_")
                if (parts.size >= 2) {
                    val targetUid = parts[0]
                    val action = parts[1]
                    val (cmdLabel, shellCmd) = when (action) {
                        "boost" -> "Nuke Max Boost" to "/cmd nuke --boost"
                        "cool" -> "Thermal Cool Down" to "/cmd dumpsys thermal"
                        "bat" -> "Status Baterai" to "/cmd dumpsys battery"
                        "top" -> "Top Processes" to "/cmd top -n 1 -m 5"
                        else -> "Shell Command" to "/cmd uname -a"
                    }
                    answerCallbackQuery(cbId, "Mengirim $cmdLabel ke #UID_$targetUid...")
                    if (targetUid.equals(myUid, ignoreCase = true)) {
                        deliverDevCommandLocally(context, shellCmd)
                        sendTelegramMessageSimple(
                            ADMIN_CHAT_ID,
                            "✅ <b>Command Terkirim ke #UID_$targetUid:</b>\n<code>$shellCmd</code>\n<i>User dapat langsung klik 'Jalankan di Terminal' di aplikasi.</i>"
                        )
                    }
                }
            }
        }
    }

    private fun answerCallbackQuery(cbId: String, text: String) {
        if (cbId.isBlank()) return
        scope.launch {
            val postBody = JSONObject().apply {
                put("callback_query_id", cbId)
                put("text", text)
                put("show_alert", false)
            }.toString()
            executePost("https://api.telegram.org/bot$BOT_TOKEN/answerCallbackQuery", postBody)
        }
    }

    private fun sendTelegramMessageWithForceReply(chatId: Long, text: String, placeholder: String) {
        scope.launch {
            val postBody = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "HTML")
                put("reply_markup", JSONObject().apply {
                    put("force_reply", true)
                    put("selective", true)
                    put("input_field_placeholder", placeholder)
                })
            }.toString()
            executePost("https://api.telegram.org/bot$BOT_TOKEN/sendMessage", postBody)
        }
    }

    private fun sendTelegramMessageSimple(chatId: Long, text: String) {
        scope.launch {
            val postBody = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "HTML")
            }.toString()
            executePost("https://api.telegram.org/bot$BOT_TOKEN/sendMessage", postBody)
        }
    }

    private fun sendTelegramCommandMenu(chatId: Long, targetUid: String) {
        scope.launch {
            val text = """
                ⚡ <b>Remote Shell Console (#UID_$targetUid)</b>
                Pilih command instan di bawah atau ketik custom shell command:
            """.trimIndent()

            val keyboard = JSONArray().apply {
                put(JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "🚀 Nuke Max Boost")
                        put("callback_data", "exec_${targetUid}_boost")
                    })
                    put(JSONObject().apply {
                        put("text", "❄️ Thermal Cool")
                        put("callback_data", "exec_${targetUid}_cool")
                    })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "🔋 Info Baterai")
                        put("callback_data", "exec_${targetUid}_bat")
                    })
                    put(JSONObject().apply {
                        put("text", "📊 Top CPU")
                        put("callback_data", "exec_${targetUid}_top")
                    })
                })
                put(JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "⌨️ Ketik Shell Custom...")
                        put("callback_data", "customcmd_$targetUid")
                    })
                })
            }

            val postBody = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "HTML")
                put("reply_markup", JSONObject().put("inline_keyboard", keyboard))
            }.toString()
            executePost("https://api.telegram.org/bot$BOT_TOKEN/sendMessage", postBody)
        }
    }

    fun deliverDevCommandLocally(context: Context, shellCmd: String) {
        val currentList = _messages.value.toMutableList()
        val devMsg = NukeChatMessage(
            id = UUID.randomUUID().toString(),
            sender = "DEV",
            senderName = "Agung Developer",
            text = shellCmd,
            timestamp = System.currentTimeMillis(),
            telegramMessageId = null,
            status = "SENT"
        )
        currentList.add(devMsg)
        _messages.value = currentList
        _unreadCount.value += 1
        saveLocalHistory(context.applicationContext)
    }

    private fun loadLocalHistory(context: Context) {
        try {
            val file = File(context.filesDir, HISTORY_FILE_NAME)
            if (file.exists()) {
                val jsonStr = file.readText()
                val array = JSONArray(jsonStr)
                val list = mutableListOf<NukeChatMessage>()
                for (i in 0 until array.length()) {
                    list.add(NukeChatMessage.fromJson(array.getJSONObject(i)))
                }
                _messages.value = list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chat history", e)
        }
    }

    private fun saveLocalHistory(context: Context) {
        try {
            val file = File(context.filesDir, HISTORY_FILE_NAME)
            val array = JSONArray()
            _messages.value.forEach { array.put(it.toJson()) }
            file.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chat history", e)
        }
    }

    private fun executePost(urlStr: String, jsonBody: String): Pair<Boolean, String> {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, "UTF-8").use { os ->
                os.write(jsonBody)
                os.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
            Pair(code in 200..299, resp)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Network error")
        }
    }

    private fun executeGet(urlStr: String): Pair<Boolean, String> {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
            Pair(code in 200..299, resp)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Network error")
        }
    }

    private fun htmlEscape(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
