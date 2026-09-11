package com.neon.gametweak

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
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
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
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
    val replyToText: String? = null,
    val replyToSender: String? = null,
    val imagePath: String? = null,
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
        put("replyToText", replyToText)
        put("replyToSender", replyToSender)
        put("imagePath", imagePath)
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
            replyToText = json.optString("replyToText").takeIf { it.isNotBlank() && it != "null" },
            replyToSender = json.optString("replyToSender").takeIf { it.isNotBlank() && it != "null" },
            imagePath = json.optString("imagePath").takeIf { it.isNotBlank() && it != "null" },
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
    private val processedCallbackIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

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

    @Volatile
    private var cachedGpu: String? = null

    fun getGpuInfo(): String {
        cachedGpu?.let { return it }
        return runCatching {
            val display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
            if (display != android.opengl.EGL14.EGL_NO_DISPLAY) {
                val vers = IntArray(2)
                if (android.opengl.EGL14.eglInitialize(display, vers, 0, vers, 1)) {
                    val configAttribs = intArrayOf(
                        android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                        android.opengl.EGL14.EGL_NONE
                    )
                    val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                    val numConfigs = IntArray(1)
                    if (android.opengl.EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
                        val contextAttribs = intArrayOf(
                            android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                            android.opengl.EGL14.EGL_NONE
                        )
                        val ctx = android.opengl.EGL14.eglCreateContext(display, configs[0], android.opengl.EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
                        if (ctx != android.opengl.EGL14.EGL_NO_CONTEXT) {
                            val surfaceAttribs = intArrayOf(
                                android.opengl.EGL14.EGL_WIDTH, 1,
                                android.opengl.EGL14.EGL_HEIGHT, 1,
                                android.opengl.EGL14.EGL_NONE
                            )
                            val surf = android.opengl.EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
                            if (surf != android.opengl.EGL14.EGL_NO_SURFACE) {
                                android.opengl.EGL14.eglMakeCurrent(display, surf, surf, ctx)
                                val renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER).orEmpty().trim()
                                android.opengl.EGL14.eglMakeCurrent(display, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
                                android.opengl.EGL14.eglDestroySurface(display, surf)
                                android.opengl.EGL14.eglDestroyContext(display, ctx)
                                android.opengl.EGL14.eglTerminate(display)
                                if (renderer.isNotBlank()) {
                                    cachedGpu = renderer
                                    return@runCatching renderer
                                }
                            }
                        }
                    }
                }
            }
            val board = Build.HARDWARE.orEmpty().trim()
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty().trim() else ""
            listOf(soc, board).firstOrNull { it.isNotBlank() } ?: "Adreno / Mali GPU"
        }.getOrDefault("Adreno / Mali GPU").also { cachedGpu = it }
    }

    private fun getBatteryDetails(context: Context): Triple<Int, String, Float> {
        return runCatching {
            val intent = ContextCompat.registerReceiver(
                context, null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 0

            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val chargeType = when {
                !isCharging -> "Discharging"
                plugged == BatteryManager.BATTERY_PLUGGED_AC -> "⚡ AC Fast"
                plugged == BatteryManager.BATTERY_PLUGGED_USB -> "⚡ USB"
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "⚡ Wireless"
                else -> "⚡ Charging"
            }

            val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val tempC = if (rawTemp > 0) rawTemp / 10f else 0f
            Triple(pct, chargeType, tempC)
        }.getOrDefault(Triple(0, "Discharging", 0f))
    }

    private fun getThermalStatusLabel(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return when (pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) {
                PowerManager.THERMAL_STATUS_NONE -> "🟢 Normal (Cool)"
                PowerManager.THERMAL_STATUS_LIGHT -> "🟡 Light Warm"
                PowerManager.THERMAL_STATUS_MODERATE -> "🟠 Moderate Warm"
                PowerManager.THERMAL_STATUS_SEVERE -> "🔴 High Thermal"
                PowerManager.THERMAL_STATUS_CRITICAL -> "🔥 Critical Heat"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "🚨 Emergency Heat"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "⚠️ Thermal Shutdown"
                else -> "🟢 Normal"
            }
        }
        return "🟢 Normal"
    }

    fun buildDeviceAuthHeader(context: Context): String {
        val uid = getUserUid(context)
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val brand = Build.BRAND.orEmpty().replaceFirstChar { it.uppercase() }
        val deviceTag = if (brand.isNotBlank() && !model.contains(brand, ignoreCase = true)) "$maker $model ($brand)" else "$maker $model"
        val androidVer = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val soc = NukeDeviceProfile.current().soc
        val gpu = getGpuInfo()

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val totalRam = String.format(Locale.US, "%.1f", (memInfo?.totalMem ?: 0L) / (1024.0 * 1024.0 * 1024.0))
        val freeRam = String.format(Locale.US, "%.1f", (memInfo?.availMem ?: 0L) / (1024.0 * 1024.0 * 1024.0))

        val (batPct, batStatus, batTemp) = getBatteryDetails(context)
        val batTempStr = if (batTemp > 0f) " • ${String.format(Locale.US, "%.1f", batTemp)}°C" else ""
        val thermal = getThermalStatusLabel(context)

        val runtimeState = NukeRuntimeState.state.value
        val activeGame = runtimeState.activePackage?.takeIf { it.isNotBlank() } ?: "Dashboard (Game Nuke)"
        val fpsVal = runtimeState.lastFps?.let { "${it.toInt()} FPS" } ?: "Standby"
        val hzVal = if (runtimeState.currentHz > 0) "${runtimeState.currentHz}Hz" else "Standard"

        val locale = Locale.getDefault()
        val country = "${locale.displayCountry} (${locale.country})".ifBlank { "Indonesia (ID)" }
        val timeFormatted = SimpleDateFormat("dd MMM, HH:mm:ss", locale).format(Date())
        val tz = java.util.TimeZone.getDefault().getDisplayName(false, java.util.TimeZone.SHORT, locale)

        val privilege = if (NukeConnectionManager.isConnected()) "⚡ ADB Shell Active" else "🔒 Local Non-Root"

        return """
            🎮 <b>GAME NUKE • LIVE SUPPORT</b>
            ━━━━━━━━━━━━━━━━━━━━━━━━━
            👤 <b>UID:</b> <code>#UID_$uid</code>
            📱 <b>Device:</b> <code>$deviceTag</code>
            🏷️ <b>OS:</b> <code>$androidVer</code>
            ⚙️ <b>SoC:</b> <code>$soc</code>
            🎮 <b>GPU:</b> <code>$gpu</code>
            ⚡ <b>RAM:</b> <code>${totalRam}GB Total │ ${freeRam}GB Free</code>
            🔋 <b>Battery:</b> <code>$batPct% ($batStatus)$batTempStr</code>
            🌡️ <b>Thermal:</b> <code>$thermal</code>
            🎯 <b>Target:</b> <code>$activeGame</code>
            📊 <b>FPS / Display:</b> <code>$fpsVal @ $hzVal</code>
            🌐 <b>Region:</b> <code>$country • $timeFormatted $tz</code>
            🛡️ <b>Privilege:</b> <code>$privilege</code>
            ━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent()
    }

    fun sendMessage(
        context: Context,
        rawText: String,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val text = rawText.trim()
        if (text.isBlank()) {
            onComplete?.invoke(false, "Message cannot be empty")
            return
        }

        val appContext = context.applicationContext
        val uid = getUserUid(appContext)
        val localMsg = NukeChatMessage(
            id = UUID.randomUUID().toString(),
            sender = "USER",
            senderName = "You",
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
                val fullPayload = "$header\n💬 <b>USER MESSAGE:</b>\n<blockquote>${htmlEscape(text)}</blockquote>\n\n<i>👉 Swipe this message to reply directly to user.</i>\n<i>#UID_$uid</i>"

                val inlineKeyboard = JSONArray().apply {
                    put(JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "⚡ Kirim Quick Command")
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
                        onComplete?.invoke(false, "Failed to send message to support server")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending telegram message", e)
                _messages.value = _messages.value.map {
                    if (it.id == localMsg.id) it.copy(status = "FAILED") else it
                }
                saveLocalHistory(appContext)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, e.message ?: "Connection error")
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    /**
     * Upload an image/screenshot report directly to Developer Telegram via sendPhoto multipart/form-data.
     * ZERO server required — hosted completely free by Telegram cloud!
     */
    fun sendPhotoMessage(
        context: Context,
        imageUri: Uri,
        captionText: String = "",
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val uid = getUserUid(appContext)
        val msgId = UUID.randomUUID().toString()

        val imagesDir = File(appContext.filesDir, "chat_images").apply { mkdirs() }
        val localFile = File(imagesDir, "img_$msgId.jpg")

        try {
            appContext.contentResolver.openInputStream(imageUri)?.use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Throwable) {
            onComplete?.invoke(false, "Failed to read image file: ${e.message}")
            return
        }

        val localMsg = NukeChatMessage(
            id = msgId,
            sender = "USER",
            senderName = "You",
            text = if (captionText.isNotBlank()) captionText else "📷 Attached Photo / Screenshot",
            timestamp = System.currentTimeMillis(),
            status = "SENDING",
            imagePath = localFile.absolutePath
        )

        _messages.value = _messages.value + localMsg
        saveLocalHistory(appContext)

        scope.launch {
            _isSending.value = true
            try {
                val header = buildDeviceAuthHeader(appContext)
                val fullCaption = "$header\n📷 <b>PHOTO / SCREENSHOT:</b>\n<blockquote>${htmlEscape(localMsg.text)}</blockquote>\n\n<i>👉 Swipe this message to reply directly to user.</i>\n<i>#UID_$uid</i>"

                val apiUrl = "https://api.telegram.org/bot$BOT_TOKEN/sendPhoto"
                val (success, response) = executeMultipartPhoto(apiUrl, ADMIN_CHAT_ID, fullCaption, localFile)
                if (success) {
                    val respObj = JSONObject(response)
                    val resultObj = respObj.optJSONObject("result")
                    val tgMsgId = resultObj?.optLong("message_id") ?: -1L
                    _messages.value = _messages.value.map {
                        if (it.id == localMsg.id) it.copy(status = "SENT", telegramMessageId = if (tgMsgId > 0) tgMsgId else null) else it
                    }
                    saveLocalHistory(appContext)
                    NukeLiveChatScheduler.setWaitingForReply(appContext, true)
                    withContext(Dispatchers.Main) { onComplete?.invoke(true, null) }
                } else {
                    _messages.value = _messages.value.map {
                        if (it.id == localMsg.id) it.copy(status = "FAILED") else it
                    }
                    saveLocalHistory(appContext)
                    withContext(Dispatchers.Main) { onComplete?.invoke(false, "Failed to upload photo to support bot") }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error sending photo to telegram", e)
                _messages.value = _messages.value.map {
                    if (it.id == localMsg.id) it.copy(status = "FAILED") else it
                }
                saveLocalHistory(appContext)
                withContext(Dispatchers.Main) { onComplete?.invoke(false, e.message ?: "Connection error") }
            } finally {
                _isSending.value = false
            }
        }
    }

    private fun executeMultipartPhoto(
        apiUrl: String,
        chatId: Long,
        caption: String,
        imageFile: File
    ): Pair<Boolean, String> {
        val boundary = "===NukeUpload" + System.currentTimeMillis() + "==="
        val lineFeed = "\r\n"
        val url = URL(apiUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            useCaches = false
            doOutput = true
            doInput = true
            requestMethod = "POST"
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "GameNuke-Client")
        }

        conn.outputStream.use { outputStream ->
            val writer = PrintWriter(OutputStreamWriter(outputStream, "UTF-8"), true)

            // chat_id
            writer.append("--$boundary").append(lineFeed)
            writer.append("Content-Disposition: form-data; name=\"chat_id\"").append(lineFeed)
            writer.append("Content-Type: text/plain; charset=UTF-8").append(lineFeed).append(lineFeed)
            writer.append(chatId.toString()).append(lineFeed).flush()

            // parse_mode
            writer.append("--$boundary").append(lineFeed)
            writer.append("Content-Disposition: form-data; name=\"parse_mode\"").append(lineFeed)
            writer.append("Content-Type: text/plain; charset=UTF-8").append(lineFeed).append(lineFeed)
            writer.append("HTML").append(lineFeed).flush()

            // caption
            writer.append("--$boundary").append(lineFeed)
            writer.append("Content-Disposition: form-data; name=\"caption\"").append(lineFeed)
            writer.append("Content-Type: text/plain; charset=UTF-8").append(lineFeed).append(lineFeed)
            writer.append(caption).append(lineFeed).flush()

            // photo
            writer.append("--$boundary").append(lineFeed)
            writer.append("Content-Disposition: form-data; name=\"photo\"; filename=\"${imageFile.name}\"").append(lineFeed)
            writer.append("Content-Type: image/jpeg").append(lineFeed).append(lineFeed).flush()

            imageFile.inputStream().use { input ->
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
            }

            writer.append(lineFeed).flush()
            writer.append("--$boundary--").append(lineFeed).flush()
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return (code in 200..299) to text
    }

    fun downloadTelegramPhoto(context: Context, fileId: String): String? {
        return try {
            val getFileUrl = "https://api.telegram.org/bot$BOT_TOKEN/getFile?file_id=$fileId"
            val fileJson = executeGet(getFileUrl)
            if (!fileJson.first || fileJson.second.isBlank()) return null
            val filePath = JSONObject(fileJson.second).optJSONObject("result")?.optString("file_path") ?: return null

            val downloadUrl = "https://api.telegram.org/file/bot$BOT_TOKEN/$filePath"
            val imagesDir = File(context.filesDir, "chat_images").apply { mkdirs() }
            val cleanId = fileId.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val targetFile = File(imagesDir, "dev_$cleanId.jpg")

            var curUrl = downloadUrl
            var redirectCount = 0
            var downloaded = false

            while (redirectCount < 5 && !downloaded) {
                val dlConn = (URL(curUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "GameNuke-Client/2.0")
                }
                val code = dlConn.responseCode
                if (code in 300..399) {
                    val loc = dlConn.getHeaderField("Location") ?: break
                    curUrl = loc
                    redirectCount++
                    continue
                }
                if (code in 200..299) {
                    dlConn.inputStream.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    downloaded = targetFile.exists() && targetFile.length() > 50L
                    break
                }
                break
            }

            if (downloaded) targetFile.absolutePath else null
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to download telegram photo: ${e.message}", e)
            null
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
            onComplete?.invoke(false, "Message cannot be empty")
            return
        }

        val appContext = context.applicationContext
        val target = _messages.value.find { it.id == localId }
        if (target == null) {
            onComplete?.invoke(false, "Message not found")
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
                    val fullPayload = "$header\n💬 <b>User Message:</b> <i>(edited)</i>\n<blockquote>${htmlEscape(trimmed)}</blockquote>\n━━━━━━━━━━━━━━━━━━━━\n<i>👉 Swipe this message to reply directly to user.</i>\n<i>#uid_$uid</i>"

                    val inlineKeyboard = JSONArray().apply {
                        put(JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "⚡ Kirim Quick Command")
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
            onComplete?.invoke(false, "Message not found")
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
                                    appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                        .edit().putLong(KEY_LAST_UPDATE_ID, lastUpdateId).apply()
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
                                        val replyText = (replyTo?.optString("text").takeIf { !it.isNullOrBlank() }
                                            ?: replyTo?.optString("caption")).orEmpty()
                                        val replyMsgId = replyTo?.optLong("message_id") ?: 0L
                                        val rawText = (msgObj.optString("text").takeIf { !it.isNullOrBlank() }
                                            ?: msgObj.optString("caption")).orEmpty()
                                        val devMsgId = msgObj.optLong("message_id")

                                        val photoArr = msgObj.optJSONArray("photo")
                                        val docObj = msgObj.optJSONObject("document")
                                        val isImageDoc = docObj?.optString("mime_type")?.startsWith("image/", ignoreCase = true) == true
                                        val targetMediaFileId = if (photoArr != null && photoArr.length() > 0) {
                                            photoArr.optJSONObject(photoArr.length() - 1)?.optString("file_id")
                                        } else if (isImageDoc) {
                                            docObj?.optString("file_id")
                                        } else null

                                        val hasMedia = !targetMediaFileId.isNullOrBlank()
                                        var downloadedPhotoPath: String? = null
                                        if (!targetMediaFileId.isNullOrBlank()) {
                                            downloadedPhotoPath = downloadTelegramPhoto(appContext, targetMediaFileId)
                                        }
                                        val devText = if (rawText.isBlank() && hasMedia) "📷 Foto dari Developer" else rawText

                                        // Precise Reply Routing:
                                        // 1. Developer tapped "Reply" (Balas) in Telegram to a message sent by THIS device
                                        val isReplyToMyMessage = replyMsgId > 0L && (currentList.any { it.telegramMessageId == replyMsgId } || _messages.value.any { it.telegramMessageId == replyMsgId })
                                        // 2. Or the replied-to message text/caption contains this device's specific UID
                                        val isReplyToMyUid = replyText.contains(myUid, ignoreCase = true)
                                        // 3. Or developer explicitly tagged this user UID in the message
                                        val isDirectMention = devText.contains(myUid, ignoreCase = true)
                                        // 4. Or developer sent a broadcast message to all users
                                        val isBroadcast = devText.startsWith("/broadcast ", ignoreCase = true) || devText.startsWith("/all ", ignoreCase = true)
                                        // 5. Or developer replied directly with image/text in private admin chat while active session waiting
                                        val isWaiting = NukeLiveChatScheduler.isWaitingForReply(appContext) || _messages.value.isNotEmpty()
                                        val isDirectInAdminChat = (replyMsgId == 0L && isWaiting)

                                        val isForMe = isReplyToMyMessage || isReplyToMyUid || isDirectMention || isBroadcast || isDirectInAdminChat

                                        if (isForMe && (devText.isNotBlank() || downloadedPhotoPath != null || hasMedia)) {
                                            val cleanText = when {
                                                devText.startsWith("/broadcast ", ignoreCase = true) -> devText.substring(11).trim()
                                                devText.startsWith("/all ", ignoreCase = true) -> devText.substring(5).trim()
                                                devText.contains(myUid, ignoreCase = true) -> devText.replace(Regex("(?i)#?UID_?$myUid"), "").trim()
                                                else -> devText
                                            }

                                            // Determine quoted user text if this is a reply
                                            var userQuotedSnippet: String? = null
                                            if (replyMsgId > 0L) {
                                                val matched = currentList.find { it.telegramMessageId == replyMsgId }
                                                if (matched != null) {
                                                    userQuotedSnippet = if (matched.text.isNotBlank()) matched.text else if (!matched.imagePath.isNullOrBlank()) "📷 Foto" else null
                                                }
                                            }
                                            if (userQuotedSnippet.isNullOrBlank() && replyText.isNotBlank()) {
                                                userQuotedSnippet = when {
                                                    replyText.contains("<blockquote>") -> replyText.substringAfter("<blockquote>").substringBefore("</blockquote>").trim()
                                                    replyText.contains("Pesan Pengguna:") -> replyText.substringAfter("Pesan Pengguna:").substringBefore("━━").trim()
                                                    replyText.contains("Pesan:") -> replyText.substringAfter("Pesan:").substringBefore("#uid").trim()
                                                    else -> null
                                                }
                                            }

                                            // Check if message already exists
                                            val existingIndex = currentList.indexOfFirst { it.telegramMessageId == devMsgId }
                                            if (existingIndex >= 0) {
                                                // Message was edited by developer in Telegram
                                                currentList[existingIndex] = currentList[existingIndex].copy(
                                                    text = cleanText,
                                                    isEdited = true,
                                                    replyToText = userQuotedSnippet ?: currentList[existingIndex].replyToText,
                                                    replyToSender = if (userQuotedSnippet != null) "Anda" else currentList[existingIndex].replyToSender,
                                                    imagePath = downloadedPhotoPath ?: currentList[existingIndex].imagePath
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
                                                        status = "SENT",
                                                        replyToText = userQuotedSnippet,
                                                        replyToSender = if (userQuotedSnippet != null) "Anda" else null,
                                                        imagePath = downloadedPhotoPath
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
                    if (_messages.value.isEmpty()) {
                        loadLocalHistory(appContext)
                    }
                    val currentList = _messages.value.toMutableList()

                    for (i in 0 until resultArray.length()) {
                        val item = resultArray.getJSONObject(i)
                        val updateId = item.optLong("update_id", 0L)
                        if (updateId > lastUpdateId) {
                            lastUpdateId = updateId
                            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putLong(KEY_LAST_UPDATE_ID, lastUpdateId).apply()
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
                                val replyText = (replyTo?.optString("text").takeIf { !it.isNullOrBlank() }
                                    ?: replyTo?.optString("caption")).orEmpty()
                                val replyMsgId = replyTo?.optLong("message_id") ?: 0L
                                val rawText = (msgObj.optString("text").takeIf { !it.isNullOrBlank() }
                                    ?: msgObj.optString("caption")).orEmpty()
                                val devMsgId = msgObj.optLong("message_id")

                                val photoArr = msgObj.optJSONArray("photo")
                                val docObj = msgObj.optJSONObject("document")
                                val isImageDoc = docObj?.optString("mime_type")?.startsWith("image/", ignoreCase = true) == true
                                val targetMediaFileId = if (photoArr != null && photoArr.length() > 0) {
                                    photoArr.optJSONObject(photoArr.length() - 1)?.optString("file_id")
                                } else if (isImageDoc) {
                                    docObj?.optString("file_id")
                                } else null

                                val hasMedia = !targetMediaFileId.isNullOrBlank()
                                var downloadedPhotoPath: String? = null
                                if (!targetMediaFileId.isNullOrBlank()) {
                                    downloadedPhotoPath = downloadTelegramPhoto(appContext, targetMediaFileId)
                                }
                                val devText = if (rawText.isBlank() && hasMedia) "📷 Foto dari Developer" else rawText

                                val isReplyToMyMessage = replyMsgId > 0L && (currentList.any { it.telegramMessageId == replyMsgId } || _messages.value.any { it.telegramMessageId == replyMsgId })
                                val isReplyToMyUid = replyText.contains(myUid, ignoreCase = true)
                                val isDirectMention = devText.contains(myUid, ignoreCase = true)
                                val isBroadcast = devText.startsWith("/broadcast ", ignoreCase = true) || devText.startsWith("/all ", ignoreCase = true)
                                val isWaiting = NukeLiveChatScheduler.isWaitingForReply(appContext) || _messages.value.isNotEmpty()
                                val isDirectInAdminChat = (replyMsgId == 0L && isWaiting)

                                val isForMe = isReplyToMyMessage || isReplyToMyUid || isDirectMention || isBroadcast || isDirectInAdminChat

                                if (isForMe && (devText.isNotBlank() || downloadedPhotoPath != null || hasMedia)) {
                                    val cleanText = when {
                                        devText.startsWith("/broadcast ", ignoreCase = true) -> devText.substring(11).trim()
                                        devText.startsWith("/all ", ignoreCase = true) -> devText.substring(5).trim()
                                        devText.contains(myUid, ignoreCase = true) -> devText.replace(Regex("(?i)#?UID_?$myUid"), "").trim()
                                        else -> devText
                                    }

                                    var userQuotedSnippet: String? = null
                                    if (replyMsgId > 0L) {
                                        val matched = currentList.find { it.telegramMessageId == replyMsgId }
                                        if (matched != null && matched.text.isNotBlank()) {
                                            userQuotedSnippet = matched.text
                                        }
                                    }
                                    if (userQuotedSnippet.isNullOrBlank() && replyText.isNotBlank()) {
                                        userQuotedSnippet = when {
                                            replyText.contains("<blockquote>") -> replyText.substringAfter("<blockquote>").substringBefore("</blockquote>").trim()
                                            replyText.contains("Pesan Pengguna:") -> replyText.substringAfter("Pesan Pengguna:").substringBefore("━━").trim()
                                            replyText.contains("Pesan:") -> replyText.substringAfter("Pesan:").substringBefore("#uid").trim()
                                            else -> null
                                        }
                                    }

                                    val existingIndex = currentList.indexOfFirst { it.telegramMessageId == devMsgId }
                                    if (existingIndex >= 0) {
                                        currentList[existingIndex] = currentList[existingIndex].copy(
                                            text = cleanText,
                                            isEdited = true,
                                            replyToText = userQuotedSnippet ?: currentList[existingIndex].replyToText,
                                            replyToSender = if (userQuotedSnippet != null) "Anda" else currentList[existingIndex].replyToSender,
                                            imagePath = downloadedPhotoPath ?: currentList[existingIndex].imagePath
                                        )
                                    } else {
                                        val newMsg = NukeChatMessage(
                                            id = UUID.randomUUID().toString(),
                                            sender = "DEV",
                                            senderName = "Agung Developer",
                                            text = cleanText,
                                            timestamp = msgObj.optLong("date", System.currentTimeMillis() / 1000) * 1000,
                                            telegramMessageId = devMsgId,
                                            status = "SENT",
                                            replyToText = userQuotedSnippet,
                                            replyToSender = if (userQuotedSnippet != null) "Anda" else null,
                                            imagePath = downloadedPhotoPath
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
        if (cbId.isBlank()) return
        if (!processedCallbackIds.add(cbId)) return
        if (processedCallbackIds.size > 200) {
            val it = processedCallbackIds.iterator()
            if (it.hasNext()) { it.next(); it.remove() }
        }

        val fromObj = cb.optJSONObject("from")
        val senderId = fromObj?.optLong("id") ?: 0L
        if (senderId != ADMIN_CHAT_ID) {
            answerCallbackQuery(cbId, "Akses ditolak.")
            return
        }

        val msgObj = cb.optJSONObject("message")
        val msgId = msgObj?.optLong("message_id") ?: 0L
        val chatId = msgObj?.optJSONObject("chat")?.optLong("id") ?: ADMIN_CHAT_ID

        val data = cb.optString("data", "")
        val targetUid = when {
            data.startsWith("cmd_") -> data.removePrefix("cmd_").trim()
            data.startsWith("closecmd_") -> data.removePrefix("closecmd_").trim()
            data.startsWith("customcmd_") -> data.removePrefix("customcmd_").trim()
            data.startsWith("exec_") -> data.removePrefix("exec_").split("_").getOrNull(0)?.trim().orEmpty()
            data.startsWith("reply_") -> data.removePrefix("reply_").trim()
            else -> ""
        }

        // Only process callbacks meant for this device instance
        if (targetUid.isNotBlank() && !targetUid.equals(myUid, ignoreCase = true)) {
            return
        }

        when {
            data.startsWith("reply_") -> {
                answerCallbackQuery(cbId, "👉 Swipe this message in Telegram to reply to #UID_$targetUid.")
            }
            data.startsWith("cmd_") -> {
                answerCallbackQuery(cbId, "⚡ Quick Command Menu opened.")
                if (msgId > 0L) {
                    updateTelegramMessageKeyboard(chatId, msgId, buildQuickCommandKeyboard(targetUid))
                }
            }
            data.startsWith("closecmd_") -> {
                answerCallbackQuery(cbId, "Quick Command Menu closed.")
                if (msgId > 0L) {
                    updateTelegramMessageKeyboard(chatId, msgId, buildDefaultKeyboard(targetUid))
                }
            }
            data.startsWith("customcmd_") -> {
                answerCallbackQuery(cbId, "Type /cmd <command> by replying to #UID_$targetUid", showAlert = true)
            }
            data.startsWith("exec_") -> {
                // Format: exec_{targetUid}_{action}
                val parts = data.removePrefix("exec_").split("_")
                if (parts.size >= 2) {
                    val action = parts[1]
                    val (cmdLabel, shellCmd) = when (action) {
                        "boost" -> "Nuke Max Boost" to "/cmd nuke --boost"
                        "cool" -> "Thermal Cool Down" to "/cmd dumpsys thermal"
                        "bat" -> "Battery Status" to "/cmd dumpsys battery"
                        "top" -> "Top Processes" to "/cmd top -n 1 -m 5"
                        else -> "Shell Command" to "/cmd uname -a"
                    }
                    answerCallbackQuery(cbId, "✅ $cmdLabel dikirim ke #UID_$targetUid\nCommand: $shellCmd", showAlert = true)
                    deliverDevCommandLocally(context, shellCmd)
                }
            }
        }
    }

    private fun answerCallbackQuery(cbId: String, text: String, showAlert: Boolean = false) {
        if (cbId.isBlank()) return
        scope.launch {
            val postBody = JSONObject().apply {
                put("callback_query_id", cbId)
                put("text", text)
                put("show_alert", showAlert)
            }.toString()
            executePost("https://api.telegram.org/bot$BOT_TOKEN/answerCallbackQuery", postBody)
        }
    }

    private fun updateTelegramMessageKeyboard(chatId: Long, messageId: Long, keyboard: JSONArray) {
        scope.launch {
            val postBody = JSONObject().apply {
                put("chat_id", chatId)
                put("message_id", messageId)
                put("reply_markup", JSONObject().put("inline_keyboard", keyboard))
            }.toString()
            executePost("https://api.telegram.org/bot$BOT_TOKEN/editMessageReplyMarkup", postBody)
        }
    }

    private fun buildQuickCommandKeyboard(targetUid: String): JSONArray = JSONArray().apply {
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
        put(JSONArray().apply {
            put(JSONObject().apply {
                put("text", "⬅️ Tutup Menu Command")
                put("callback_data", "closecmd_$targetUid")
            })
        })
    }

    private fun buildDefaultKeyboard(targetUid: String): JSONArray = JSONArray().apply {
        put(JSONArray().apply {
            put(JSONObject().apply {
                put("text", "⚡ Kirim Quick Command")
                put("callback_data", "cmd_$targetUid")
            })
        })
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
