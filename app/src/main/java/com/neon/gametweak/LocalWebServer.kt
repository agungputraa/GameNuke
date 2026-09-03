package com.neon.gametweak

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.math.roundToInt

/**
 * Local HTTP Web & REST API Server for Game Nuke.
 *
 * Provides a structured, secure, and complete REST API for companion apps, PC tools,
 * Web dashboards, and automation scripts running on the local device / network.
 *
 * Default Ports:
 *  - API Server: 8080 (REST endpoints & SSE stream)
 *  - Web UI:     8081 (Static Web dashboard from internal storage)
 */
class LocalWebServer private constructor(private val context: Context) {
    private var apiServerSocket: ServerSocket? = null
    private var webServerSocket: ServerSocket? = null

    var isApiRunning = false
        private set
    var isWebRunning = false
        private set

    private val startTimeMs = System.currentTimeMillis()

    private val coroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
            isApiRunning = false
            isWebRunning = false
        },
    )
    val documentRoot: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "WebUI").apply { mkdirs() }
    val apiPort = 8080
    val webPort = 8081

    private val backlog = 50

    companion object {
        @Volatile
        private var instance: LocalWebServer? = null
        fun getInstance(context: Context): LocalWebServer = instance ?: synchronized(this) {
            instance ?: LocalWebServer(context.applicationContext).also { instance = it }
        }
    }

    /** Returns true when the accept-loop socket has closed and the server needs a restart. */
    fun isApiSocketClosed(): Boolean = apiServerSocket == null || apiServerSocket!!.isClosed

    fun startApiServer() {
        if (isApiRunning && apiServerSocket != null && !apiServerSocket!!.isClosed) return
        isApiRunning = true
        // Ensure the foreground service is alive to keep the process alive.
        runCatching {
            val svcIntent = Intent(context, NukeWebServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        }
        coroutineScope.launch {
            // Self-healing loop: if the socket crashes, wait 2 s and retry.
            var retries = 0
            while (isApiRunning) {
                val socketOpened = runCatching {
                    apiServerSocket?.close()
                    apiServerSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(apiPort), backlog)
                    }
                }.isSuccess

                if (!socketOpened) {
                    retries++
                    if (retries > 5) { isApiRunning = false; break }
                    kotlinx.coroutines.delay(2_000L)
                    continue
                }
                retries = 0

                try {
                    while (isApiRunning) {
                        val client = apiServerSocket?.accept() ?: break
                        launch { handleApiRequestStream(client) }
                    }
                } catch (_: Exception) {
                    // Socket accept failed – outer loop will reopen.
                }

                if (isApiRunning) kotlinx.coroutines.delay(500L)
            }
            isApiRunning = false
        }
    }

    fun stopApiServer() {
        isApiRunning = false
        runCatching { apiServerSocket?.close() }
        apiServerSocket = null
        runCatching {
            context.stopService(Intent(context, NukeWebServerService::class.java))
        }
    }

    fun startWebServer() {
        if (isWebRunning) return
        isWebRunning = true
        coroutineScope.launch {
            try {
                webServerSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(webPort), backlog)
                }
                while (isWebRunning) {
                    val client = webServerSocket?.accept() ?: break
                    launch { handleStaticFileRequest(client) }
                }
            } catch (_: Exception) {
                isWebRunning = false
            }
        }
    }

    fun stopWebServer() {
        isWebRunning = false
        runCatching { webServerSocket?.close() }
        webServerSocket = null
    }

    private fun handleStaticFileRequest(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val out = client.getOutputStream()
            val requestLine = reader.readLine()?.trim() ?: return
            if (requestLine.isEmpty()) return

            while (true) {
                val headerLine = reader.readLine()
                if (headerLine.isNullOrEmpty()) break
            }

            val firstSpace = requestLine.indexOf(' ')
            val lastSpace = requestLine.lastIndexOf(' ')
            if (firstSpace <= 0 || lastSpace <= firstSpace) return
            val pathRaw = requestLine.substring(firstSpace + 1, lastSpace).trim()
            val path = runCatching { URLDecoder.decode(pathRaw.split("?")[0], "UTF-8") }.getOrDefault(pathRaw.split("?")[0])

            val root = runCatching { documentRoot.canonicalFile }.getOrElse { documentRoot.absoluteFile }
            val relativePath = if (path == "/") "index.html" else path.trimStart('/')
            val file = runCatching { File(root, relativePath).canonicalFile }.getOrNull()
            val insideRoot = file != null && (file == root || file.path.startsWith(root.path + File.separator))
            if (!insideRoot || file == null || !file.exists() || file.isDirectory) {
                val notFound = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n404 File Not Found"
                out.write(notFound.toByteArray())
                out.flush()
                return
            }

            val mimeType = when (file.extension.lowercase()) {
                "html" -> "text/html"
                "js" -> "application/javascript"
                "css" -> "text/css"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "svg" -> "image/svg+xml"
                "json" -> "application/json"
                else -> "application/octet-stream"
            }

            val header = "HTTP/1.1 200 OK\r\nContent-Type: $mimeType\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray())
            file.inputStream().use { it.copyTo(out) }
            out.flush()
        } catch (_: Exception) {
        } finally {
            if (!isWebRunning || !client.isClosed) {
                runCatching { client.close() }
            }
        }
    }

    private fun handleApiRequestStream(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val out = client.getOutputStream()
            val requestLine = reader.readLine()?.trim() ?: return
            if (requestLine.isEmpty()) return

            val headers = mutableMapOf<String, String>()
            while (true) {
                val headerLine = reader.readLine()
                if (headerLine.isNullOrEmpty()) break
                val idx = headerLine.indexOf(':')
                if (idx > 0) headers[headerLine.substring(0, idx).trim().lowercase()] = headerLine.substring(idx + 1).trim()
            }

            val firstSpace = requestLine.indexOf(' ')
            val lastSpace = requestLine.lastIndexOf(' ')
            if (firstSpace <= 0 || lastSpace <= firstSpace) return
            val method = requestLine.substring(0, firstSpace).uppercase()
            val pathRaw = requestLine.substring(firstSpace + 1, lastSpace).trim()
            val uriParts = pathRaw.split("?", limit = 2)
            val path = runCatching { URLDecoder.decode(uriParts[0], "UTF-8") }.getOrDefault(uriParts[0])
            val queryParams = parseQueryParams(uriParts.getOrNull(1))

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength in 1..1048576) {
                val charBuf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(charBuf, readTotal, contentLength - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                String(charBuf, 0, readTotal)
            } else ""
            val bodyParams = if (body.isNotBlank() && !body.trimStart().startsWith("{")) parseQueryParams(body) else emptyMap()

            fun getParam(name: String): String? {
                queryParams[name]?.let { if (it.isNotBlank()) return it }
                bodyParams[name]?.let { if (it.isNotBlank()) return it }
                if (body.isNotBlank()) {
                    val trimmed = body.trim()
                    if (trimmed.startsWith("{")) {
                        Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it }
                        Regex("\"$name\"\\s*:\\s*([0-9.]+)").find(trimmed)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it }
                        Regex("\"$name\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it }
                    }
                }
                return null
            }

            // Realtime SSE stream endpoint
            if (path == "/api/stream") {
                handleSseStream(client, out)
                return
            }

            val adb = AdbManager.getInstance(context)
            val isOnline = runCatching { NukeConnectionManager.isConnected() }.getOrDefault(false)
            val backend = runCatching { NukeConnectionManager.connectionLabel() }.getOrDefault("OFFLINE")

            when (path) {
                "/api", "/api/docs", "/api/endpoints" -> {
                    sendJsonResponse(out, buildApiDocumentationJson())
                }

                "/api/ping", "/api/health", "/api/status" -> {
                    val uptime = System.currentTimeMillis() - startTimeMs
                    val overlayActive = NukeRuntimeState.state.value.overlayRunning
                    val json = """
                        {
                            "status": "online",
                            "app": "Game Nuke",
                            "version": "${BuildConfig.VERSION_NAME}",
                            "version_code": ${BuildConfig.VERSION_CODE},
                            "backend": "$backend",
                            "backend_connected": $isOnline,
                            "overlay_active": $overlayActive,
                            "uptime_ms": $uptime,
                            "timestamp": ${System.currentTimeMillis()}
                        }
                    """.trimIndent()
                    sendJsonResponse(out, json)
                }

                "/api/telemetry" -> {
                    val telemetryJson = readTelemetryJson(adb, isOnline, backend)
                    sendJsonResponse(out, telemetryJson)
                }

                "/api/device" -> {
                    val deviceJson = """
                        {
                            "manufacturer": "${Build.MANUFACTURER}",
                            "brand": "${Build.BRAND}",
                            "model": "${Build.MODEL}",
                            "product": "${Build.PRODUCT}",
                            "device": "${Build.DEVICE}",
                            "board": "${Build.BOARD}",
                            "hardware": "${Build.HARDWARE}",
                            "android_version": "${Build.VERSION.RELEASE}",
                            "sdk_int": ${Build.VERSION.SDK_INT},
                            "supported_abis": [${Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }}]
                        }
                    """.trimIndent()
                    sendJsonResponse(out, deviceJson)
                }

                "/api/exec", "/api/shell" -> {
                    val cmd = getParam("cmd") ?: getParam("command") ?: if (body.isNotBlank() && !body.trimStart().startsWith("{")) body.trim() else null
                    if (cmd.isNullOrBlank()) {
                        sendJsonResponse(out, """{"success":false,"error":"Missing required 'cmd' parameter"}""", 400)
                        return
                    }
                    if (!isOnline) {
                        sendJsonResponse(out, """{"success":false,"error":"Privileged backend offline. Connect via Shizuku / Wireless ADB first."}""", 503)
                        return
                    }
                    val startExec = System.currentTimeMillis()
                    val cmdResult = adb.executeCommand(cmd, "/", 20_000L)
                    val execDuration = System.currentTimeMillis() - startExec
                    val safeOut = cmdResult.output.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                    val safeCmd = cmd.replace("\\", "\\\\").replace("\"", "\\\"")
                    sendJsonResponse(out, """
                        {
                            "success": ${cmdResult.isSuccess},
                            "command": "$safeCmd",
                            "exit_code": ${cmdResult.exitCode},
                            "output": "$safeOut",
                            "timed_out": ${cmdResult.timedOut},
                            "backend": "$backend",
                            "duration_ms": $execDuration
                        }
                    """.trimIndent())
                }

                "/api/overlay" -> {
                    val action = getParam("action")?.lowercase() ?: if (method == "POST") "toggle" else "status"
                    when (action) {
                        "start" -> {
                            if (!OverlayPermissionController.hasOverlayPermission(context)) {
                                sendJsonResponse(out, """{"success":false,"error":"Overlay permission (SYSTEM_ALERT_WINDOW) not granted"}""", 403)
                            } else {
                                val hudIntent = Intent(context, FloatingBoosterService::class.java).apply {
                                    this.action = FloatingBoosterService.ACTION_SHOW_OVERLAY
                                    putExtra(FloatingBoosterService.EXTRA_USER_REQUESTED, true)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(hudIntent) else context.startService(hudIntent)
                                sendJsonResponse(out, """{"success":true,"action":"start","overlay_active":true}""")
                            }
                        }
                        "stop" -> {
                            val hudIntent = Intent(context, FloatingBoosterService::class.java).apply {
                                this.action = FloatingBoosterService.ACTION_STOP_OVERLAY
                            }
                            context.startService(hudIntent)
                            sendJsonResponse(out, """{"success":true,"action":"stop","overlay_active":false}""")
                        }
                        "toggle" -> {
                            val isRunning = NukeRuntimeState.state.value.overlayRunning
                            if (isRunning) {
                                val hudIntent = Intent(context, FloatingBoosterService::class.java).apply {
                                    this.action = FloatingBoosterService.ACTION_STOP_OVERLAY
                                }
                                context.startService(hudIntent)
                                sendJsonResponse(out, """{"success":true,"action":"stopped","overlay_active":false}""")
                            } else {
                                if (!OverlayPermissionController.hasOverlayPermission(context)) {
                                    sendJsonResponse(out, """{"success":false,"error":"Overlay permission (SYSTEM_ALERT_WINDOW) not granted"}""", 403)
                                } else {
                                    val hudIntent = Intent(context, FloatingBoosterService::class.java).apply {
                                        this.action = FloatingBoosterService.ACTION_SHOW_OVERLAY
                                        putExtra(FloatingBoosterService.EXTRA_USER_REQUESTED, true)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(hudIntent) else context.startService(hudIntent)
                                    sendJsonResponse(out, """{"success":true,"action":"started","overlay_active":true}""")
                                }
                            }
                        }
                        else -> {
                            sendJsonResponse(out, """{"success":true,"overlay_active":${NukeRuntimeState.state.value.overlayRunning}}""")
                        }
                    }
                }

                "/api/thermal" -> {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        when (pm?.currentThermalStatus) {
                            PowerManager.THERMAL_STATUS_NONE -> "NONE"
                            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                            else -> "UNKNOWN"
                        }
                    } else "NOT_SUPPORTED"
                    val thermalCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm?.currentThermalStatus ?: -1 else -1
                    val isThrottling = thermalCode >= 2
                    val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
                    val tempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
                    sendJsonResponse(out, """
                        {
                            "thermal_status": "$thermalStatus",
                            "thermal_status_code": $thermalCode,
                            "battery_temperature_c": $tempC,
                            "is_throttling": $isThrottling
                        }
                    """.trimIndent())
                }

                "/api/packages/info" -> {
                    val targetPkg = getParam("package") ?: getParam("pkg")
                    if (targetPkg.isNullOrBlank()) {
                        sendJsonResponse(out, """{"success":false,"error":"Missing required 'package' parameter"}""", 400)
                        return
                    }
                    val pkm = context.packageManager
                    val pInfo = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pkm.getPackageInfo(targetPkg, PackageManager.PackageInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            pkm.getPackageInfo(targetPkg, 0)
                        }
                    }.getOrNull()
                    if (pInfo == null) {
                        sendJsonResponse(out, """{"success":false,"error":"Package '$targetPkg' not found"}""", 404)
                        return
                    }
                    val appInfo = pInfo.applicationInfo
                    val appName = appInfo?.let { pkm.getApplicationLabel(it).toString() } ?: targetPkg
                    val isSystem = (appInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                    val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appInfo?.category == ApplicationInfo.CATEGORY_GAME else false
                    val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
                    sendJsonResponse(out, """
                        {
                            "success": true,
                            "package": "$targetPkg",
                            "app_name": "${appName.replace("\"", "\\\"")}",
                            "version_name": "${pInfo.versionName ?: "unknown"}",
                            "version_code": $vCode,
                            "target_sdk": ${appInfo?.targetSdkVersion ?: 0},
                            "min_sdk": ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo?.minSdkVersion ?: 0 else 0},
                            "is_system": $isSystem,
                            "is_game": $isGame
                        }
                    """.trimIndent())
                }

                "/api/integrity", "/api/adblock" -> {
                    val disableParam = getParam("action") == "disable" || getParam("disable") == "true"
                    if (disableParam && isOnline) {
                        runCatching {
                            adb.executeCommand("settings put global private_dns_mode opportunistic")
                            adb.executeCommand("settings put global private_dns_specifier \"\"")
                        }
                    }
                    val check = NukeAdBlockDetector.checkStatus(context, adb)
                    sendJsonResponse(out, """
                        {
                            "is_detected": ${check.isDetected},
                            "detected_dns_specifier": "${check.detectedDnsSpecifier}",
                            "private_dns_mode": "${check.dnsMode}",
                            "reason": "${check.reason.replace("\"", "\\\"")}",
                            "core_tools_accessible": ${!check.isDetected}
                        }
                    """.trimIndent())
                }

                "/api/battery/profile" -> {
                    val mode = getParam("mode")?.lowercase()
                    if (mode != null && isOnline) {
                        when (mode) {
                            "performance" -> adb.executeCommand("settings put system min_refresh_rate 120; settings put system peak_refresh_rate 120")
                            "powersave" -> adb.executeCommand("settings put system min_refresh_rate 60; settings put system peak_refresh_rate 60")
                            "balanced" -> adb.executeCommand("settings put system min_refresh_rate 60; settings put system peak_refresh_rate 120")
                        }
                        sendJsonResponse(out, """{"success":true,"active_profile":"$mode"}""")
                    } else {
                        sendJsonResponse(out, """{"success":true,"supported_profiles":["performance","balanced","powersave"]}""")
                    }
                }

                "/api/toast", "/api/notify" -> {
                    val msg = getParam("message") ?: getParam("text") ?: getParam("msg") ?: if (body.isNotBlank() && !body.trimStart().startsWith("{")) body.trim() else "Game Nuke REST API Active"
                    coroutineScope.launch(Dispatchers.Main) {
                        NukeToast.success(context, msg)
                    }
                    sendJsonResponse(out, """{"success":true,"delivered":true,"message":"${msg.replace("\"", "\\\"")}"}""")
                }

                "/api/games" -> {
                    val scan = GameCatalogRepository.scan(context)
                    val games = scan.games
                    val gamesJson = games.joinToString(",") { game ->
                        """{"package":"${game.packageName}","title":"${game.label.replace("\"", "\\\"")}","detected_as_game":${game.detectedAsGame}}"""
                    }
                    sendJsonResponse(out, """{"count":${games.size},"games":[$gamesJson]}""")
                }

                "/api/games/launch" -> {
                    val pkg = getParam("package") ?: getParam("pkg")
                    if (pkg.isNullOrBlank()) {
                        sendJsonResponse(out, """{"success":false,"error":"Missing required 'package' parameter"}""", 400)
                        return
                    }
                    val launched = runCatching {
                        val launchIntent = GameCatalogRepository.resolveLaunchIntent(context, pkg)
                        if (launchIntent != null) {
                            context.startActivity(launchIntent)
                            true
                        } else {
                            val fallback = context.packageManager.getLaunchIntentForPackage(pkg)
                            if (fallback != null) {
                                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(fallback)
                                true
                            } else false
                        }
                    }.getOrDefault(false)

                    sendJsonResponse(out, """{"success":$launched,"package":"$pkg"}""")
                }

                "/api/modules" -> {
                    val repo = NukeModuleShopRepository(context, adb)
                    val shopState = repo.state.value
                    val modules = shopState.modules
                    val modulesJson = modules.joinToString(",") { mod ->
                        """
                        {
                            "id": "${mod.id}",
                            "name": "${mod.name.replace("\"", "\\\"")}",
                            "developer": "${mod.developer.replace("\"", "\\\"")}",
                            "version": "${mod.version}",
                            "category": "${mod.category}",
                            "description": "${mod.description.replace("\"", "\\\"")}",
                            "is_installed": ${shopState.installedIds.contains(mod.id)},
                            "is_active": ${shopState.activeIds.contains(mod.id)}
                        }
                        """.trimIndent()
                    }
                    sendJsonResponse(out, """{"modules": [$modulesJson], "count": ${modules.size}}""")
                }

                "/api/display" -> {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    @Suppress("DEPRECATION")
                    val display = wm?.defaultDisplay
                    val currentHz = runCatching { display?.refreshRate?.roundToInt() ?: 0 }.getOrDefault(0)
                    val maxHz = runCatching { display?.supportedModes?.maxOfOrNull { it.refreshRate }?.roundToInt() ?: currentHz }.getOrDefault(currentHz)
                    val modes = runCatching { display?.supportedModes?.map { it.refreshRate.roundToInt() }?.distinct()?.sorted() }.getOrNull() ?: listOf(currentHz)
                    val modesJson = modes.joinToString(",") { "$it" }

                    val sizeRes = if (isOnline) runCatching { adb.executeCommand("wm size") }.getOrNull()?.output?.trim() else null
                    val densityRes = if (isOnline) runCatching { adb.executeCommand("wm density") }.getOrNull()?.output?.trim() else null

                    sendJsonResponse(out, """
                        {
                            "current_hz": $currentHz,
                            "max_hz": $maxHz,
                            "supported_hz": [$modesJson],
                            "display_size": "${sizeRes?.replace("\"", "\\\"") ?: "standard"}",
                            "display_density": "${densityRes?.replace("\"", "\\\"") ?: "standard"}"
                        }
                    """.trimIndent())
                }

                "/api/display/fps" -> {
                    val hzStr = getParam("hz") ?: getParam("fps")
                    val hz = hzStr?.toIntOrNull()
                    if (hz == null || hz !in 30..240) {
                        sendJsonResponse(out, """{"success":false,"error":"Invalid or missing 'hz' parameter (allowed: 30..240)"}""", 400)
                        return
                    }
                    if (!isOnline) {
                        sendJsonResponse(out, """{"success":false,"error":"Privileged backend offline"}""", 503)
                        return
                    }
                    val r1 = adb.executeCommand("settings put system min_refresh_rate $hz")
                    val r2 = adb.executeCommand("settings put system peak_refresh_rate $hz")
                    sendJsonResponse(out, """{"success":${r1.isSuccess && r2.isSuccess},"target_hz":$hz}""")
                }

                "/api/boost" -> {
                    val result = if (isOnline) {
                        adb.executeCommand("am compact system", "/", 10_000L)
                    } else null
                    sendJsonResponse(out, """{"success":true,"action":"boost_completed","privileged":${result?.isSuccess == true}}""")
                }

                "/api/cleaner/trim" -> {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val memBefore = ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
                    val result = if (isOnline) adb.executeCommand("am compact system", "/", 10_000L) else null
                    val memAfter = ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
                    sendJsonResponse(out, """
                        {
                            "success": true,
                            "privileged": ${result?.isSuccess == true},
                            "ram_freed_mb": ${((memAfter.availMem - memBefore.availMem) / (1024 * 1024)).coerceAtLeast(0L)}
                        }
                    """.trimIndent())
                }

                "/api/dnd" -> {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    val hasPolicy = nm?.isNotificationPolicyAccessGranted == true
                    val currentInterruptionFilter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
                    val isDndActive = currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

                    if (method == "POST" || queryParams.containsKey("enable")) {
                        val enable = getParam("enable")?.toBooleanStrictOrNull() ?: true
                        if (hasPolicy) {
                            val filter = if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
                            nm?.setInterruptionFilter(filter)
                            sendJsonResponse(out, """{"success":true,"dnd_enabled":$enable}""")
                        } else {
                            sendJsonResponse(out, """{"success":false,"error":"DND policy access not granted in system settings"}""", 403)
                        }
                    } else {
                        sendJsonResponse(out, """{"dnd_active":$isDndActive,"permission_granted":$hasPolicy}""")
                    }
                }

                "/api/processes" -> {
                    if (!isOnline) {
                        sendJsonResponse(out, """{"error":"Privileged backend offline"}""", 503)
                        return
                    }
                    val result = adb.executeCommand("ps -A -o PID,RSS,NAME", "/", 5_000L, 65536)
                    if (!result.isSuccess) {
                        sendJsonResponse(out, """{"error":"Process query failed"}""", 500)
                        return
                    }
                    val jsonArray = StringBuilder("[")
                    var first = true
                    result.output.lineSequence().drop(1).forEach { row ->
                        val pParts = row.trim().split(Regex("\\s+"))
                        if (pParts.size >= 3) {
                            val pid = pParts[0]
                            val rss = pParts[1].toLongOrNull() ?: 0L
                            val name = pParts[2]
                            val memMb = rss / 1024
                            if (!name.contains("com.neon.gametweak") && !name.contains("ps") && !name.contains("sh") && memMb > 0) {
                                if (!first) jsonArray.append(",")
                                val safeName = name.replace("\\", "\\\\").replace("\"", "\\\"")
                                jsonArray.append("""{"pid":$pid,"memory_mb":$memMb,"name":"$safeName"}""")
                                first = false
                            }
                        }
                    }
                    jsonArray.append("]")
                    sendJsonResponse(out, """{"count":${if (first) 0 else 1},"processes":$jsonArray}""")
                }

                else -> {
                    sendJsonResponse(out, """{"error":"Endpoint not found. Visit GET /api/docs for API catalog."}""", 404)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    private fun handleSseStream(client: Socket, out: OutputStream) {
        try {
            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
            out.write(header.toByteArray())
            out.flush()

            val adb = AdbManager.getInstance(context)
            while (isApiRunning && !client.isClosed) {
                val isOnline = runCatching { NukeConnectionManager.isConnected() }.getOrDefault(false)
                val backend = runCatching { NukeConnectionManager.connectionLabel() }.getOrDefault("OFFLINE")
                val json = readTelemetryJson(adb, isOnline, backend)
                out.write(("data: $json\n\n").toByteArray())
                out.flush()
                Thread.sleep(1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun readTelemetryJson(adb: AdbManager, isOnline: Boolean, backend: String): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memInfo) }
        val totalRamMb = (memInfo.totalMem / 1_048_576L).coerceAtLeast(0L)
        val availRamMb = (memInfo.availMem / 1_048_576L).coerceAtLeast(0L)
        val usedRamMb = (totalRamMb - availRamMb).coerceAtLeast(0L)
        val ramUsedPercent = if (totalRamMb > 0L) ((usedRamMb.toDouble() / totalRamMb.toDouble()) * 100.0).roundToInt().coerceIn(0, 100) else 0

        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val storageFreeMb = (stat?.availableBytes ?: 0L) / 1_048_576L
        val storageTotalMb = (stat?.totalBytes ?: 0L) / 1_048_576L

        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val batteryLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val temperatureC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val isCharging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
        val voltageMv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        val display = wm?.defaultDisplay
        val currentHz = runCatching { display?.refreshRate?.roundToInt() ?: 0 }.getOrDefault(0)
        val maxHz = runCatching { display?.supportedModes?.maxOfOrNull { it.refreshRate }?.roundToInt() ?: currentHz }.getOrDefault(currentHz)

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkLabel = runCatching {
            val cm = connectivity ?: return@runCatching "OFFLINE"
            val network = cm.activeNetwork ?: return@runCatching "OFFLINE"
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching "ONLINE"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "LAN"
                else -> "ONLINE"
            }
        }.getOrDefault("OFFLINE")

        val gateway = NukeGamingShellGateway(adb)
        val cpuLoad = runCatching { if (isOnline) gateway.readCpuLoadPercent() else null }.getOrNull() ?: NukeLocalCpuSampler.readPercent()

        return """
            {
                "cpu_load_percent": $cpuLoad,
                "ram": {
                    "total_mb": $totalRamMb,
                    "used_mb": $usedRamMb,
                    "available_mb": $availRamMb,
                    "used_percent": $ramUsedPercent
                },
                "storage": {
                    "total_mb": $storageTotalMb,
                    "available_mb": $storageFreeMb
                },
                "battery": {
                    "level_percent": $batteryLevel,
                    "temperature_c": $temperatureC,
                    "is_charging": $isCharging,
                    "voltage_mv": $voltageMv
                },
                "display": {
                    "current_hz": $currentHz,
                    "max_hz": $maxHz
                },
                "network": {
                    "type": "$networkLabel"
                },
                "backend": "$backend",
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()
    }

    private fun buildApiDocumentationJson(): String = """
        {
            "openapi": "3.0.0",
            "info": {
                "title": "Game Nuke Local REST API",
                "version": "${BuildConfig.VERSION_NAME}",
                "description": "Structured local REST API for real-time telemetry, privileged shell execution, game launching, display controls, and memory optimization."
            },
            "endpoints": [
                { "path": "/api/status", "methods": ["GET"], "description": "Returns system health, app version, backend status, and uptime." },
                { "path": "/api/telemetry", "methods": ["GET"], "description": "Real-time hardware metrics: CPU load, RAM usage, storage, battery, display refresh rate." },
                { "path": "/api/stream", "methods": ["GET"], "description": "Server-Sent Events (SSE) live telemetry stream (1 Hz update rate)." },
                { "path": "/api/exec?cmd={command}", "methods": ["POST", "GET"], "description": "Execute a shell command with elevated privileges (Shizuku / Wireless ADB) and return exit code, output, and execution time." },
                { "path": "/api/device", "methods": ["GET"], "description": "Hardware and OS specifications (Model, Brand, Android Version, SDK, ABIs)." },
                { "path": "/api/thermal", "methods": ["GET"], "description": "Hardware thermal throttling status, thermal headroom, and battery temperature." },
                { "path": "/api/packages/info?package={pkg}", "methods": ["GET"], "description": "Inspect package details: version, targetSdk, system status, and game classification." },
                { "path": "/api/overlay?action={start|stop|toggle|status}", "methods": ["POST", "GET"], "description": "Control or inspect the floating Gaming Cockpit HUD overlay." },
                { "path": "/api/integrity?action={check|disable}", "methods": ["POST", "GET"], "description": "Check network integrity and resolve Private DNS ad-blocking." },
                { "path": "/api/battery/profile?mode={performance|balanced|powersave}", "methods": ["POST", "GET"], "description": "Get or apply system power and refresh rate profiles." },
                { "path": "/api/toast?message={text}", "methods": ["POST", "GET"], "description": "Dispatch a local toast notification directly on the device." },
                { "path": "/api/games", "methods": ["GET"], "description": "List all discovered games and installed gaming apps." },
                { "path": "/api/modules", "methods": ["GET"], "description": "List all community performance modules and their active state." },
                { "path": "/api/games/launch?package={pkg}", "methods": ["POST", "GET"], "description": "Launch a specific game session with gaming HUD." },
                { "path": "/api/display", "methods": ["GET"], "description": "Get display refresh rate capabilities, resolution, and density." },
                { "path": "/api/display/fps?hz={30..240}", "methods": ["POST", "GET"], "description": "Set active display refresh rate." },
                { "path": "/api/boost", "methods": ["POST", "GET"], "description": "Perform memory optimization and game session prep." },
                { "path": "/api/cleaner/trim", "methods": ["POST", "GET"], "description": "Trigger system memory compaction and cache trim." },
                { "path": "/api/dnd?enable={true|false}", "methods": ["POST", "GET"], "description": "Check or set Game Do-Not-Disturb (DND) focus mode." },
                { "path": "/api/processes", "methods": ["GET"], "description": "Query active user process list and memory consumption." }
            ]
        }
    """.trimIndent()

    private fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (param in query.split("&")) {
            val pair = param.split("=", limit = 2)
            if (pair.isNotEmpty()) {
                val key = runCatching { URLDecoder.decode(pair[0], "UTF-8") }.getOrDefault(pair[0]).trim()
                val rawVal = if (pair.size > 1) pair[1] else ""
                val value = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal).trim()
                if (key.isNotEmpty()) result[key] = value
            }
        }
        return result
    }

    private fun sendJsonResponse(out: OutputStream, json: String, statusCode: Int = 200) {
        val statusMsg = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val response = "HTTP/1.1 $statusCode $statusMsg\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: *\r\nConnection: close\r\n\r\n$json"
        out.write(response.toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
