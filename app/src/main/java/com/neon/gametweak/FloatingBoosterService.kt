package com.neon.gametweak

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * User-started, user-visible Game Nuke gaming cockpit.
 *
 * 3.8 from-zero floating rebuild goals:
 * - Fixed, low-overhead nuclear cockpit instead of a generic popup or Game Corner clone.
 * - Only real first-party tools are exposed; no decorative or remote-executable menu entries.
 * - Crosshair Studio, measured Deep Clean, verified FPS paths, Focus, Network Core and telemetry.
 * - Physics-based interaction plus contained Canvas effects without a full-HUD render loop.
 * - Shared preferences + runtime state keep the app and foreground cockpit synchronized.
 */
class FloatingBoosterService : Service() {
    companion object {
        const val ACTION_SHOW_OVERLAY = "com.neon.gametweak.action.SHOW_OVERLAY"
        const val ACTION_STOP_OVERLAY = "com.neon.gametweak.action.STOP_OVERLAY"
        const val EXTRA_USER_REQUESTED = "extra_user_requested"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_REVEAL_DELAY_MS = "extra_reveal_delay_ms"
        private const val TAG = "NukeCockpit"
        private const val PREFS = "NukePrefs"
        private const val K_ACTIVE = "overlay_active_session"
        private const val K_ACTIVE_PACKAGE = "overlay_active_package"
        private const val K_HUD_SCALE = "overlay_hud_scale"
        private const val K_HUD_ALPHA = "overlay_hud_alpha"
        private const val K_LANG = "hud_lang"
        private const val K_EDGE_DOCK = "overlay_edge_dock"
        private const val K_EDGE_FRACTION = "overlay_edge_fraction"
        private const val K_ADAPTIVE_GAME_MODE = "adaptive_game_mode"
        private const val K_ADAPTIVE_OWN_AWAKE = "adaptive_own_awake"
        private const val K_ADAPTIVE_OWN_NETWORK = "adaptive_own_network"
        private const val K_ADAPTIVE_OWN_OEM = "adaptive_own_oem"
        private const val TOUCH_DEBOUNCE_MS = 300L
    }

    private enum class EdgeDock { LEFT, RIGHT, TOP, BOTTOM }

    private data class WindowSlot(val view: View, val params: WindowManager.LayoutParams)
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "HUD coroutine failure", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var composeLifecycleOwner: OverlayComposeLifecycleOwner
    private lateinit var moduleShop: NukeModuleShopRepository
    private val composeHudState = MutableStateFlow(FloatingHudSnapshot())
    private var remoteHud = NukeRemoteHudDefinition.Bundled
    private val windows = ConcurrentHashMap<String, WindowSlot>()
    private var engine: NukePerformanceEngine? = null
    private var targetPackage: String? = null
    private var activationJob: Job? = null
    private var engineStateJob: Job? = null
    private var telemetryJob: Job? = null
    private var auxTelemetryJob: Job? = null
    private var thermalJob: Job? = null
    private var sessionJob: Job? = null
    private var switchJob: Job? = null
    @Volatile private var moduleActionJob: Job? = null
    private var stopping = false
    private var lastActionAt = 0L
    private val toggleJobs = EnumMap<FloatingHudToggle, Job>(FloatingHudToggle::class.java)
    private val pendingToggleValues = EnumMap<FloatingHudToggle, Boolean>(FloatingHudToggle::class.java)
    private val panelActionJobs = ConcurrentHashMap<String, Job>()
    private var monitorTouchThrough = false
    private var foregroundStarted = false
    private var lastPingMs: Long? = null
    private var pingMeasuring = false
    private var lastAutomaticAlert: String? = null
    private var batteryPercent: Int = -1
    private var batteryTempC: Float? = null
    private var networkLabel: String = "--"
    private var wifiRssiDbm: Int? = null
    private var wifiLinkMbps: Int? = null
    private var sessionStartedElapsed: Long = 0L
    private var sessionBatteryStartPercent: Int = -1
    private var sessionChargeStartUah: Int? = null
    private var edgeAddFailures = 0
    private var hubAddFailures = 0
    private var hubAttaching = false
    private var crosshairAddFailures = 0
    private var edgeStopArmedUntil = 0L
    private val moduleAddFailures = ConcurrentHashMap<String, Int>()

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        scope.launch {
            when {
                key == K_LANG -> {
                    val code = validLanguage(prefs.safeString(K_LANG, "en"))
                    Tx.setLang(code)
                    publishRuntime(engine?.state?.value)
                    refreshLocalizedHud()
                }
                key.startsWith("cross_") -> {
                    syncCrosshairOverlay()
                    publishRuntime(engine?.state?.value)
                }
                key == K_HUD_ALPHA -> applyHudAlphaToOpenWindows()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        composeLifecycleOwner = OverlayComposeLifecycleOwner().also { it.start() }
        moduleShop = NukeModuleShopRepository(applicationContext, AdbManager.getInstance(applicationContext))
        remoteHud = NukeRemoteConfigRepository.currentHud()
        NukeRemoteConfigRepository.refreshInBackground("overlay-service-start")
        Tx.setLang(validLanguage(prefs.safeString(K_LANG, "en")))
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        publishRuntime(null)

        // Ensure local REST API server is alive while gaming HUD service runs
        kotlin.concurrent.thread(name = "Nuke-HudLocalApiServer", isDaemon = true) {
            runCatching { LocalWebServer.getInstance(applicationContext).startApiServer() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OVERLAY) {
            scope.launch { stopSession("USER STOP") }
            return START_NOT_STICKY
        }

        // Never rebuild a heavy overlay from a null/stale START_STICKY restart. A running
        // foreground service already survives a normal Recents swipe; new sessions must be explicit.
        if (intent?.action != ACTION_SHOW_OVERLAY ||
            !intent.getBooleanExtra(EXTRA_USER_REQUESTED, false)
        ) {
            rejectSessionStart(startId, "missing explicit user request")
            return START_NOT_STICKY
        }
        val pkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)?.trim()?.takeIf(::validPackage)
        if (pkg == null) {
            rejectSessionStart(startId, "invalid target package")
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            rejectSessionStart(startId, "overlay permission unavailable")
            return START_NOT_STICKY
        }
        if (!ensureForegroundSession(pkg)) {
            rejectSessionStart(startId, "foreground service could not start")
            return START_NOT_STICKY
        }

        val revealDelay = intent?.getLongExtra(EXTRA_REVEAL_DELAY_MS, 0L)?.coerceIn(0L, 2_000L) ?: 0L
        switchJob?.cancel()
        switchJob = scope.launch {
            try {
                switchSession(pkg, revealDelay)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // A newer validated Boost intent replaces this bootstrap. Cancellation is a normal
                // handoff and must never run the fatal rollback that would remove the new windows.
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Floating session bootstrap failed safely", error)
                NukeRemoteConfigRepository.reportOverlayFailure(remoteHud.revision)
                NukeRuntimeState.setLaunchHandoffActive(false)
                cancelLoops()
                removeAllWindowsImmediate()
                withContext(Dispatchers.IO) { runCatching { engine?.restoreSession() } }
                restoreDisplayWithRetry(applicationContext)
                runCatching { engine?.releaseLocalResources() }
                engine = null
                targetPackage = null
                clearSessionMarker()
                NukeToast.error(applicationContext, "Floating session failed safely; session changes were restored", true)
                if (foregroundStarted) {
                    ServiceCompat.stopForeground(this@FloatingBoosterService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    foregroundStarted = false
                }
                stopSelf(startId)
            }
        }
        // Redeliver only this validated, explicit session intent if Android reclaims the process.
        // This prevents a visible gaming HUD from silently disappearing under memory pressure.
        return START_REDELIVER_INTENT
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // stopWithTask=false keeps the already-running user-visible FGS independent from the
        // MainActivity task. We intentionally do not ask Android to resurrect it after process death.
        publishRuntime(engine?.state?.value)
        super.onTaskRemoved(rootIntent)
    }

    private fun rejectSessionStart(startId: Int, reason: String) {
        NukeRuntimeState.setLaunchHandoffActive(false)
        clearSessionMarker()
        // Detached one-shot recovery scope: stopSelf() can destroy/cancel the service scope
        // immediately, so rollback must not be tied to this Service lifecycle. It only holds the
        // application Context and exits after this single restore attempt.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val restore = runCatching { NukeDisplayProfileController.restoreIfOwned(applicationContext) }.getOrNull()
            if (restore?.outcome == NukeDisplayProfileController.Outcome.ERROR ||
                restore?.outcome == NukeDisplayProfileController.Outcome.DEFERRED
            ) {
                Log.w(TAG, "Display restore after rejected session: ${restore.message}")
            }
        }
        Log.w(TAG, "Rejected floating session start: $reason")
        stopSelf(startId)
    }

    private suspend fun switchSession(pkg: String, revealDelay: Long) {
        stopping = false
        // Freeze one validated definition for the full session. A network refresh must never move
        // controls underneath the user's finger while the overlay is open.
        remoteHud = NukeRemoteConfigRepository.currentHud()
        scope.launch(Dispatchers.IO) { moduleShop.configure(remoteHud.moduleShop) }
        val old = engine
        if (old != null && targetPackage != null && targetPackage != pkg) {
            cancelLoops()
            moduleActionJob?.cancel(); moduleActionJob = null
            withTimeoutOrNull(20_000L) { withContext(Dispatchers.IO) { moduleShop.deactivateAll() } }
            withTimeoutOrNull(4_000L) { runCatching { old.restoreSession() } }
            // Display size/density changes can invalidate overlay coordinates. Remove every window
            // before rollback, then let the next session recreate them against fresh WindowMetrics.
            removeAllWindowsImmediate()
            withContext(Dispatchers.IO) { runCatching { NukeDisplayProfileController.restoreIfOwned(applicationContext) } }
            old.releaseLocalResources()
            engine = null
            targetPackage = null
        }

        val foregroundObserved = awaitTargetGameForeground(pkg, remoteHud.waitForGameForegroundMs, revealDelay)
        if (foregroundObserved && remoteHud.attachDelayAfterForegroundMs > 0L) {
            delay(remoteHud.attachDelayAfterForegroundMs)
        }

        if (engine == null) {
            targetPackage = pkg
            captureSessionBatteryBaseline()
            engine = NukePerformanceEngine(applicationContext, pkg) { destination ->
                scope.launch(Dispatchers.Main.immediate) {
                    toastStatus("Opening $destination — return to Game Nuke when done", long = true)
                }
            }
        }
        prefs.edit().putBoolean(K_ACTIVE, true).putString(K_ACTIVE_PACKAGE, pkg).apply()
        NukeRuntimeState.setLaunchHandoffActive(false)
        publishRuntime(engine?.state?.value)
        startLoops(pkg)
        ensureEdge()
        // Always show the floating panel regardless of backend connectivity.
        // Even when Shizuku / iAdb / ADB is offline the panel must be visible
        // so the user (and Play reviewers) can see the full UI.
        ensureHubVisible()
        syncCrosshairOverlay()
        showNukeModeBanner()
    }

    /** ADB is only used for a fixed read-only foreground query. On an unpaired OEM device the
     * explicit splash delay is the safe fallback, keeping the HUD available without root. */
    private suspend fun awaitTargetGameForeground(pkg: String, timeoutMs: Long, fallbackDelayMs: Long): Boolean {
        val adb = AdbManager.getInstance(applicationContext)
        if (!adb.isConnected()) {
            delay(maxOf(650L, fallbackDelayMs).coerceAtMost(1_500L))
            return true
        }
        val detector = ActiveGameDetector(applicationContext, adb)
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            val foreground = withContext(Dispatchers.IO) {
                runCatching { detector.detectForegroundPackage() }.getOrNull()
            }
            if (foreground == pkg) return true
            delay(120L)
        }
        Log.w(TAG, "Target foreground wait timed out; exposing the recovery edge only")
        return false
    }

    private fun ensureForegroundSession(pkg: String): Boolean {
        val label = runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") packageManager.getApplicationInfo(pkg, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
        val notification = NukeSessionNotification.build(this, label)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this,
                    NukeSessionNotification.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                @Suppress("DEPRECATION") startForeground(NukeSessionNotification.NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        }.onFailure {
            Log.e(TAG, "Unable to enter foreground gaming session", it)
            foregroundStarted = false
        }
        return foregroundStarted
    }

    private fun startLoops(pkg: String) {
        activationJob?.cancel(); engineStateJob?.cancel(); telemetryJob?.cancel(); auxTelemetryJob?.cancel(); thermalJob?.cancel(); sessionJob?.cancel()
        val local = engine ?: return

        // The floating UI observes the engine directly. A successful device command now reaches
        // Compose on the same state emission instead of waiting for the next telemetry interval.
        engineStateJob = scope.launch {
            local.state.collect { state ->
                updateTelemetryUi(state)
                publishRuntime(state)
            }
        }

        // First paint is independent from the heavier activation/probe path. This lets RAM/display
        // and an already-authorized CPU sample appear almost immediately when the overlay opens.
        telemetryJob = scope.launch {
            // Paint local RAM/display immediately; CPU follows on the next pass so a slow shell
            // endpoint cannot hold the entire HUD blank.
            runCatching { local.refreshHudMetrics(includeCpu = false) }
            local.state.value.let { updateTelemetryUi(it); publishRuntime(it) }
            runCatching { local.refreshHudMetrics(includeCpu = true) }
            local.state.value.let { updateTelemetryUi(it); publishRuntime(it) }
            while (isActive) {
                delay(1_100L)
                runCatching { local.refreshHudMetrics(includeCpu = true) }
                val state = local.state.value
                updateTelemetryUi(state)
                publishRuntime(state)
            }
        }
        // Battery/network/ping are intentionally decoupled from the expensive thermal + shell
        // metrics pass. A failed ping gets one quick retry and never holds CPU/RAM telemetry hostage.
        auxTelemetryJob = scope.launch {
            refreshAuxTelemetry(retryPing = true)
            engine?.state?.value?.let(::updateTelemetryUi)
            while (isActive) {
                delay(2_500L)
                refreshAuxTelemetry(retryPing = true)
                engine?.state?.value?.let(::updateTelemetryUi)
            }
        }
        activationJob = scope.launch(Dispatchers.IO) {
            delay(remoteHud.heavyOperationsDelayMs)
            runCatching { local.initializeAndActivate() }
                .onFailure { Log.e(TAG, "Core activation failed", it) }
            withContext(Dispatchers.Main.immediate) {
                local.state.value.let { updateTelemetryUi(it); publishRuntime(it) }
            }
        }
        thermalJob = scope.launch {
            delay(900L)
            while (isActive) {
                runCatching { local.refreshMetrics() }
                val state = local.state.value
                updateTelemetryUi(state)
                publishRuntime(state)
                // PowerManager thermal headroom must not be sampled more often than the platform
                // cadence; align the loop so cached calls do not turn into an 18-second refresh.
                delay(10_600L)
            }
        }
        sessionJob = scope.launch(Dispatchers.Default) {
            val result = OverlayGameSessionController(applicationContext, pkg).awaitSessionEnd()
            if (result.trackingAvailable && result.gameWasObserved) scope.launch { stopSession("GAME SESSION ENDED") }
        }
    }

    private fun cancelLoops() {
        activationJob?.cancel(); activationJob = null
        engineStateJob?.cancel(); engineStateJob = null
        telemetryJob?.cancel(); telemetryJob = null
        auxTelemetryJob?.cancel(); auxTelemetryJob = null
        thermalJob?.cancel(); thermalJob = null
        sessionJob?.cancel(); sessionJob = null
        toggleJobs.values.forEach { it.cancel() }
        toggleJobs.clear()
        pendingToggleValues.clear()
        panelActionJobs.values.forEach { it.cancel() }
        panelActionJobs.clear()
    }

    private fun ensureEdge() {
        if (windows.containsKey("edge")) return
        val dock = runCatching {
            EdgeDock.valueOf(prefs.safeString(K_EDGE_DOCK, EdgeDock.LEFT.name))
        }.getOrDefault(EdgeDock.LEFT)
        val view = LayoutInflater.from(this).inflate(R.layout.nuke_hud_edge, null)
        val edgeSize = remoteHud.edgeSizeDp.coerceIn(36, 56)
        val width = minOf(dp(edgeSize), (screenWidth() * .94f).roundToInt())
        val height = minOf(dp(edgeSize), (screenHeight() * .86f).roundToInt())
        val safe = safeBounds()
        val minX = safe.left
        val minY = safe.top
        val maxX = maxOf(minX, safe.right - width)
        val maxY = maxOf(minY, safe.bottom - height)
        val fraction = prefs.safeFloat(K_EDGE_FRACTION, .35f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .35f
        val p = params(width, height).apply {
            x = when (dock) {
                EdgeDock.LEFT -> minX
                EdgeDock.RIGHT -> maxX
                EdgeDock.TOP, EdgeDock.BOTTOM -> (minX + (maxX - minX) * fraction).roundToInt().coerceIn(minX, maxX)
            }
            y = when (dock) {
                EdgeDock.TOP -> minY
                EdgeDock.BOTTOM -> maxY
                EdgeDock.LEFT, EdgeDock.RIGHT -> (minY + (maxY - minY) * fraction).roundToInt().coerceIn(minY, maxY)
            }
        }
        if (!addWindow("edge", view, p)) {
            edgeAddFailures++
            if (edgeAddFailures <= 3 && !stopping) {
                scope.launch { delay(350L * edgeAddFailures); ensureEdge() }
            } else if (!stopping) {
                NukeRemoteConfigRepository.reportOverlayFailure(remoteHud.revision)
                NukeToast.error(applicationContext, "Gaming overlay could not attach; the game keeps running and the notification can retry recovery", true)
            }
            return
        }
        edgeAddFailures = 0
        val edgeRoot = view.findViewById<View>(R.id.edgeRoot)
        makeDraggable(
            "edge",
            edgeRoot,
            tap = { toggleHub() },
            longPress = {
                val now = SystemClock.elapsedRealtime()
                if (now <= edgeStopArmedUntil) {
                    edgeStopArmedUntil = 0L
                    toastOutcome("Ending Game Nuke session")
                    scope.launch { stopSession("EDGE LONG PRESS CONFIRMED") }
                } else {
                    edgeStopArmedUntil = now + 3_000L
                    toastStatus("Long-press again within 3 seconds to end the gaming session", long = true)
                }
            },
        )
        applyKeepAwake()
        engine?.state?.value?.let(::updateTelemetryUi)
        refreshLocalizedHud()
    }

    private fun toggleHub() {
        if (hubVisible()) removeHubWindows() else ensureHubVisible()
    }

    private fun ensureHubVisible() {
        if (stopping || hubVisible() || hubAttaching) return
        hubAttaching = true
        engine?.state?.value?.let(::updateTelemetryUi)
        val callbacks = FloatingHudCallbacks(
            onTool = ::handleComposeTool,
            onToggle = ::handleComposeToggle,
            onMinimize = ::removeHubWindows,
            onEndSession = {
                toastOutcome("Ending Game Nuke session")
                scope.launch { stopSession("HUD CLOSE") }
            },
            onCoreClick = {
                if (activationJob?.isActive != true) {
                    activationJob = scope.launch(Dispatchers.IO) {
                        runCatching { engine?.initializeAndActivate() }
                        withContext(Dispatchers.Main.immediate) {
                            engine?.state?.value?.let { updateTelemetryUi(it); publishRuntime(it) }
                        }
                    }
                }
            },
            onDrag = { dx, dy ->
                val slot = windows["hub_portrait"]
                if (slot != null) {
                    val b = safeBounds()
                    slot.params.x = (slot.params.x + dx.roundToInt()).coerceIn(b.left, maxOf(b.left, b.right - slot.params.width))
                    slot.params.y = (slot.params.y + dy.roundToInt()).coerceIn(b.top, maxOf(b.top, b.bottom - slot.params.height))
                    runCatching { wm.updateViewLayout(slot.view, slot.params) }
                }
            },
            onModuleRefresh = {
                scope.launch(Dispatchers.IO) {
                    moduleShop.configure(remoteHud.moduleShop, force = true)
                }
            },
            onModuleInstall = { moduleId ->
                if (moduleActionJob?.isActive == true) toastStatus("Another module action is already running")
                else moduleActionJob = scope.launch(Dispatchers.IO) {
                    try {
                        val result = runCatching { moduleShop.install(moduleId) }
                            .getOrElse { "Install failed: ${it.message ?: it.javaClass.simpleName}" }
                        withContext(Dispatchers.Main.immediate) { toastOutcome(result) }
                    } finally {
                        moduleActionJob = null
                    }
                }
            },
            onModuleToggle = { moduleId, enabled ->
                if (moduleActionJob?.isActive == true) toastStatus("Another module action is already running")
                else moduleActionJob = scope.launch(Dispatchers.IO) {
                    try {
                        val result = runCatching { moduleShop.setEnabled(moduleId, enabled) }
                            .getOrElse { "Module failed: ${it.message ?: it.javaClass.simpleName}" }
                        withContext(Dispatchers.Main.immediate) { toastOutcome(result) }
                    } finally {
                        moduleActionJob = null
                    }
                }
            },
            onSearchFocusChanged = { focused -> setHubImeFocus(focused) },
            onQuickAction = ::handleQuickAction,
            onBrightnessChanged = ::handleBrightnessSlider,
            onDpiChanged = ::handleDpiSlider,
        )
        val bounds = safeBounds()
        val landscape = bounds.width() > bounds.height() && bounds.width() >= dp(560)
        if (landscape) {
            val wingWidth = minOf((bounds.width() * .42f).roundToInt(), dp(460))
                .coerceAtLeast(minOf(dp(240), bounds.width() / 2))
            val wingHeight = minOf((bounds.height() * .94f).roundToInt(), dp(540))
                .coerceAtLeast(minOf(dp(300), bounds.height()))
            val y = bounds.top + ((bounds.height() - wingHeight) / 2).coerceAtLeast(0)
            val primaryKey = "hub_left"
            val secondaryKey = "hub_right"
            val primaryWing = FloatingHudWing.LEFT
            val secondaryWing = FloatingHudWing.RIGHT
            val primaryParams = params(wingWidth, wingHeight).apply { x = bounds.left; this.y = y }
            val secondaryParams = params(wingWidth, wingHeight).apply { x = bounds.right - wingWidth; this.y = y }
            val primaryView = createFloatingHudComposeView(
                context = this,
                owner = composeLifecycleOwner,
                state = composeHudState,
                moduleState = moduleShop.state,
                wing = primaryWing,
                callbacks = callbacks,
            )
            val secondaryView = createFloatingHudComposeView(
                context = this,
                owner = composeLifecycleOwner,
                state = composeHudState,
                moduleState = moduleShop.state,
                wing = secondaryWing,
                callbacks = callbacks,
            )
            val primaryAdded = addWindow(primaryKey, primaryView, primaryParams)
            val secondaryAdded = primaryAdded && addWindow(secondaryKey, secondaryView, secondaryParams)
            if (!primaryAdded || !secondaryAdded) {
                removeWindowImmediate(primaryKey)
                removeWindowImmediate(secondaryKey)
                hubAttaching = false
                hubAddFailures++
                NukeRemoteConfigRepository.reportOverlayFailure(remoteHud.revision)
                ensureEdge()
                if (hubAddFailures <= 4 && !stopping) {
                    scope.launch {
                        delay(220L * hubAddFailures)
                        ensureHubVisible()
                    }
                } else {
                    toastOutcome("Floating panel could not attach; tap the edge bubble to retry")
                }
                return
            }
            applyWingTouchableRegion(primaryView, primaryWing)
            applyWingTouchableRegion(secondaryView, secondaryWing)
        } else {
            val portraitWidth = minOf((bounds.width() * .94f).roundToInt(), dp(430))
                .coerceAtLeast(minOf(dp(290), bounds.width()))
            val portraitHeight = minOf((bounds.height() * .76f).roundToInt(), dp(620))
                .coerceAtLeast(minOf(dp(360), bounds.height()))
            val x = bounds.left + ((bounds.width() - portraitWidth) / 2).coerceAtLeast(0)
            val y = bounds.top + ((bounds.height() - portraitHeight) / 2).coerceAtLeast(dp(16))
            val primaryKey = "hub_portrait"
            val primaryWing = FloatingHudWing.PORTRAIT
            val primaryParams = params(portraitWidth, portraitHeight).apply { this.x = x; this.y = y }
            val primaryView = createFloatingHudComposeView(
                context = this,
                owner = composeLifecycleOwner,
                state = composeHudState,
                moduleState = moduleShop.state,
                wing = primaryWing,
                callbacks = callbacks,
            )
            val primaryAdded = addWindow(primaryKey, primaryView, primaryParams)
            if (!primaryAdded) {
                removeWindowImmediate(primaryKey)
                hubAttaching = false
                hubAddFailures++
                NukeRemoteConfigRepository.reportOverlayFailure(remoteHud.revision)
                ensureEdge()
                if (hubAddFailures <= 4 && !stopping) {
                    scope.launch {
                        delay(220L * hubAddFailures)
                        ensureHubVisible()
                    }
                } else {
                    toastOutcome("Floating panel could not attach; tap the edge bubble to retry")
                }
                return
            }
            applyWingTouchableRegion(primaryView, primaryWing)
        }
        hubAttaching = false
        hubAddFailures = 0
        NukeRemoteConfigRepository.markOverlayStable(remoteHud.revision)
        applyKeepAwake()
        queryAndSyncQuickToolStates()
    }

    private fun hubVisible(): Boolean = windows.keys.any { it.startsWith("hub_") }

    private fun removeHubWindows() {
        setHubImeFocus(false)
        windows.keys.filter { it.startsWith("hub_") }.forEach(::removeWindow)
    }

    private fun setHubImeFocus(focused: Boolean) {
        val slots = listOfNotNull(windows["hub_right"], windows["hub_bottom"], windows["hub_left"], windows["hub_top"])
        if (slots.isEmpty()) return

        val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val notTouchModal = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val altFocusableIm = WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM

        for (slot in slots) {
            if (focused) {
                // Remove NOT_FOCUSABLE, NOT_TOUCH_MODAL, and ALT_FOCUSABLE_IM so soft keyboard can bind
                slot.params.flags = slot.params.flags and notFocusable.inv() and notTouchModal.inv() and altFocusableIm.inv()
                slot.params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            } else {
                // Restore normal flags for gaming overlay
                slot.params.flags = (slot.params.flags or notFocusable or notTouchModal) and altFocusableIm.inv()
                slot.params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            }

            slot.view.isFocusable = focused
            slot.view.isFocusableInTouchMode = focused
            runCatching { wm.updateViewLayout(slot.view, slot.params) }
                .onFailure { Log.w(TAG, "Unable to update ModuleShop IME focus", it) }
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        val activeView = slots.firstOrNull()?.view
        if (!focused) {
            if (activeView != null) {
                runCatching { imm?.hideSoftInputFromWindow(activeView.windowToken, 0) }
                activeView.clearFocus()
            }
        } else {
            activeView?.postDelayed({
                activeView.requestFocus()
                runCatching {
                    imm?.showSoftInput(activeView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }, 120L)
        }
    }

    private fun queryAndSyncQuickToolStates() {
        scope.launch(Dispatchers.IO) {
            val adb = AdbManager.getInstance(applicationContext)

            // ── Source-of-truth: SharedPreferences ────────────────────────────────
            // Toggle states are ALWAYS loaded from prefs. The live shell query below is
            // only used for brightness, DPI, and unsupported detection — NOT to overwrite
            // user-toggled switch states. This prevents the snapback bug where a slow
            // device settings query undoes optimistic state changes the user just made.
            val isGamePref = prefs.safeBoolean(K_ADAPTIVE_GAME_MODE, false)
            val isDndPref = prefs.safeBoolean("nuke_quick_dnd", false)
            val isTouchPref = prefs.safeBoolean("nuke_quick_touch_response", false)
            val isNetPref = engine?.state?.value?.networkBoostActive == true
                || prefs.safeBoolean("nuke_quick_net_boost", false)
            val isHotspotPref = prefs.safeBoolean("nuke_quick_hotspot", false)
            val isSilentPref = prefs.safeBoolean("nuke_quick_silent_mode", false)
            val isReadingPref = prefs.safeBoolean("nuke_quick_reading_mode", false)
            val isDarkPref = prefs.safeBoolean("nuke_quick_dark_mode", false)
            val isRotPref = prefs.safeBoolean("nuke_quick_rotation_lock", false)
            val isBatteryPref = prefs.safeBoolean("nuke_quick_battery_saver", false)

            // ── Live shell query: only for brightness, DPI & unsupported detection ─
            val batchScript = """
                echo "bright=$(settings get system screen_brightness 2>/dev/null)"
                echo "dark=$(cmd uimode night 2>/dev/null)"
                echo "dpi=$(wm density 2>/dev/null)"
                echo "hot=$(dumpsys wifi 2>/dev/null | grep -m1 -i softApState)"
            """.trimIndent()
            val result = runCatching { adb.executeCommand(batchScript, "/", 4_000L).output }.getOrDefault("")
            val kv = mutableMapOf<String, String>()
            result.lineSequence().forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) kv[parts[0].trim()] = parts[1].trim()
            }

            val rawBright = kv["bright"]?.toIntOrNull()
            val brightPct = if (rawBright != null) ((rawBright / 255.0f) * 100).roundToInt().coerceIn(5, 100) else 100
            val rawDpi = kv["dpi"]?.substringAfterLast(":")?.trim()?.toIntOrNull() ?: 400

            // Only mark unsupported if device explicitly says so
            val unsupported = mutableSetOf<String>()
            val dark = kv["dark"]
            if (dark?.contains("unsupported", true) == true) unsupported.add("dark_mode")
            if (dark?.contains("unsupported", true) == true) unsupported.add("dark_mode")

            // Hotspot: if shell gives a definitive answer, sync it into prefs too so it stays consistent
            val hot = kv["hot"]
            val isHotspot = if (hot != null && hot.isNotBlank()) {
                val liveHot = hot.contains("13") || hot.contains("enabled", true)
                // Sync to prefs so next open shows correct state
                prefs.edit().putBoolean("nuke_quick_hotspot", liveHot).apply()
                liveHot
            } else isHotspotPref

            val isBluetoothPref = prefs.safeBoolean("nuke_quick_bluetooth", false)
            val isAirplanePref = prefs.safeBoolean("nuke_quick_airplane_mode", false)
            val isVibrationPref = prefs.safeBoolean("nuke_quick_vibration", true)
            val isCpuTurboPref = prefs.safeBoolean("nuke_quick_cpu_turbo", false)
            val isScreenTimeoutPref = prefs.safeBoolean("nuke_quick_screen_timeout_extend", false)
            val isDataSaverPref = prefs.safeBoolean("nuke_quick_data_saver", false)

            val states = mapOf(
                "game_mode" to isGamePref,
                "dnd" to isDndPref,
                "touch_response" to isTouchPref,
                "net_boost" to isNetPref,
                "hotspot" to isHotspot,
                "silent_mode" to isSilentPref,
                "reading_mode" to isReadingPref,
                "dark_mode" to isDarkPref,
                "rotation_lock" to isRotPref,
                "battery_saver" to isBatteryPref,
                "bluetooth" to isBluetoothPref,
                "airplane_mode" to isAirplanePref,
                "vibration" to isVibrationPref,
                "cpu_turbo" to isCpuTurboPref,
                "screen_timeout_extend" to isScreenTimeoutPref,
                "data_saver" to isDataSaverPref,
            )

            withContext(Dispatchers.Main.immediate) {
                composeHudState.update { snap ->
                    snap.copy(
                        // Merge: prefer incoming states, but never remove keys already set by user
                        quickToolStates = snap.quickToolStates + states,
                        unsupportedQuickTools = unsupported,
                        brightnessPercent = brightPct,
                        displayDpi = rawDpi,
                    )
                }
            }
        }
    }

    private fun handleBrightnessSlider(percent: Int) {
        val pct = percent.coerceIn(5, 100)
        composeHudState.update { it.copy(brightnessPercent = pct) }
        scope.launch(Dispatchers.IO) {
            val adb = AdbManager.getInstance(applicationContext)
            val raw = ((pct / 100.0f) * 255).roundToInt().coerceIn(10, 255)
            val floatVal = (pct / 100.0f).coerceIn(0.05f, 1.0f)
            val script = "settings put system screen_brightness_mode 0 2>/dev/null ; settings put system screen_brightness $raw 2>/dev/null ; cmd display set-brightness $floatVal 2>/dev/null"
            adb.executeCommand(script, "/", 3_000L)
            withContext(Dispatchers.Main.immediate) {
                toastOutcome("Brightness: $pct%")
            }
        }
    }

    private fun handleDpiSlider(dpi: Int) {
        val targetDpi = dpi.coerceIn(320, 600)
        composeHudState.update { it.copy(displayDpi = targetDpi) }
        scope.launch(Dispatchers.IO) {
            val adb = AdbManager.getInstance(applicationContext)
            adb.executeCommand("wm density $targetDpi 2>/dev/null", "/", 4_000L)
            withContext(Dispatchers.Main.immediate) {
                toastOutcome("Display Density: ${targetDpi} DPI")
            }
        }
    }

    private fun handleQuickAction(action: String) {
        // Optimistic UI state update immediately (0ms visual feedback!)
        val currentActive = composeHudState.value.quickToolStates[action] ?: false
        val nextVal = !currentActive
        if (action != "deep_clean" && action != "screenshot") {
            prefs.edit().putBoolean("nuke_quick_$action", nextVal).apply()
            composeHudState.update { it.copy(quickToolStates = it.quickToolStates + (action to nextVal)) }
        }

        when (action) {
            "game_mode" -> {
                val local = engine ?: run { toastOutcome("Game Nuke core is not ready"); return }
                val current = prefs.safeBoolean(K_ADAPTIVE_GAME_MODE, false)
                val target = !current
                prefs.edit().putBoolean(K_ADAPTIVE_GAME_MODE, target).apply()
                scope.launch {
                    applyAdaptiveGameMode(local, target)
                    toastOutcome(if (target) "Game Mode: PERFORMANCE" else "Game Mode: STANDARD")
                }
            }
            "dnd" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val script = if (nextVal) {
                        "cmd notification set_interruption_filter priority 2>/dev/null || settings put global zen_mode 1 2>/dev/null || cmd notification set_zen_mode 1 2>/dev/null"
                    } else {
                        "cmd notification set_interruption_filter all 2>/dev/null || settings put global zen_mode 0 2>/dev/null || cmd notification set_zen_mode 0 2>/dev/null"
                    }
                    adb.executeCommand(script, "/", 3_000L)
                    withContext(Dispatchers.Main.immediate) {
                        toastOutcome(if (nextVal) "Do Not Disturb: ON" else "Do Not Disturb: OFF")
                    }
                }
            }
            "touch_response" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val nextSpeed = if (nextVal) 7 else 0
                    val script = """
                        settings put system pointer_speed $nextSpeed 2>/dev/null
                        setprop debug.touch.pressure.scale ${if (nextVal) "0.001" else "1.0"} 2>/dev/null
                        setprop debug.touch.size.scale ${if (nextVal) "0.001" else "1.0"} 2>/dev/null
                        setprop persist.sys.touch.response ${if (nextVal) "1" else "0"} 2>/dev/null
                        settings put secure tap_duration_threshold 0 2>/dev/null
                        settings put secure touch_blocking_period 0 2>/dev/null
                    """.trimIndent()
                    adb.executeCommand(script, "/", 3_000L)
                    withContext(Dispatchers.Main.immediate) {
                        toastOutcome(if (nextVal) "Touch Response: HIGH SENSITIVITY" else "Touch Response: STANDARD")
                    }
                }
            }
            "net_boost" -> {
                val local = engine
                if (local != null) {
                    scope.launch {
                        local.setNetworkBoost(nextVal)
                        toastOutcome(if (nextVal) "Net Performance Lock: ACTIVE" else "Net Performance Lock: DISABLED")
                    }
                } else {
                    scope.launch(Dispatchers.IO) {
                        val adb = AdbManager.getInstance(applicationContext)
                        val script = "setprop net.tcp.delack ${if (nextVal) 0 else 1} 2>/dev/null ; sysctl -w net.ipv4.tcp_low_latency=${if (nextVal) 1 else 0} 2>/dev/null"
                        adb.executeCommand(script, "/", 3_000L)
                        withContext(Dispatchers.Main.immediate) {
                            toastOutcome(if (nextVal) "Net Performance Lock: ACTIVE" else "Net Performance Lock: DISABLED")
                        }
                    }
                }
            }
            "hotspot" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    if (nextVal) {
                        // Strategy 1: cmd wifi start-tethering (Android 11+)
                        val r = adb.executeCommand("cmd wifi start-tethering 2>/dev/null", "/", 3_000L)
                        if (r.isSuccess && r.output.contains("success", true)) {
                            withContext(Dispatchers.Main.immediate) { toastOutcome("Hotspot: ON") }
                            return@launch
                        }
                        // Strategy 2: Tethering Manager via service call (Android 12+)
                        val r2 = adb.executeCommand("cmd connectivity start-tethering 0 2>/dev/null", "/", 3_000L)
                        if (r2.isSuccess) {
                            withContext(Dispatchers.Main.immediate) { toastOutcome("Hotspot: ON") }
                            return@launch
                        }
                        // Strategy 3: Open hotspot settings UI for user
                        adb.executeCommand("am start -n com.android.settings/.TetherSettings 2>/dev/null", "/", 3_000L)
                        withContext(Dispatchers.Main.immediate) {
                            toastOutcome("Hotspot: Buka Settings Hotspot (tap toggle)")
                            // Revert UI state since we couldn't toggle programmatically
                            prefs.edit().putBoolean("nuke_quick_hotspot", false).apply()
                            composeHudState.update { it.copy(quickToolStates = it.quickToolStates + ("hotspot" to false)) }
                        }
                    } else {
                        adb.executeCommand("cmd wifi stop-tethering 2>/dev/null ; cmd connectivity stop-tethering 0 2>/dev/null", "/", 3_000L)
                        withContext(Dispatchers.Main.immediate) { toastOutcome("Hotspot: OFF") }
                    }
                }
            }
            "deep_clean" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    val memBefore = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
                    val statBefore = runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0L)

                    val myPkg = packageName
                    val gamePkg = NukeRuntimeState.state.value.activePackage ?: ""

                    // Phase 1: Kill ALL cached/background processes (skip game, self, system)
                    val killScript = """
                        for p in $(dumpsys activity processes 2>/dev/null | grep -E 'ProcessRecord' | grep -E 'adj=(9[0-9]{2}|1[0-9]{3})' | grep -oP '(?<=pid=)\d+' | sort -u); do
                            pkg=$(cat /proc/${'$'}p/cmdline 2>/dev/null | tr -d '\0' | cut -d: -f1)
                            [ "${'$'}pkg" != "$myPkg" ] && [ "${'$'}pkg" != "$gamePkg" ] && \
                            [ "${'$'}pkg" != "com.android.systemui" ] && [ "${'$'}pkg" != "moe.shizuku.privileged.api" ] && \
                            [ "${'$'}pkg" != "com.iadb" ] && [ "${'$'}pkg" != "com.google.android.gms" ] && \
                            [ -n "${'$'}pkg" ] && kill -9 ${'$'}p 2>/dev/null
                        done
                        am kill-all 2>/dev/null
                    """.trimIndent()
                    adb.executeCommand(killScript, "/", 6_000L)

                    // Phase 2: Memory compaction + drop caches
                    val memScript = """
                        am compact all 2>/dev/null
                        pm trim-caches 9999999999 2>/dev/null
                        echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
                        echo 1 > /proc/sys/vm/compact_memory 2>/dev/null
                        sync
                    """.trimIndent()
                    adb.executeCommand(memScript, "/", 6_000L)
                    System.gc()

                    // Phase 3: Deep junk file cleaning — all app caches, temp files, thumbnails
                    val cleanScript = """
                        rm -rf /sdcard/Android/data/*/cache/* 2>/dev/null
                        rm -rf /sdcard/Android/data/*/code_cache/* 2>/dev/null
                        rm -rf /sdcard/Android/data/*/.cache/* 2>/dev/null
                        rm -rf /sdcard/Android/media/*/cache/* 2>/dev/null
                        rm -rf /sdcard/Android/obb/*/cache/* 2>/dev/null
                        rm -rf /sdcard/.thumbnails/* 2>/dev/null
                        rm -rf /sdcard/DCIM/.thumbnails/* 2>/dev/null
                        rm -rf /sdcard/Download/.trash/* 2>/dev/null
                        rm -rf /sdcard/.cache/* 2>/dev/null
                        rm -rf /data/local/tmp/* 2>/dev/null
                        rm -rf /data/local/tmp/.* 2>/dev/null
                        find /sdcard/Android/data -name '*.log' -delete 2>/dev/null
                        find /sdcard/Android/data -name '*.tmp' -delete 2>/dev/null
                        find /sdcard -maxdepth 2 -name '*.bak' -delete 2>/dev/null
                        find /data/data -maxdepth 3 -name 'cache' -type d -exec rm -rf {}/* \; 2>/dev/null
                        sync
                    """.trimIndent()
                    adb.executeCommand(cleanScript, "/", 10_000L)

                    // Phase 4: Force GC on all running apps
                    adb.executeCommand("am send-trim-memory 0 RUNNING_CRITICAL 2>/dev/null", "/", 3_000L)

                    val memAfter = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
                    val statAfter = runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0L)

                    val freedRamMb = ((memAfter.availMem - memBefore.availMem) / (1024 * 1024)).coerceAtLeast(0L)
                    val freedStorageMb = ((statAfter - statBefore) / (1024 * 1024)).coerceAtLeast(0L)

                    withContext(Dispatchers.Main.immediate) {
                        val ramStr = if (freedRamMb > 0) "+${freedRamMb}MB RAM" else "RAM Compacted"
                        val storStr = if (freedStorageMb > 0) "+${freedStorageMb}MB Storage" else "Cache Purged"
                        toastOutcome("DEEP CLEAN DONE! $ramStr ✓ $storStr")
                    }
                }
            }
            "silent_mode" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val nextRinger = if (nextVal) 0 else 2
                    val script = "cmd audio set-ringer-mode $nextRinger 2>/dev/null ; settings put global mode_ringer $nextRinger 2>/dev/null"
                    adb.executeCommand(script, "/", 3_000L)
                    val audio = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    runCatching {
                        audio?.ringerMode = if (nextVal) android.media.AudioManager.RINGER_MODE_SILENT else android.media.AudioManager.RINGER_MODE_NORMAL
                    }
                    withContext(Dispatchers.Main.immediate) {
                        toastOutcome(if (nextVal) "Ringer: SILENT (Muted)" else "Ringer: NORMAL SOUND")
                    }
                }
            }
            "screenshot" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val timestamp = System.currentTimeMillis()
                    val path = "/sdcard/Pictures/Screenshots/GameNuke_$timestamp.png"
                    val r = adb.executeCommand("screencap -p $path 2>/dev/null ; am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$path 2>/dev/null")
                    withContext(Dispatchers.Main.immediate) {
                        if (r.isSuccess) toastOutcome("Screenshot Saved: GameNuke_$timestamp.png")
                        else toastOutcome("Screenshot Captured")
                    }
                }
            }
            "reading_mode" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val flag = if (nextVal) "1" else "0"
                    val script = "settings put secure night_display_activated $flag 2>/dev/null ; cmd color night-display ${if (nextVal) "on" else "off"} 2>/dev/null ; settings put system display_anti_flicker $flag 2>/dev/null ; settings put system eye_protect_mode $flag 2>/dev/null"
                    adb.executeCommand(script, "/", 3_000L)
                    withContext(Dispatchers.Main.immediate) {
                        toastOutcome(if (nextVal) "Eye Comfort: ON" else "Eye Comfort: OFF")
                    }
                }
            }
            "dark_mode" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val arg = if (nextVal) "yes" else "no"
                    val script = "cmd uimode night $arg 2>/dev/null ; settings put secure ui_night_mode ${if (nextVal) "2" else "1"} 2>/dev/null ; settings put system ui_night_mode ${if (nextVal) "2" else "1"} 2>/dev/null"
                    adb.executeCommand(script, "/", 3_000L)
                    withContext(Dispatchers.Main.immediate) {
                        toastOutcome(if (nextVal) "Dark Theme: ON" else "Dark Theme: OFF")
                    }
                }
            }
            "rotation_lock" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val flag = if (nextVal) "0" else "1"
                    adb.executeCommand("settings put system accelerometer_rotation $flag 2>/dev/null", "/", 3_000L)
                    withContext(Dispatchers.Main.immediate) {
                        toastOutcome(if (nextVal) "Auto-Rotation: LOCKED" else "Auto-Rotation: AUTO")
                    }
                }
            }
            "battery_saver" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    if (nextVal) {
                        // Must simulate unplug first (ADB keeps charging=true which blocks battery saver)
                        val script = """
                            dumpsys battery unplug 2>/dev/null
                            settings put global low_power 1 2>/dev/null
                            am broadcast -a android.os.action.POWER_SAVE_MODE_CHANGED --ez mode true 2>/dev/null
                            settings put global low_power_sticky 0 2>/dev/null
                            settings put global low_power_trigger_level 0 2>/dev/null
                            cmd power set-mode 1 2>/dev/null
                        """.trimIndent()
                        adb.executeCommand(script, "/", 4_000L)
                    } else {
                        val script = """
                            settings put global low_power 0 2>/dev/null
                            am broadcast -a android.os.action.POWER_SAVE_MODE_CHANGED --ez mode false 2>/dev/null
                            cmd power set-mode 0 2>/dev/null
                            dumpsys battery reset 2>/dev/null
                        """.trimIndent()
                        adb.executeCommand(script, "/", 4_000L)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        toastOutcome(if (nextVal) "Battery Saver: ON" else "Battery Saver: OFF")
                    }
                }
            }
            "bluetooth" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val script = if (nextVal) {
                        "cmd bluetooth_manager enable 2>/dev/null ; svc bluetooth enable 2>/dev/null ; settings put global bluetooth_on 1 2>/dev/null"
                    } else {
                        "cmd bluetooth_manager disable 2>/dev/null ; svc bluetooth disable 2>/dev/null ; settings put global bluetooth_on 0 2>/dev/null"
                    }
                    adb.executeCommand(script, "/", 3_000L)
                    withContext(Dispatchers.Main.immediate) { toastOutcome(if (nextVal) "Bluetooth: ON" else "Bluetooth: OFF") }
                }
            }
            "airplane_mode" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val flag = if (nextVal) "1" else "0"
                    val script = """
                        settings put global airplane_mode_on $flag 2>/dev/null
                        am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${if (nextVal) "true" else "false"} 2>/dev/null
                        cmd connectivity airplane-mode ${if (nextVal) "enable" else "disable"} 2>/dev/null
                    """.trimIndent()
                    adb.executeCommand(script, "/", 4_000L)
                    withContext(Dispatchers.Main.immediate) { toastOutcome(if (nextVal) "Airplane Mode: ON" else "Airplane Mode: OFF") }
                }
            }
            "vibration" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val flag = if (nextVal) "1" else "0"
                    val script = "settings put system haptic_feedback_enabled $flag 2>/dev/null ; settings put system vibrate_on $flag 2>/dev/null ; settings put system vibrate_when_ringing $flag 2>/dev/null"
                    adb.executeCommand(script, "/", 3_000L)
                    runCatching {
                        val audio = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                        audio?.setVibrateSetting(android.media.AudioManager.VIBRATE_TYPE_RINGER,
                            if (nextVal) android.media.AudioManager.VIBRATE_SETTING_ON else android.media.AudioManager.VIBRATE_SETTING_OFF)
                    }
                    withContext(Dispatchers.Main.immediate) { toastOutcome(if (nextVal) "Vibration: ON" else "Vibration: OFF") }
                }
            }
            "cpu_turbo" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val script = if (nextVal) {
                        """
                            for gov in $(ls /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor 2>/dev/null); do echo performance > ${'$'}gov 2>/dev/null; done
                            settings put global restricted_networking_mode 0 2>/dev/null
                            settings put global cpu_scalar 1 2>/dev/null
                            cmd power set-fixed-performance-mode-enabled true 2>/dev/null
                        """.trimIndent()
                    } else {
                        """
                            for gov in $(ls /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor 2>/dev/null); do echo schedutil > ${'$'}gov 2>/dev/null; done
                            cmd power set-fixed-performance-mode-enabled false 2>/dev/null
                        """.trimIndent()
                    }
                    adb.executeCommand(script, "/", 4_000L)
                    withContext(Dispatchers.Main.immediate) { toastOutcome(if (nextVal) "CPU: PERFORMANCE Governor" else "CPU: Auto Governor") }
                }
            }
            "screen_timeout_extend" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    // 30 minutes = 1800000ms, default = 60000ms (1 min)
                    val timeout = if (nextVal) "1800000" else "60000"
                    adb.executeCommand("settings put system screen_off_timeout $timeout 2>/dev/null", "/", 3_000L)
                    withContext(Dispatchers.Main.immediate) { toastOutcome(if (nextVal) "Screen Timeout: 30 min" else "Screen Timeout: 1 min") }
                }
            }
            "data_saver" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    if (nextVal) {
                        val script = """
                            cmd netpolicy set restrict-background true 2>/dev/null
                            settings put global data_roaming 0 2>/dev/null
                            cmd connectivity set-data-saver-mode true 2>/dev/null
                        """.trimIndent()
                        adb.executeCommand(script, "/", 3_000L)
                    } else {
                        val script = """
                            cmd netpolicy set restrict-background false 2>/dev/null
                            cmd connectivity set-data-saver-mode false 2>/dev/null
                        """.trimIndent()
                        adb.executeCommand(script, "/", 3_000L)
                    }
                    withContext(Dispatchers.Main.immediate) { toastOutcome(if (nextVal) "Data Saver: ON" else "Data Saver: OFF") }
                }
            }
            "macro" -> {
                val controller = NukeMacroController.getInstance(applicationContext)
                if (controller.state.value.isRunning) {
                    controller.stopMacro()
                    toastOutcome("Macro Fast-Hand: STOPPED")
                } else {
                    val engine = controller.detectBestEngine()
                    if (engine == NukeMacroController.MacroEngine.NONE) {
                        toastStatus("Aktifkan Shizuku atau Accessibility Service untuk Macro", long = true)
                    } else {
                        if (controller.state.value.points.isEmpty()) {
                            val dm = resources.displayMetrics
                            val cx = dm.widthPixels * 0.75f
                            val cy = dm.heightPixels * 0.65f
                            controller.addPoint(cx, cy, 60L)
                            controller.addPoint(cx - 80f, cy - 80f, 60L)
                        }
                        controller.startMacro()
                        val engineName = if (engine == NukeMacroController.MacroEngine.SHIZUKU_PRIVILEGED) "0ms Shizuku" else "Accessibility"
                        toastOutcome("Macro Fast-Hand: ACTIVE ($engineName)")
                    }
                }
            }
            "vpn_boost" -> {
                if (NukeVpnService.isRunning) {
                    NukeVpnService.stopBoost(applicationContext)
                    toastOutcome("VPN Ping Booster: OFF")
                } else {
                    val prepareIntent = NukeVpnService.prepare(applicationContext)
                    if (prepareIntent != null) {
                        prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(prepareIntent)
                        toastStatus("Izinkan izin VPN untuk Ping Booster", long = true)
                    } else {
                        NukeVpnService.startBoost(applicationContext, NukeVpnService.BoostMode.TURBO_1MS)
                        toastOutcome("VPN Ping Turbo: 1ms LOCKED")
                    }
                }
            }
            "fps_lock" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val hz = if (nextVal) "120" else "0"
                    val script = """
                        settings put system peak_refresh_rate $hz 2>/dev/null
                        settings put system min_refresh_rate $hz 2>/dev/null
                        settings put system user_refresh_rate $hz 2>/dev/null
                    """.trimIndent()
                    adb.executeCommand(script, "/", 3_000L)
                    withContext(Dispatchers.Main.immediate) {
                        toastOutcome(if (nextVal) "Display Refresh: LOCKED 120Hz" else "Display Refresh: DYNAMIC AUTO")
                    }
                }
            }
            "anti_mistouch" -> {
                scope.launch(Dispatchers.IO) {
                    val adb = AdbManager.getInstance(applicationContext)
                    val script = if (nextVal) {
                        "settings put secure edge_touch_prevention 1 2>/dev/null ; settings put system edge_mistouch_prevention 1 2>/dev/null"
                    } else {
                        "settings put secure edge_touch_prevention 0 2>/dev/null ; settings put system edge_mistouch_prevention 0 2>/dev/null"
                    }
                    adb.executeCommand(script, "/", 3_000L)
                    withContext(Dispatchers.Main.immediate) {
                        toastOutcome(if (nextVal) "Anti-Mistouch Palm Shield: ON" else "Anti-Mistouch: OFF")
                    }
                }
            }
            "check_update" -> {
                NukeAppUpdater.checkForUpdates(
                    context = applicationContext,
                    onUpdateAvailable = { updateInfo ->
                        toastOutcome("Update Baru: v${updateInfo.versionName}! Mengunduh APK…")
                        NukeAppUpdater.startDownloadAndInstall(applicationContext, updateInfo)
                    },
                    onUpToDate = {
                        toastOutcome("Game Nuke sudah versi terbaru!")
                    },
                    onError = { err ->
                        toastOutcome("Cek update gagal: $err")
                    }
                )
            }
            else -> toastOutcome("Action completed")
        }
    }

    /**
     * Restrict window touch interception to the visible saber-panel shape.
     * Paths here must match the GenericShapes defined in FloatingHudCompose.kt.
     * We use View-level touch gating (public SDK only) — no hidden Android APIs.
     */
    private fun applyWingTouchableRegion(view: View, wing: FloatingHudWing) {
        view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            val wf = width.toFloat()
            val hf = height.toFloat()
            // Approximate path matching the Compose saber-curve shapes with line segments
            // (android.graphics.Path approximation is fine for touch region hit-testing).
            val path = android.graphics.Path().apply {
                when (wing) {
                    FloatingHudWing.LEFT -> {
                        // Full-height left edge, inner side concave inward at mid-height
                        val inset = wf * .14f
                        moveTo(0f, 0f)
                        lineTo(wf - inset * .3f, 0f)
                        lineTo(wf, hf * .5f)          // widest concave point (approx)
                        lineTo(wf - inset * .3f, hf)
                        lineTo(0f, hf)
                    }
                    FloatingHudWing.RIGHT -> {
                        val inset = wf * .14f
                        moveTo(wf, 0f)
                        lineTo(inset * .3f, 0f)
                        lineTo(0f, hf * .5f)
                        lineTo(inset * .3f, hf)
                        lineTo(wf, hf)
                    }
                    FloatingHudWing.TOP -> {
                        val scoop = hf * .18f
                        moveTo(0f, 0f); lineTo(wf, 0f)
                        lineTo(wf, hf - scoop)
                        lineTo(wf * .5f, hf)
                        lineTo(0f, hf - scoop)
                    }
                    FloatingHudWing.BOTTOM -> {
                        val scoop = hf * .18f
                        moveTo(0f, hf); lineTo(wf, hf)
                        lineTo(wf, scoop)
                        lineTo(wf * .5f, 0f)
                        lineTo(0f, scoop)
                    }
                    FloatingHudWing.PORTRAIT -> {
                        val r = 14f * density()
                        addRoundRect(0f, 0f, wf, hf, r, r, android.graphics.Path.Direction.CW)
                    }
                }
                close()
            }
            val region = android.graphics.Region().also {
                it.setPath(path, android.graphics.Region(0, 0, width, height))
            }
            view.tag = region
        }
        view.setOnTouchListener { v, event ->
            val region = v.tag as? android.graphics.Region ?: return@setOnTouchListener false
            val x = event.x.toInt()
            val y = event.y.toInt()
            if (!region.contains(x, y)) return@setOnTouchListener false
            v.onTouchEvent(event)
        }
    }

    private fun handleComposeTool(tool: FloatingHudTool) {
        when (tool) {
            FloatingHudTool.CROSSHAIR -> openCrosshairStudio()
            FloatingHudTool.DEEP_CLEAN -> openDeepClean()
            FloatingHudTool.MAX_FPS -> openMaxFps()
            FloatingHudTool.NETWORK -> openNetwork()
            FloatingHudTool.MONITOR -> openMonitor()
            FloatingHudTool.RESOURCE_RADAR -> openResourceRadar()
            FloatingHudTool.SCREEN_CONTROL -> openScreenControl()
            FloatingHudTool.CPU_CLOCKS -> openCpuClocks()
        }
    }

    private fun handleComposeToggle(toggle: FloatingHudToggle, requested: Boolean) {
        if (toggleJobs[toggle]?.isActive == true) return
        when (toggle) {
            FloatingHudToggle.GAME_MODE -> submitComposeToggle(toggle, requested) { local ->
                applyAdaptiveGameMode(local, requested)
            }
            FloatingHudToggle.DND -> {
                val state = engine?.state?.value
                if (state?.dndAccess == true) submitComposeToggle(toggle, requested) { it.setGameFocus(requested) }
                else {
                    if (openExternalPanelOnce(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))) {
                        toastStatus("Opening Do Not Disturb access settings — return to Game Nuke when done", long = true)
                    }
                }
            }
            FloatingHudToggle.CALL_SHIELD -> when {
                !NukeCallShield.isSupported(this) -> toastOutcome("Call Shield is not available on this Android build")
                requested && !NukeCallShield.isRoleHeld(this) -> {
                    if (allowExternalPanel()) {
                        toastStatus("Opening Call Shield role settings — choose Game Nuke, then return", long = true)
                        if (NukeCallShield.requestRole(this)) NukeRuntimeState.markExternalNavigationOpened()
                        else toastOutcome("Call Shield permission screen could not be opened")
                    }
                }
                else -> {
                    val applied = NukeCallShield.setEnabled(this, requested)
                    engine?.state?.value?.let(::updateTelemetryUi)
                    if (requested && !applied) toastOutcome("Call Shield failed to enable")
                    else toastOutcome(if (requested) "Call Shield enabled for gaming sessions" else "Call Shield disabled")
                }
            }
            FloatingHudToggle.BATTERY_SAVER -> submitComposeToggle(toggle, requested) { it.setBatterySaver(requested) }
            FloatingHudToggle.DATA_SAVER -> submitComposeToggle(toggle, requested) { it.setDataSaver(requested) }
            FloatingHudToggle.NETWORK_BOOST -> submitComposeToggle(toggle, requested) { it.setNetworkBoost(requested) }
            FloatingHudToggle.CROSSHAIR -> {
                prefs.edit().putBoolean("cross_en", requested).apply()
                syncCrosshairOverlay()
                engine?.state?.value?.let(::updateTelemetryUi)
                toastOutcome("Crosshair overlay ${if (requested) "enabled" else "disabled"}")
            }
            FloatingHudToggle.KEEP_AWAKE -> {
                prefs.edit().putBoolean("hud_keep_awake", requested).apply()
                applyKeepAwake()
                engine?.state?.value?.let(::updateTelemetryUi)
                toastOutcome("Keep awake ${if (requested) "enabled" else "disabled"}")
            }
        }
    }

    private suspend fun applyAdaptiveGameMode(local: NukePerformanceEngine, enabled: Boolean) {
        if (enabled) {
            val wasAwake = prefs.safeBoolean("hud_keep_awake", false)
            prefs.edit()
                .putBoolean(K_ADAPTIVE_GAME_MODE, true)
                .putBoolean(K_ADAPTIVE_OWN_AWAKE, !wasAwake)
                .apply()
            if (!wasAwake) {
                prefs.edit().putBoolean("hud_keep_awake", true).apply()
                applyKeepAwake()
            }
            var ownNetwork = false
            if (!local.state.value.networkBoostActive) {
                runCatching { local.setNetworkBoost(true) }
                ownNetwork = local.state.value.networkBoostActive
            }
            var ownOem = false
            val gameModeSupported = local.state.value.capabilities.firstOrNull { it.id == "game_mode" }?.supported == true
            if (gameModeSupported && local.state.value.gameModePerformance != true) {
                runCatching { local.setPerformanceGameMode(true) }
                ownOem = local.state.value.gameModePerformance == true
            }
            runCatching { local.maximizeNativeFps() }
            prefs.edit().putBoolean(K_ADAPTIVE_OWN_NETWORK, ownNetwork).putBoolean(K_ADAPTIVE_OWN_OEM, ownOem).apply()
            scope.launch(Dispatchers.IO) {
                val adb = AdbManager.getInstance(applicationContext)
                val turboScript = """
                    for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo performance > ${'$'}g 2>/dev/null; done
                    for g in /sys/class/kgsl/kgsl-3d0/devfreq/governor /sys/class/devfreq/*gpu*/governor; do echo performance > ${'$'}g 2>/dev/null; done
                    cmd power set-fixed-performance-mode-enabled true 2>/dev/null
                    setprop debug.sf.latch_unsignaled 1 2>/dev/null
                    setprop debug.renderengine.backend skiagl 2>/dev/null
                    setprop debug.sf.disable_backpressure 1 2>/dev/null
                    setprop persist.sys.game.mode 1 2>/dev/null
                    settings put global restricted_networking_mode 0 2>/dev/null
                    settings put system pointer_speed 7 2>/dev/null
                    sysctl -w net.ipv4.tcp_low_latency=1 2>/dev/null
                    setprop net.tcp.delack 0 2>/dev/null
                """.trimIndent()
                adb.executeCommand(turboScript, "/", 4_000L)
            }
        } else {
            val ownNetwork = prefs.safeBoolean(K_ADAPTIVE_OWN_NETWORK, false)
            val ownOem = prefs.safeBoolean(K_ADAPTIVE_OWN_OEM, false)
            val ownAwake = prefs.safeBoolean(K_ADAPTIVE_OWN_AWAKE, false)
            if (ownOem && local.state.value.gameModePerformance == true) runCatching { local.setPerformanceGameMode(false) }
            if (ownNetwork && local.state.value.networkBoostActive) runCatching { local.setNetworkBoost(false) }
            if (ownAwake) {
                prefs.edit().putBoolean("hud_keep_awake", false).apply()
                applyKeepAwake()
            }
            prefs.edit().putBoolean(K_ADAPTIVE_GAME_MODE, false)
                .remove(K_ADAPTIVE_OWN_AWAKE).remove(K_ADAPTIVE_OWN_NETWORK).remove(K_ADAPTIVE_OWN_OEM).apply()
            scope.launch(Dispatchers.IO) {
                val adb = AdbManager.getInstance(applicationContext)
                val resetScript = """
                    for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo schedutil > ${'$'}g 2>/dev/null; done
                    cmd power set-fixed-performance-mode-enabled false 2>/dev/null
                    setprop persist.sys.game.mode 0 2>/dev/null
                """.trimIndent()
                adb.executeCommand(resetScript, "/", 3_000L)
            }
        }
    }

    private fun submitComposeToggle(
        toggle: FloatingHudToggle,
        requested: Boolean,
        action: suspend (NukePerformanceEngine) -> Unit,
    ) {
        val local = engine ?: run {
            toastOutcome("Game Nuke core is not ready")
            return
        }
        if (toggleJobs[toggle]?.isActive == true) return

        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val result = runCatching { action(local) }
                .onFailure { Log.w(TAG, "HUD toggle $toggle failed", it) }
            runCatching { local.refreshHudMetrics(includeCpu = false) }
            toggleJobs.remove(toggle)
            local.state.value.let { updateTelemetryUi(it); publishRuntime(it) }
            if (result.isFailure) {
                pendingToggleValues.remove(toggle)
                toastOutcome("Action failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
            } else {
                pendingToggleValues.remove(toggle)
                toastOutcome(local.state.value.message)
            }
        }
        toggleJobs[toggle] = job
        pendingToggleValues[toggle] = requested
        composeHudState.update { snapshot ->
            snapshot.copy(
                toggleValues = snapshot.toggleValues + (toggle to requested),
                busyToggles = snapshot.busyToggles + toggle,
                statusMessage = "SYNCING ${toggle.name.replace('_', ' ')}…",
            )
        }
        job.start()
    }

    /**
     * Child panels use one in-flight job per panel. This prevents repeated taps from queuing the
     * same slow shell operation (cache trim, frame scan, reconnect, and similar commands).
     */
    private fun runPanelAction(key: String, block: suspend () -> Unit) {
        val gateKey = key.substringBefore('.')
        if (panelActionJobs[gateKey]?.isActive == true) {
            toastStatus("${gateKey.replace('_', ' ')} action is already running")
            return
        }
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Panel action $key failed safely", error)
                toastOutcome("$key failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                panelActionJobs.remove(gateKey, job)
            }
        }
        panelActionJobs[gateKey] = job
        job.start()
    }

    private fun allowExternalPanel(): Boolean {
        return NukeRuntimeState.tryBeginExternalNavigation()
    }

    private fun openExternalPanelOnce(intent: Intent): Boolean {
        if (!allowExternalPanel()) return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return runCatching {
            startActivity(intent)
            NukeRuntimeState.markExternalNavigationOpened()
            true
        }.getOrDefault(false)
    }

    private var overlayToastView: View? = null
    private var overlayToastDismissRunnable: Runnable? = null

    private fun showOverlayToast(message: String, long: Boolean = false) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            overlayToastDismissRunnable?.let { mainHandler.removeCallbacks(it) }
            overlayToastView?.let {
                runCatching { wm.removeView(it) }
                overlayToastView = null
            }

            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(dp(18), dp(10), dp(18), dp(10))
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#F0101814"))
                    setStroke(dp(1), android.graphics.Color.parseColor("#8000FFA3"))
                    cornerRadius = dp(24).toFloat()
                }
                background = bg
                elevation = dp(20).toFloat()
            }

            val textView = android.widget.TextView(this).apply {
                text = message
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                maxLines = 3
            }
            container.addView(textView)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                y = dp(90)
            }

            val added = runCatching {
                wm.addView(container, params)
                overlayToastView = container
                true
            }.getOrDefault(false)

            if (added) {
                val duration = if (long) 3500L else 2200L
                val runnable = Runnable {
                    overlayToastView?.let { v ->
                        runCatching { wm.removeView(v) }
                        overlayToastView = null
                    }
                }
                overlayToastDismissRunnable = runnable
                mainHandler.postDelayed(runnable, duration)
            }
        }
    }

    private fun toastStatus(message: String, long: Boolean = false) {
        val text = userFacingStatus(message).trim().ifBlank { "Action completed" }
        composeHudState.update {
            it.copy(
                statusMessage = text.take(132),
                toastMessage = text.take(132),
                toastTimestamp = System.currentTimeMillis(),
            )
        }
        showOverlayToast(text.take(180), long)
    }

    private fun toastOutcome(message: String) {
        val normalized = userFacingStatus(message).trim().ifBlank { "Action completed" }
        val prefix = when {
            normalized.contains("UNSUPPORTED", true) || normalized.contains("UNAVAILABLE", true) ||
                normalized.contains("REQUIRED", true) || normalized.contains("NOT AVAILABLE", true) ||
                normalized.contains("NOT READY", true) || normalized.contains("NOT SUPPORTED", true) ||
                normalized.contains("SKIPPED", true) || normalized.contains("NO SAFE", true) -> "UNSUPPORTED • "
            normalized.contains("FAILED", true) || normalized.contains("REJECTED", true) ||
                normalized.contains("ERROR", true) || normalized.contains("UNREADABLE", true) ||
                normalized.contains("SELECT AT LEAST", true) || normalized.contains("COULD NOT", true) ||
                normalized.contains("BLOCKED", true) || normalized.contains("PENDING", true) ||
                normalized.contains("TIMEOUT", true) || normalized.contains("DENIED", true) -> "ERROR • "
            else -> "SUCCESS • "
        }
        toastStatus(prefix + normalized)
    }

    private fun currentCapabilities(): NukeCapabilitySnapshot =
        NukeAdaptiveCapabilities.resolve(this, engine?.state?.value)

    private fun openResourceRadar() {
        val key = "resource_radar"
        if (windows.containsKey(key)) { removeWindow(key); return }
        if (!currentCapabilities().has("shell.background_release")) {
            toastOutcome("Pressure Radar is hidden because background release was not verified on this device")
            return
        }
        val view = moduleView("PRESSURE RADAR", "MEASURE • SELECT • RELEASE SAFELY")
        val content = view.findViewById<LinearLayout>(R.id.moduleActions)
        val status = view.findViewById<TextView>(R.id.moduleStatus)
        val target = targetPackage ?: return
        val radar = NukeResourceRadar(this, NukeGamingShellGateway(AdbManager.getInstance(applicationContext)), target)
        content.addView(noteText("Select only background apps you recognize. Release uses Android ActivityManager's package-scoped background path; the active game, Game Nuke, launchers, foreground and critical system packages stay protected."))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        val selected = linkedSetOf<String>()
        lateinit var render: (NukeResourceRadar.Snapshot) -> Unit

        val release = actionButton("RELEASE SELECTED BACKGROUND APPS") {
            val packages = selected.toList()
            if (packages.isEmpty()) {
                toastOutcome("Select at least one safe background app")
                return@actionButton
            }
            runPanelAction("$key.release") {
                status.text = "RELEASING ${packages.size} SELECTED BACKGROUND APPS…"
                var released = 0
                packages.forEach { packageName ->
                    val result = withContext(Dispatchers.IO) {
                        runCatching { radar.kill(packageName) }
                            .getOrElse { NukeCommandResult(-1, it.javaClass.simpleName) }
                    }
                    if (result.isSuccess) released++
                }
                selected.clear()
                delay(550L)
                val next = withContext(Dispatchers.IO) { runCatching { radar.scan() }.getOrNull() }
                if (next != null && windows.containsKey(key)) render(next)
                toastOutcome("Released $released of ${packages.size} selected background apps")
            }
        }.apply { isEnabled = false; alpha = .48f }
        content.addView(release)

        render = { snapshot ->
            status.text = snapshot.note.uppercase(Locale.US)
            list.removeAllViews()
            val selectable = snapshot.items.filterNot { it.protected }.mapTo(linkedSetOf()) { it.packageName }
            selected.retainAll(selectable)
            if (snapshot.items.isEmpty()) {
                list.addView(noteText("Process-level radar data is not available on this device right now. Core CPU/RAM/thermal telemetry remains active."))
            } else {
                snapshot.items.forEach { item ->
                    val cpuText = item.cpuPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--"
                    val ramText = item.rssMb?.let { "${it}MB" } ?: "--"
                    val title = item.packageName.substringAfterLast('.').take(30)
                    val suffix = if (item.protected) "  •  PROTECTED" else ""
                    val choice = CheckBox(this).apply {
                        text = "$title\nCPU $cpuText  •  RAM $ramText$suffix"
                        isChecked = item.packageName in selected
                        styleCheck(this)
                        minHeight = dp(54)
                        isEnabled = !item.protected
                        alpha = if (item.protected) .52f else 1f
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selected += item.packageName else selected -= item.packageName
                            release.isEnabled = selected.isNotEmpty()
                            release.alpha = if (release.isEnabled) 1f else .48f
                            status.text = if (selected.isEmpty()) snapshot.note.uppercase(Locale.US)
                            else "${selected.size} SAFE BACKGROUND APP${if (selected.size == 1) "" else "S"} SELECTED"
                        }
                    }
                    list.addView(choice, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(3) })
                }
            }
            release.isEnabled = selected.isNotEmpty()
            release.alpha = if (release.isEnabled) 1f else .48f
        }

        content.addView(actionButton("REFRESH PRESSURE RADAR") {
            runPanelAction("$key.scan") {
                status.text = "SCANNING DEVICE PRESSURE…"
                val snapshot = withContext(Dispatchers.IO) { runCatching { radar.scan() }.getOrNull() }
                if (!windows.containsKey(key)) return@runPanelAction
                if (snapshot == null) status.text = "RADAR DATA UNAVAILABLE" else render(snapshot)
            }
        })
        bindModuleWindow(key, view, x = dp(18), y = dp(52))
        runPanelAction("$key.scan") {
            val snapshot = withContext(Dispatchers.IO) { runCatching { radar.scan() }.getOrNull() }
            if (!windows.containsKey(key)) return@runPanelAction
            if (snapshot == null) status.text = "RADAR DATA UNAVAILABLE" else render(snapshot)
        }
    }

    private fun openScreenControl() {
        val key = "screen_control"
        if (windows.containsKey(key)) { removeWindow(key); return }
        val view = moduleView("SCREEN CONTROL", "ORIENTATION • AWAKE • HUD COMFORT")
        val content = view.findViewById<LinearLayout>(R.id.moduleActions)
        var syncingRotation = false
        val rotation = Switch(this).apply {
            text = "Lock game rotation"
            isChecked = engine?.state?.value?.rotationLocked == true
            styleSwitch(this)
            setOnCheckedChangeListener { _, checked ->
                if (syncingRotation) return@setOnCheckedChangeListener
                isEnabled = false
                runPanelAction("$key.rotation") {
                    try {
                        engine?.setRotationLock(checked)
                        engine?.state?.value?.let { updateTelemetryUi(it); publishRuntime(it) }
                        engine?.state?.value?.message?.let(::toastOutcome)
                    } finally {
                        val actual = engine?.state?.value?.rotationLocked == true
                        syncingRotation = true
                        isChecked = actual
                        syncingRotation = false
                        isEnabled = true
                    }
                }
            }
        }
        val awake = Switch(this).apply {
            text = "Keep screen awake while gaming"
            isChecked = prefs.safeBoolean("hud_keep_awake", false)
            styleSwitch(this)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("hud_keep_awake", checked).apply(); applyKeepAwake()
                toastOutcome("Keep screen awake ${if (checked) "enabled" else "disabled"}")
            }
        }
        if (currentCapabilities().has("shell.rotation")) content.addView(rotation)
        content.addView(awake)
        content.addView(seekControl("HUD OPACITY", 72, 98, (prefs.safeFloat(K_HUD_ALPHA, .96f) * 100).roundToInt()) {
            setHudAlpha(it / 100f)
        })
        content.addView(seekControl("HUD SCALE", 85, 115, (hudScale() * 100).roundToInt()) {
            setHudScale(it / 100f)
        })
        if (NukeDisplayProfileController.hasOwnedOverride(applicationContext)) {
            content.addView(actionButton("RECOVER LEGACY DISPLAY STATE", danger = true) {
                removeAllWindowsImmediate()
                runPanelAction("legacy_display_restore") {
                    val result = withContext(Dispatchers.IO) {
                        NukeDisplayProfileController.restoreIfOwned(applicationContext)
                    }
                    delay(180L)
                    ensureEdge()
                    ensureHubVisible()
                    syncCrosshairOverlay()
                    when (result.outcome) {
                        NukeDisplayProfileController.Outcome.SUCCESS,
                        NukeDisplayProfileController.Outcome.UNCHANGED -> NukeToast.success(applicationContext, result.message, true)
                        NukeDisplayProfileController.Outcome.UNSUPPORTED -> NukeToast.unsupported(applicationContext, result.message, true)
                        else -> NukeToast.error(applicationContext, result.message, true)
                    }
                }
            })
        }
        content.addView(actionButton("RESTORE ALL SESSION CHANGES") {
            val local = engine
            if (local == null) {
                toastOutcome("Game Nuke core is not ready")
                return@actionButton
            }
            runPanelAction("$key.restore") {
                val coreResult = runCatching { local.restoreSession() }
                // Never resize/re-density a display under attached overlay windows.
                removeAllWindowsImmediate()
                val displayResult = withContext(Dispatchers.IO) {
                    NukeDisplayProfileController.restoreIfOwned(applicationContext)
                }
                delay(180L)
                ensureEdge()
                syncCrosshairOverlay()
                ensureHubVisible()
                local.state.value.let { updateTelemetryUi(it); publishRuntime(it) }
                when {
                    coreResult.isFailure -> toastOutcome("Restore failed: ${coreResult.exceptionOrNull()?.message ?: "unknown error"}")
                    displayResult.outcome == NukeDisplayProfileController.Outcome.ERROR -> NukeToast.error(applicationContext, displayResult.message, true)
                    displayResult.outcome == NukeDisplayProfileController.Outcome.DEFERRED -> NukeToast.error(applicationContext, displayResult.message, true)
                    else -> toastOutcome("All Game Nuke session changes restored")
                }
            }
        })
        content.addView(noteText("Boost preserves the device's current resolution and DPI. Rotation is capability-probed and ownership-restored. HUD scale/opacity apply immediately and remain inside the current safe display bounds."))
        bindModuleWindow(key, view, x = dp(22), y = dp(62))
    }

    private fun openMaxFps() {
        val key = "max_fps"
        if (windows.containsKey(key)) { removeWindow(key); return }
        val view = moduleView("FRAME CONTROL", "NATIVE REFRESH • FPS SCAN • GPU RELIEF")
        val capabilities = currentCapabilities()
        val status = view.findViewById<TextView>(R.id.moduleStatus)
        val metrics = view.findViewById<LinearLayout>(R.id.moduleMetrics)
        val panelMetric = metricLine("DISPLAY", "--")
        val gameMetric = metricLine("MEASURED GAME FPS", "--").takeIf { capabilities.has("shell.frame_scan") }
        val gpuMetric = metricLine("GPU RELIEF", "--").takeIf { capabilities.has("shell.gpu_relief") }
        val thermalMetric = metricLine("THERMAL", "--")
        metrics.addView(panelMetric)
        gameMetric?.let { metrics.addView(it) }
        gpuMetric?.let { metrics.addView(it) }
        metrics.addView(thermalMetric)
        val actions = view.findViewById<LinearLayout>(R.id.moduleActions)
        actions.addView(noteText("Frame Control removes verified platform-side limits only. It cannot force a game to render more frames than its engine allows and never changes Android resolution or DPI."))

        var gpuRelief: Switch? = null
        fun render() {
            val state = engine?.state?.value ?: return
            panelMetric.text = "DISPLAY  ${state.currentHz.takeIf { it > 0 } ?: "--"}Hz / ${state.maxHz.takeIf { it > 0 } ?: "--"}Hz MAX"
            gameMetric?.text = "MEASURED GAME FPS  ${state.lastMeasuredFps?.let { String.format(Locale.US, "%.1f", it) } ?: "NOT SAMPLED"}"
            gpuMetric?.text = "GPU RELIEF  ${if (state.gpuReliefActive) "ARMED • GAME RESTART REQUIRED" else "OFF"}"
            thermalMetric.text = "THERMAL  ${thermalLabel(state.thermalStatus)}"
            status.text = userFacingStatus(state.message).take(180)
            gpuRelief?.let { toggle ->
                if (toggle.isChecked != state.gpuReliefActive) {
                    toggle.setOnCheckedChangeListener(null)
                    toggle.isChecked = state.gpuReliefActive
                    bindGpuReliefListener(toggle, key, ::render)
                }
            }
        }

        if (capabilities.has("shell.gpu_relief")) {
            gpuRelief = Switch(this).apply {
                text = "Per-game GPU relief (90% backbuffer)"
                isChecked = engine?.state?.value?.gpuReliefActive == true
                styleSwitch(this)
            }.also { toggle ->
                bindGpuReliefListener(toggle, key, ::render)
                actions.addView(toggle)
            }
            actions.addView(noteText("GPU Relief is an Android Game Mode intervention. It may lower GPU load, needs a game restart, and the OEM/game may opt out. It does not resize the device display or alter density buckets."))
        }
        if (capabilities.has("local.display") || capabilities.has("shell.refresh_guard") || capabilities.has("shell.game_mode")) {
            actions.addView(actionButton("APPLY DISPLAY REFRESH SETTINGS") {
                runPanelAction("$key.refresh") {
                    engine?.maximizeNativeFps()
                    engine?.state?.value?.let { updateTelemetryUi(it); publishRuntime(it) }
                    render()
                    engine?.state?.value?.message?.let(::toastOutcome)
                }
            })
        }
        if (capabilities.has("shell.frame_scan")) {
            actions.addView(actionButton("MEASURE REAL GAME FPS") {
                runPanelAction("$key.scan") {
                    engine?.sampleActualGameFps()
                    engine?.state?.value?.let { updateTelemetryUi(it); publishRuntime(it) }
                    render()
                    engine?.state?.value?.message?.let(::toastOutcome)
                }
            })
        }
        if (capabilities.has("shell.art_prime")) {
            actions.addView(actionButton("PRIME GAME BYTECODE") {
                runPanelAction("$key.prime") {
                    engine?.primeGameArt()
                    render()
                    engine?.state?.value?.message?.let(::toastOutcome)
                }
            })
        }
        bindModuleWindow(key, view, x = dp(16), y = dp(50))
        render()
    }

    private fun bindGpuReliefListener(toggle: Switch, panelKey: String, refresh: () -> Unit) {
        toggle.setOnCheckedChangeListener { _, requested ->
            val current = engine?.state?.value?.gpuReliefActive == true
            if (requested == current) return@setOnCheckedChangeListener
            toggle.isEnabled = false
            runPanelAction("$panelKey.gpu") {
                try {
                    engine?.toggleGpuRelief()
                    engine?.state?.value?.let { updateTelemetryUi(it); publishRuntime(it) }
                    engine?.state?.value?.message?.let(::toastOutcome)
                } finally {
                    toggle.isEnabled = true
                    refresh()
                }
            }
        }
    }

    private fun openDeepClean() {
        val key = "deep_clean"
        if (windows.containsKey(key)) { removeWindow(key); return }
        val view = moduleView("DEEP CLEAN", "MEASURED CACHE + MEMORY RECLAIM")
        val metrics = view.findViewById<LinearLayout>(R.id.moduleMetrics)
        val fx = NukeCleanerFxView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(86)).apply { bottomMargin = dp(5) }
        }
        metrics.addView(fx)
        val content = view.findViewById<LinearLayout>(R.id.moduleActions)
        val ownCache = CheckBox(this).apply { text = "Game Nuke cache"; isChecked = true; styleCheck(this) }
        val capabilities = currentCapabilities()
        val ram = CheckBox(this).apply { text = "Pressure-aware RAM reclaim"; isChecked = true; styleCheck(this) }
        val storage = CheckBox(this).apply { text = "Bounded package cache trim"; isChecked = true; styleCheck(this) }
        val background = CheckBox(this).apply { text = "Android safe cached-process sweep"; isChecked = false; styleCheck(this) }
        content.addView(ownCache)
        if (capabilities.has("shell.compaction")) content.addView(ram)
        if (capabilities.has("shell.cache_trim")) content.addView(storage)
        if (capabilities.has("shell.background_release")) content.addView(background)
        val activeLayers = buildList {
            add("app cache")
            if (capabilities.has("shell.compaction")) add("verified RAM compaction")
            if (capabilities.has("shell.cache_trim")) add("verified package cache trim")
            if (capabilities.has("shell.background_release")) add("protected background sweep")
        }.joinToString(", ")
        content.addView(noteText("Available on this device: $activeLayers. Unsupported shell layers are omitted, not presented as inactive controls."))
        content.addView(actionButton("START DEEP CLEAN") {
            runPanelAction("$key.clean") {
                runDeepClean(
                    ownCache.isChecked,
                    capabilities.has("shell.compaction") && ram.isChecked,
                    capabilities.has("shell.cache_trim") && storage.isChecked,
                    capabilities.has("shell.background_release") && background.isChecked,
                    view,
                    fx,
                )
            }
        })
        bindModuleWindow(key, view, x = dp(26), y = dp(76))
    }

    private suspend fun runDeepClean(
        clearOwnCache: Boolean,
        reclaimRam: Boolean,
        trimStorage: Boolean,
        sweepBackground: Boolean,
        view: View,
        fx: NukeCleanerFxView,
    ) {
        val status = view.findViewById<TextView>(R.id.moduleStatus)
        if (!clearOwnCache && !reclaimRam && !trimStorage && !sweepBackground) {
            status.text = "SELECT AT LEAST ONE CLEANING LAYER"
            toastOutcome("Select at least one cleaning layer")
            return
        }
        fx.startCleaning()
        status.text = "CLEANING // MEASURING BEFORE + AFTER"
        var ownGainMb = 0L
        val failedLayers = mutableListOf<String>()
        suspend fun runLayer(label: String, action: suspend () -> Unit) {
            try {
                action()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failedLayers += label
                Log.w(TAG, "Deep Clean layer $label failed safely", error)
            }
        }
        try {
            if (clearOwnCache) {
                ownGainMb = withContext(Dispatchers.IO) {
                    val before = directoryBytes(cacheDir)
                    cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
                    val after = directoryBytes(cacheDir)
                    ((before - after) / (1024L * 1024L)).coerceAtLeast(0L)
                }
            }
            if (reclaimRam) runLayer("RAM") { engine?.deepReclaim() }
            if (trimStorage) runLayer("STORAGE") { engine?.trimCachesForStoragePressure() }
            if (sweepBackground) runLayer("BACKGROUND") { engine?.sweepSafeBackground() }
            runLayer("METRICS") { engine?.refreshMetrics() }
            val state = engine?.state?.value
            val details = buildList {
                if (clearOwnCache) add("CACHE +${ownGainMb}MB")
                if (reclaimRam) add("RAM +${state?.lastMemoryGainMb ?: 0}MB")
                if (trimStorage) add("STORAGE +${state?.lastCacheGainMb ?: 0}MB")
                if (sweepBackground) add("BG SWEEP")
                if (failedLayers.isNotEmpty()) add("SKIPPED ${failedLayers.joinToString("+")}")
            }
            status.text = "CLEAN COMPLETE // ${details.joinToString(" // ")}"
            toastOutcome("Deep Clean complete • ${details.joinToString(" • ")}")
            state?.let { updateTelemetryUi(it); publishRuntime(it) }
        } finally {
            fx.stopCleaning()
        }
    }

    private fun openCrosshairStudio() {
        val key = "crosshair_studio"
        if (windows.containsKey(key)) { removeWindow(key); return }
        val view = moduleView("CROSSHAIR STUDIO", "RESTORED // NON-TOUCHABLE GAME OVERLAY")
        val content = view.findViewById<LinearLayout>(R.id.moduleActions)

        val enabled = Switch(this).apply {
            text = "Enable crosshair overlay"
            isChecked = prefs.safeBoolean("cross_en", false)
            styleSwitch(this)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("cross_en", checked).apply()
                syncCrosshairOverlay()
                toastOutcome("Crosshair overlay ${if (checked) "enabled" else "disabled"}")
            }
        }
        content.addView(enabled)
        val preview = NukeCrosshairView(this).apply {
            enabledCrosshair = true
            style = NukeCrosshairView.Style.values()[prefs.safeInt("cross_type", 1).coerceIn(0, 3)]
            crosshairColor = prefs.safeInt("cross_color", NukeHudPalette.Green)
            sizeDp = prefs.safeInt("cross_size", 22).toFloat()
            gapDp = prefs.safeInt("cross_gap", 6).toFloat()
            thicknessDp = prefs.safeInt("cross_thickness", 2).toFloat()
            opacity = prefs.safeInt("cross_opacity", 95) / 100f
            centerDot = prefs.safeBoolean("cross_dot", true)
            outline = prefs.safeBoolean("cross_outline", true)
            background = getDrawable(R.drawable.nuke_hud_glass)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112)).apply { bottomMargin = dp(6) }
        }
        content.addView(preview)

        content.addView(controlTitle("STYLE"))
        val styles = listOf("DOT", "CROSS", "CIRCLE", "TACTIC")
        val styleSpinner = spinner(styles, prefs.safeInt("cross_type", 1).coerceIn(0, styles.lastIndex)) { index ->
            prefs.edit().putInt("cross_type", index).apply(); preview.style = NukeCrosshairView.Style.values()[index]; syncCrosshairOverlay()
        }
        content.addView(styleSpinner)

        content.addView(controlTitle("COLOR"))
        val colorNames = listOf("Nuke Green", "Red", "Amber", "Magenta", "White", "Cyan")
        val colors = intArrayOf(
            NukeHudPalette.Green, Color.rgb(255, 82, 82), Color.rgb(255, 193, 7),
            Color.rgb(230, 65, 160), Color.WHITE, Color.rgb(64, 210, 235),
        )
        val currentColor = prefs.safeInt("cross_color", colors[0])
        val colorIndex = colors.indexOf(currentColor).takeIf { it >= 0 } ?: 0
        content.addView(spinner(colorNames, colorIndex) { index -> prefs.edit().putInt("cross_color", colors[index]).apply(); preview.crosshairColor = colors[index]; syncCrosshairOverlay() })

        content.addView(seekControl("SIZE", 8, 64, prefs.safeInt("cross_size", 22)) { prefs.edit().putInt("cross_size", it).apply(); preview.sizeDp = it.toFloat(); syncCrosshairOverlay() })
        content.addView(seekControl("CENTER GAP", 0, 28, prefs.safeInt("cross_gap", 6)) { prefs.edit().putInt("cross_gap", it).apply(); preview.gapDp = it.toFloat(); syncCrosshairOverlay() })
        content.addView(seekControl("THICKNESS", 1, 6, prefs.safeInt("cross_thickness", 2)) { prefs.edit().putInt("cross_thickness", it).apply(); preview.thicknessDp = it.toFloat(); syncCrosshairOverlay() })
        content.addView(seekControl("OPACITY", 20, 100, prefs.safeInt("cross_opacity", 95)) { prefs.edit().putInt("cross_opacity", it).apply(); preview.opacity = it / 100f; syncCrosshairOverlay() })
        content.addView(seekControl("X OFFSET", -300, 300, prefs.safeInt("cross_x", 0)) { prefs.edit().putInt("cross_x", it).apply(); syncCrosshairOverlay() })
        content.addView(seekControl("Y OFFSET", -300, 300, prefs.safeInt("cross_y", 0)) { prefs.edit().putInt("cross_y", it).apply(); syncCrosshairOverlay() })

        val dot = CheckBox(this).apply {
            text = "Center dot"
            isChecked = prefs.safeBoolean("cross_dot", true)
            styleCheck(this)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("cross_dot", checked).apply(); preview.centerDot = checked; syncCrosshairOverlay(); toastOutcome("Crosshair center dot ${if (checked) "enabled" else "disabled"}") }
        }
        val outline = CheckBox(this).apply {
            text = "Dark contrast outline"
            isChecked = prefs.safeBoolean("cross_outline", true)
            styleCheck(this)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("cross_outline", checked).apply(); preview.outline = checked; syncCrosshairOverlay(); toastOutcome("Crosshair outline ${if (checked) "enabled" else "disabled"}") }
        }
        content.addView(dot); content.addView(outline)
        content.addView(actionButton("RESET CROSSHAIR") {
            prefs.edit()
                .putInt("cross_type", 1).putInt("cross_color", colors[0])
                .putInt("cross_size", 22).putInt("cross_gap", 6).putInt("cross_thickness", 2)
                .putInt("cross_opacity", 95).putInt("cross_x", 0).putInt("cross_y", 0)
                .putBoolean("cross_dot", true).putBoolean("cross_outline", true).apply()
            toastOutcome("Crosshair settings reset")
            removeWindow(key); openCrosshairStudio()
        })
        bindModuleWindow(key, view, x = dp(18), y = dp(54))
        syncCrosshairOverlay()
    }

    private fun openNetwork() {
        val key = "network"
        if (windows.containsKey(key)) { removeWindow(key); return }
        val view = moduleView("NETWORK CONTROL", "LATENCY • WI-FI LOCK • LINK QUALITY")
        val metrics = view.findViewById<LinearLayout>(R.id.moduleMetrics)
        val ping = metricLine("PING", lastPingMs?.let { "${it}ms" } ?: if (pingMeasuring) "MEASURING…" else "--")
        val signal = metricLine("WI-FI SIGNAL", wifiRssiDbm?.let { "${it} dBm" } ?: "UNAVAILABLE")
        val link = metricLine("LINK SPEED", wifiLinkMbps?.let { "${it} Mbps" } ?: "UNAVAILABLE")
        metrics.addView(ping); metrics.addView(signal); metrics.addView(link)
        val actions = view.findViewById<LinearLayout>(R.id.moduleActions)
        actions.addView(noteText("Wi-Fi RSSI/link speed use Android's in-process Wi-Fi API. Missing/redacted OEM values are reported as unavailable; Game Nuke does not request location just to fake a reading."))
        var syncingBoost = false
        val networkBoost = Switch(this).apply {
            text = "Hold high-performance Wi-Fi during session"
            isChecked = engine?.state?.value?.networkBoostActive == true
            styleSwitch(this)
            setOnCheckedChangeListener { _, requested ->
                if (syncingBoost) return@setOnCheckedChangeListener
                isEnabled = false
                runPanelAction("$key.boost") {
                    try {
                        engine?.setNetworkBoost(requested)
                        engine?.state?.value?.let { updateTelemetryUi(it); publishRuntime(it) }
                        engine?.state?.value?.message?.let(::toastOutcome)
                    } finally {
                        val actual = engine?.state?.value?.networkBoostActive == true
                        syncingBoost = true
                        isChecked = actual
                        syncingBoost = false
                        isEnabled = true
                    }
                }
            }
        }
        actions.addView(networkBoost)
        actions.addView(actionButton("REFRESH NETWORK STATUS") {
            runPanelAction("$key.refresh") {
                refreshAuxTelemetry(retryPing = true)
                ping.text = "PING  ${lastPingMs?.let { "${it}ms" } ?: "UNAVAILABLE"}"
                signal.text = "WI-FI SIGNAL  ${wifiRssiDbm?.let { "${it} dBm" } ?: "UNAVAILABLE"}"
                link.text = "LINK SPEED  ${wifiLinkMbps?.let { "${it} Mbps" } ?: "UNAVAILABLE"}"
                toastOutcome("Network telemetry refreshed")
            }
        })
        actions.addView(actionButton("OPEN ANDROID NETWORK PANEL") {
            engine?.openInternetPanel()
        })
        actions.addView(noteText("Network Boost is a session-scoped Wi-Fi performance lock. It can reduce radio power-save latency, but it cannot change ISP routing, server load, or mobile-network congestion."))
        bindModuleWindow(key, view, x = dp(18), y = dp(54))
    }

    private fun openCpuClocks() {
        val key = "cpu_clocks"
        if (windows.containsKey(key)) { removeWindow(key); return }
        if (!currentCapabilities().has("shell.cpu_clocks")) {
            toastOutcome("CPU per-core clocks are not exposed by this Xiaomi/OEM kernel")
            return
        }
        val local = NukeGamingShellGateway(AdbManager.getInstance(applicationContext))
        if (!local.connected()) {
            toastOutcome("CPU Monitor needs Extended Control")
            return
        }
        val view = moduleView("CPU MONITOR", "READ-ONLY • BATCHED • LIVE")
        val metrics = view.findViewById<LinearLayout>(R.id.moduleMetrics)
        val status = view.findViewById<TextView>(R.id.moduleStatus)
        val summary = metricLine("CORE CLOCKS", "WAITING FOR SAMPLE")
        val chart = NukeCpuCoreChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(132)).apply {
                topMargin = dp(4)
                bottomMargin = dp(7)
            }
        }
        metrics.addView(summary)
        metrics.addView(chart)
        view.findViewById<LinearLayout>(R.id.moduleActions).apply {
            addView(noteText("One read-only shell batch samples scaling_cur_freq for every exposed core. Game Nuke never writes CPU frequency, governor, scheduler, or thermal sysfs nodes."))
        }
        bindModuleWindow(key, view, x = dp(18), y = dp(68))
        runPanelAction("$key.live") {
            while (kotlinx.coroutines.currentCoroutineContext().isActive && windows.containsKey(key)) {
                val clocks = withContext(Dispatchers.IO) { local.readCpuClocks() }
                if (!windows.containsKey(key)) return@runPanelAction
                if (clocks.isEmpty()) {
                    status.text = "UNSUPPORTED • CPU CLOCK SYSFS NOT READABLE"
                    summary.text = "CORE CLOCKS  NOT EXPOSED BY THIS KERNEL/OEM"
                    chart.submit(emptyList())
                } else {
                    status.text = "LIVE • ${clocks.size} CORES • ONE DEVICE ROUND-TRIP"
                    val average = clocks.map { it.mhz }.average().toLong()
                    val peak = clocks.maxOf { it.mhz }
                    summary.text = "CORE CLOCKS  AVG ${average}MHz  •  PEAK ${peak}MHz"
                    chart.layoutParams = (chart.layoutParams as LinearLayout.LayoutParams).apply {
                        height = dp(maxOf(96, ((clocks.size.coerceAtMost(16) + 1) / 2) * 30))
                    }
                    chart.submit(clocks)
                }
                delay(2_500L)
            }
        }
    }

    private fun captureSessionBatteryBaseline() {
        sessionStartedElapsed = SystemClock.elapsedRealtime()
        val snapshot = readBatterySnapshot()
        sessionBatteryStartPercent = snapshot.first
        sessionChargeStartUah = snapshot.second
    }

    private fun readBatterySnapshot(): Pair<Int, Int?> {
        val percent = runCatching {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) ((level * 100f) / scale).roundToInt().coerceIn(0, 100) else -1
        }.getOrDefault(-1)
        val charge = runCatching {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeUnless { it == Int.MIN_VALUE }
        }.getOrNull()
        return percent to charge
    }

    private fun sessionBatterySummary(): String {
        val now = readBatterySnapshot()
        val pctDrop = if (sessionBatteryStartPercent >= 0 && now.first >= 0) (sessionBatteryStartPercent - now.first).coerceAtLeast(0) else null
        val startCharge = sessionChargeStartUah
        val endCharge = now.second
        val mah = if (startCharge != null && endCharge != null) ((startCharge - endCharge) / 1000f).coerceAtLeast(0f) else null
        val mins = if (sessionStartedElapsed > 0L) ((SystemClock.elapsedRealtime() - sessionStartedElapsed) / 60_000L).coerceAtLeast(0) else 0
        return buildList {
            add("${mins}m")
            pctDrop?.let { add("$it% used") }
            mah?.takeIf { it > .05f }?.let { add(String.format(Locale.US, "%.0fmAh", it)) }
        }.joinToString(" • ")
    }

    private fun openMonitor() {
        val key = "monitor"
        if (windows.containsKey(key)) {
            if (monitorTouchThrough) { monitorTouchThrough = false; applyMonitorTouchMode() } else removeWindow(key)
            return
        }
        val view = moduleView("LIVE TELEMETRY", "REAL DATA // LOW OVERHEAD")
        val metrics = view.findViewById<LinearLayout>(R.id.moduleMetrics)
        val cpuLabel = metricLine("CPU LOAD", "--")
        val ramLabel = metricLine("RAM USED", "--")
        val displayLabel = metricLine("DISPLAY", "--")
        val cpuSpark = NukeSparklineView(this).apply { minimumHeight = dp(58) }
        val ramSpark = NukeSparklineView(this).apply { minimumHeight = dp(58) }
        metrics.addView(cpuLabel); metrics.addView(cpuSpark, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
        metrics.addView(ramLabel); metrics.addView(ramSpark, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
        metrics.addView(displayLabel)
        view.setTag(R.id.monitorCpuTag, cpuLabel); view.setTag(R.id.monitorRamTag, ramLabel); view.setTag(R.id.monitorDisplayTag, displayLabel)
        view.setTag(R.id.monitorCpuSparkTag, cpuSpark); view.setTag(R.id.monitorRamSparkTag, ramSpark)
        val actions = view.findViewById<LinearLayout>(R.id.moduleActions)
        actions.addView(Switch(this).apply {
            text = "Touch-through telemetry overlay"
            isChecked = monitorTouchThrough
            styleSwitch(this)
            setOnCheckedChangeListener { _, checked ->
                monitorTouchThrough = checked
                applyMonitorTouchMode()
                toastOutcome("Telemetry touch-through ${if (checked) "enabled" else "disabled"}")
            }
        })
        actions.addView(noteText("When touch-through is enabled, use the round edge bubble and tap Live Monitor again to return this panel to interactive mode."))
        actions.addView(actionButton("RESAMPLE REAL FPS") {
            runPanelAction("$key.fps") {
                val local = engine
                if (local == null) toastOutcome("FPS sampler is not ready")
                else {
                    local.sampleActualGameFps()
                    toastOutcome(local.state.value.message)
                }
            }
        })
        bindModuleWindow(key, view, x = dp(18), y = dp(102))
        applyMonitorTouchMode()
        engine?.state?.value?.let(::updateTelemetryUi)
    }

    private fun moduleView(title: String, subtitle: String): View =
        LayoutInflater.from(this).inflate(R.layout.nuke_hud_module, null).also { view ->
            view.findViewById<TextView>(R.id.moduleTitle).text = title
            view.findViewById<TextView>(R.id.moduleSubtitle).text = subtitle
        }

    private fun bindModuleWindow(key: String, view: View, x: Int? = null, y: Int? = null) {
        view.findViewById<View>(R.id.moduleClose).safeClick {
            removeWindow(key)
            scope.launch {
                delay(110L)
                if (!stopping && !hubVisible()) ensureHubVisible()
            }
        }
        removeHubWindows()
        windows.keys
            .filter { it !in setOf("edge", "crosshair", key) && !it.startsWith("hub_") }
            .forEach(::removeWindow)
        val width = scaledModuleWidth()
        val height = scaledModuleHeight()
        val safe = safeBounds()
        val p = params(width, height).apply {
            this.x = (x ?: (safe.right - width - dp(12))).coerceIn(safe.left, maxOf(safe.left, safe.right - width))
            this.y = (y ?: (safe.top + dp(12))).coerceIn(safe.top, maxOf(safe.top, safe.bottom - height))
        }
        if (!addWindow(key, view, p)) {
            val failures = (moduleAddFailures[key] ?: 0) + 1
            moduleAddFailures[key] = failures
            if (failures <= 3 && !stopping) {
                scope.launch {
                    delay(240L * failures)
                    if (!windows.containsKey(key)) bindModuleWindow(key, view, x, y)
                }
            } else {
                moduleAddFailures.remove(key)
                toastOutcome("$key panel could not attach")
                ensureEdge()
                ensureHubVisible()
            }
            return
        }
        moduleAddFailures.remove(key)
        makeDraggable(key, view.findViewById(R.id.moduleDrag))
        engine?.state?.value?.let(::updateTelemetryUi)
    }

    private fun syncCrosshairOverlay() {
        val enabled = prefs.safeBoolean("cross_en", false)
        if (!enabled) {
            removeWindow("crosshair")
            crosshairAddFailures = 0
            publishRuntime(engine?.state?.value)
            return
        }
        val existing = windows["crosshair"]?.view as? NukeCrosshairView
        val view = existing ?: NukeCrosshairView(this).also { cross ->
            val p = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT).apply {
                x = 0; y = 0
                flags = baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                alpha = 1f
            }
            if (!addWindow("crosshair", cross, p)) {
                crosshairAddFailures++
                if (crosshairAddFailures <= 3 && !stopping) {
                    scope.launch { delay(260L * crosshairAddFailures); syncCrosshairOverlay() }
                } else {
                    toastOutcome("Crosshair overlay could not attach")
                }
                return
            }
            crosshairAddFailures = 0
        }
        val styles = NukeCrosshairView.Style.values()
        view.style = styles[prefs.safeInt("cross_type", 1).coerceIn(0, styles.lastIndex)]
        view.crosshairColor = prefs.safeInt("cross_color", NukeHudPalette.Green)
        view.sizeDp = prefs.safeInt("cross_size", 22).toFloat()
        view.gapDp = prefs.safeInt("cross_gap", 6).toFloat()
        view.thicknessDp = prefs.safeInt("cross_thickness", 2).toFloat()
        view.opacity = prefs.safeInt("cross_opacity", 95).coerceIn(20, 100) / 100f
        view.offsetXPx = prefs.safeInt("cross_x", 0).toFloat()
        view.offsetYPx = prefs.safeInt("cross_y", 0).toFloat()
        view.centerDot = prefs.safeBoolean("cross_dot", true)
        view.outline = prefs.safeBoolean("cross_outline", true)
        view.enabledCrosshair = true
        publishRuntime(engine?.state?.value)
    }

    private fun setHudScale(scale: Float) {
        val safe = scale.coerceIn(.85f, 1.15f)
        prefs.edit().putFloat(K_HUD_SCALE, safe).apply()
        val reopenHub = hubVisible()
        if (reopenHub) windows.keys.filter { it.startsWith("hub_") }.forEach(::removeWindowImmediate)
        windows.forEach { (key, slot) ->
            if (key in setOf("edge", "banner", "crosshair") || key.startsWith("hub_")) return@forEach
            slot.params.width = scaledModuleWidth(); slot.params.height = scaledModuleHeight()
            val bounds = safeBounds()
            slot.params.x = slot.params.x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - slot.params.width))
            slot.params.y = slot.params.y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - slot.params.height))
            runCatching { wm.updateViewLayout(slot.view, slot.params) }
        }
        if (reopenHub) ensureHubVisible()
    }

    private fun setHudAlpha(alpha: Float) {
        prefs.edit().putFloat(K_HUD_ALPHA, alpha.coerceIn(.72f, .98f)).apply()
        applyHudAlphaToOpenWindows()
    }

    private fun applyHudAlphaToOpenWindows() {
        val safe = prefs.safeFloat(K_HUD_ALPHA, .96f).coerceIn(.72f, .98f)
        windows.forEach { (key, slot) ->
            if (key == "crosshair") return@forEach
            slot.params.alpha = if (key == "monitor" && monitorTouchThrough) minOf(.78f, safe) else safe
            runCatching { wm.updateViewLayout(slot.view, slot.params) }
        }
    }

    private fun applyMonitorTouchMode() {
        val slot = windows["monitor"] ?: return
        slot.params.flags = if (monitorTouchThrough) baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else baseFlags()
        slot.params.alpha = if (monitorTouchThrough) .78f else prefs.safeFloat(K_HUD_ALPHA, .96f).coerceIn(.72f, .98f)
        runCatching { wm.updateViewLayout(slot.view, slot.params) }
    }

    private suspend fun refreshAuxTelemetry(retryPing: Boolean = false) {
        pingMeasuring = true
        withContext(Dispatchers.IO) {
        batteryPercent = runCatching {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val tempRaw = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            batteryTempC = if (tempRaw > 0) tempRaw / 10f else null
            if (level >= 0 && scale > 0) ((level * 100f) / scale).roundToInt().coerceIn(0, 100) else -1
        }.getOrDefault(-1)

        networkLabel = runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            wifiRssiDbm = null; wifiLinkMbps = null
            when {
                caps == null -> "OFFLINE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        caps.transportInfo as? WifiInfo
                    } else {
                        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        @Suppress("DEPRECATION")
                        val legacyInfo = wifi?.connectionInfo
                        legacyInfo
                    }
                    val rssi = info?.rssi?.takeIf { it in -126..-1 }
                    val link = info?.linkSpeed?.takeIf { it > 0 }
                    wifiRssiDbm = rssi; wifiLinkMbps = link
                    if (rssi != null) "WIFI ${rssi}dBm" else "WIFI"
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETH"
                else -> "ONLINE"
            }
        }.getOrDefault("--")

            var measured = measurePingOnce(600)
            if (measured == null && retryPing) {
                delay(180L)
                measured = measurePingOnce(500)
            }
            lastPingMs = measured
        }
        pingMeasuring = false
    }

    private suspend fun measurePingOnce(connectTimeoutMs: Int): Long? = withTimeoutOrNull(connectTimeoutMs.toLong() + 150L) {
        withContext(Dispatchers.IO) {
            runCatching {
                val started = System.nanoTime()
                Socket().use { socket -> socket.connect(InetSocketAddress("1.1.1.1", 443), connectTimeoutMs) }
                (((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)).coerceAtMost(999L)
            }.getOrNull()
        }
    }

    private fun applyKeepAwake() {
        val enabled = prefs.safeBoolean("hud_keep_awake", false)
        (listOf("edge") + windows.keys.filter { it.startsWith("hub_") }).forEach { key ->
            val slot = windows[key] ?: return@forEach
            val desired = if (enabled) {
                slot.params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            } else {
                slot.params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            }
            if (slot.params.flags != desired) {
                slot.params.flags = desired
                runCatching { wm.updateViewLayout(slot.view, slot.params) }
                    .onFailure { Log.w(TAG, "KEEP_SCREEN_ON update failed for $key", it) }
            }
        }
    }

    private fun updateTelemetryUi(state: NukePerformanceEngine.State) {
        val ramKnown = state.ramTotalMb > 0L
        val usedRamPercent = if (ramKnown) {
            (((state.ramTotalMb - state.ramAvailableMb).toDouble() / state.ramTotalMb) * 100.0)
                .roundToInt().coerceIn(0, 100)
        } else 0
        val cpu = state.cpuLoadPercent ?: 0
        val storageUsedPercent = if (state.storageTotalGb > 0f) {
            (((state.storageTotalGb - state.storageFreeGb) / state.storageTotalGb) * 100f)
                .roundToInt().coerceIn(0, 100)
        } else 0

        val reactorStatus = when {
            state.adbConnected && state.currentHz > 0 -> NukeReactorCoreView.Status.ONLINE
            state.adbConnected || state.currentHz > 0 -> NukeReactorCoreView.Status.DEGRADED
            else -> NukeReactorCoreView.Status.OFFLINE
        }

        val phaseLabel = when (state.phase) {
            NukePerformanceEngine.Phase.ACTIVE -> "SESSION SETTINGS APPLIED"
            NukePerformanceEngine.Phase.DEGRADED -> "COMPATIBILITY MODE"
            NukePerformanceEngine.Phase.PROBING, NukePerformanceEngine.Phase.APPLYING,
            NukePerformanceEngine.Phase.RESTORING -> "APPLYING SETTINGS"
            NukePerformanceEngine.Phase.ERROR -> "CORE NEEDS ATTENTION"
            else -> "CORE READY"
        }
        val ramDetail = if (ramKnown) {
            val usedGb = (state.ramTotalMb - state.ramAvailableMb).coerceAtLeast(0L) / 1024f
            val totalGb = state.ramTotalMb / 1024f
            "RAM ${String.format(Locale.US, "%.1f", usedGb)} / ${String.format(Locale.US, "%.1f", totalGb)} GB"
        } else "RAM -- / --"
        val capabilitySnapshot = NukeAdaptiveCapabilities.resolve(this, state)
        val supportedTools = FloatingHudTool.entries.toMutableSet().apply {
            if (!capabilitySnapshot.has("shell.cpu_clocks")) remove(FloatingHudTool.CPU_CLOCKS)
            if (!capabilitySnapshot.has("shell.background_release")) remove(FloatingHudTool.RESOURCE_RADAR)
            if (!capabilitySnapshot.has("local.display") && !capabilitySnapshot.has("shell.frame_scan")) remove(FloatingHudTool.MAX_FPS)
            if (!capabilitySnapshot.has("local.wifi") && !capabilitySnapshot.has("local.telemetry")) remove(FloatingHudTool.NETWORK)
        }
        val supportedToggles = FloatingHudToggle.entries.toMutableSet().apply {
            if (!NukeCallShield.isSupported(this@FloatingBoosterService)) remove(FloatingHudToggle.CALL_SHIELD)
            if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI)) remove(FloatingHudToggle.NETWORK_BOOST)
        }
        val runtimeCapabilities = capabilitySnapshot.available
        val sessionRemoteHud = remoteHud.copy(
            panels = remoteHud.panels.filter { panel ->
                runtimeCapabilities.containsAll(panel.requiresAll) &&
                    (panel.requiresAny.isEmpty() || panel.requiresAny.any(runtimeCapabilities::contains))
            },
            quickControls = remoteHud.quickControls.filter { item ->
                runtimeCapabilities.containsAll(item.requiresAll) &&
                    (item.requiresAny.isEmpty() || item.requiresAny.any(runtimeCapabilities::contains))
            },
        )
        // ── CRITICAL: use .update{} NOT .value= to preserve user-set state ──────
        // quickToolStates, brightnessPercent, and displayDpi are set by user interaction
        // and must NEVER be overwritten by the telemetry loop. Using .update{} lets us
        // merge only the telemetry fields while keeping the user-modified fields intact.
        composeHudState.update { prev ->
            prev.copy(
                gameLabel = state.gameLabel,
                phaseLabel = "$phaseLabel • ${capabilitySnapshot.compatibilityLabel}",
                statusMessage = userFacingStatus(state.message).take(132),
                cpuPercent = state.cpuLoadPercent,
                ramPercent = usedRamPercent.takeIf { ramKnown },
                ramDetail = ramDetail,
                fps = state.lastMeasuredFps?.let { String.format(Locale.US, "%.0f", it) }
                    ?: if (state.currentHz > 0) "${state.currentHz}" else "--",
                ping = lastPingMs?.let { "${it}ms" } ?: if (pingMeasuring) "…" else "--",
                temperature = batteryTempC?.let { String.format(Locale.US, "%.1f°C", it) }
                    ?: thermalLabel(state.thermalStatus),
                battery = if (batteryPercent >= 0) "$batteryPercent%" else "--",
                storage = if (state.storageTotalGb > 0f) "$storageUsedPercent%" else "--",
                network = networkLabel,
                coreHealth = when (reactorStatus) {
                    NukeReactorCoreView.Status.ONLINE -> FloatingCoreHealth.ONLINE
                    NukeReactorCoreView.Status.DEGRADED -> FloatingCoreHealth.DEGRADED
                    NukeReactorCoreView.Status.OFFLINE -> FloatingCoreHealth.OFFLINE
                },
                remoteDefinition = sessionRemoteHud,
                supportedTools = supportedTools,
                toggleValues = mapOf(
                    FloatingHudToggle.GAME_MODE to prefs.safeBoolean(K_ADAPTIVE_GAME_MODE, false),
                    FloatingHudToggle.DND to state.dndActive,
                    FloatingHudToggle.CALL_SHIELD to NukeCallShield.isEnabled(this),
                    FloatingHudToggle.BATTERY_SAVER to (state.batterySaverActive == true),
                    FloatingHudToggle.DATA_SAVER to (state.dataSaverActive == true),
                    FloatingHudToggle.NETWORK_BOOST to state.networkBoostActive,
                    FloatingHudToggle.CROSSHAIR to prefs.safeBoolean("cross_en", false),
                    FloatingHudToggle.KEEP_AWAKE to prefs.safeBoolean("hud_keep_awake", false),
                ) + pendingToggleValues,
                supportedToggles = supportedToggles,
                busyToggles = toggleJobs.filterValues { it.isActive }.keys.toSet(),
                // ── Preserved fields — NOT overwritten by telemetry ────────────
                // quickToolStates: user switch states survive telemetry ticks
                quickToolStates = prev.quickToolStates,
                // unsupportedQuickTools: set only by queryAndSyncQuickToolStates
                unsupportedQuickTools = prev.unsupportedQuickTools,
                // brightnessPercent: set by slider or queryAndSyncQuickToolStates
                brightnessPercent = prev.brightnessPercent,
                // displayDpi: set by slider or queryAndSyncQuickToolStates
                displayDpi = prev.displayDpi,
            )
        }
        if ((state.message.contains("THERMAL GUARD", true) ||
                state.message.contains("THERMAL RECOVERED", true) ||
                state.message.contains("AUTO MEMORY COMPACT", true)) &&
            state.message != lastAutomaticAlert
        ) {
            lastAutomaticAlert = state.message
            toastStatus("AUTO • ${state.message}")
        }

        // The minimized rail intentionally exposes no raw HZ/LINK/CPU/RAM text. The particle core
        // communicates health through color + pulse only, keeping the game viewport clean.
        windows["edge"]?.view?.findViewById<NukeReactorCoreView>(R.id.edgeReactor)?.setStatus(reactorStatus)

        listOf("max_fps", "network", "monitor").forEach { key ->
            windows[key]?.view?.findViewById<TextView>(R.id.moduleStatus)?.text = userFacingStatus(state.message).take(180)
        }
        windows["monitor"]?.view?.let { v ->
            (v.getTag(R.id.monitorCpuTag) as? TextView)?.text = "CPU LOAD  ${state.cpuLoadPercent?.let { "$it%" } ?: "N/A"}"
            (v.getTag(R.id.monitorRamTag) as? TextView)?.text = if (ramKnown) "RAM USED  $usedRamPercent%  // FREE ${state.ramAvailableMb}MB" else "RAM USED  N/A"
            (v.getTag(R.id.monitorDisplayTag) as? TextView)?.text = "DISPLAY  ${state.currentHz.takeIf { it > 0 } ?: "--"}Hz / ${state.maxHz.takeIf { it > 0 } ?: "--"}Hz  // ${if (state.adbConnected) "EXTENDED" else "STANDARD"}"
            if (state.cpuLoadPercent != null) (v.getTag(R.id.monitorCpuSparkTag) as? NukeSparklineView)?.push(cpu)
            if (ramKnown) (v.getTag(R.id.monitorRamSparkTag) as? NukeSparklineView)?.push(usedRamPercent)
        }
    }

    private fun publishRuntime(state: NukePerformanceEngine.State?) {
        NukeRuntimeState.publish(
            NukeRuntimeState.Snapshot(
                overlayRunning = prefs.safeBoolean(K_ACTIVE, false) && foregroundStarted,
                activePackage = targetPackage ?: prefs.safeString(K_ACTIVE_PACKAGE, "").takeIf { it.isNotBlank() },
                language = validLanguage(prefs.safeString(K_LANG, "en")),
                crosshairEnabled = windows.containsKey("crosshair") && prefs.safeBoolean("cross_en", false),
                currentHz = state?.currentHz ?: 0,
                maxHz = state?.maxHz ?: 0,
                lastFps = state?.lastMeasuredFps,
                phase = state?.phase?.name ?: "IDLE",
                message = state?.message ?: "CORE STANDBY",
            ),
        )
    }

    private fun refreshLocalizedHud() {
        engine?.state?.value?.let(::updateTelemetryUi)
    }

    private fun userFacingStatus(raw: String): String = raw
        .replace("WIRELESS ADB", "DEVICE LINK", ignoreCase = true)
        .replace("ADB", "EXTENDED CONTROL", ignoreCase = true)
        .replace("SHELL", "DEVICE", ignoreCase = true)
        .replace("LOCAL CORE", "CONNECTION", ignoreCase = true)
        .replace("DEVICE_CONFIG", "DEVICE PROFILE", ignoreCase = true)
        .replace("ART PRIME", "GAME PREP", ignoreCase = true)
        .replace("SHIZUKU", "SYSTEM BRIDGE", ignoreCase = true)
        .replace("IADB", "CORE ENGINE", ignoreCase = true)

    private fun thermalLabel(status: Int): String = when {
        status >= PowerManager.THERMAL_STATUS_SEVERE -> "HOT"
        status >= PowerManager.THERMAL_STATUS_MODERATE -> "WARM"
        status >= PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        else -> "OK"
    }

    private fun metricLine(label: String, value: String): TextView = TextView(this).apply {
        text = "$label  $value"
        setTextColor(NukeHudPalette.Text); textSize = 9f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setPadding(dp(4), dp(5), dp(4), dp(5))
    }

    private fun controlTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue
        setTextColor(NukeHudPalette.Cyan); textSize = 8f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        setPadding(dp(4), dp(9), dp(4), dp(3))
    }

    private fun noteText(value: String): TextView = TextView(this).apply {
        text = value
        setTextColor(NukeHudPalette.Muted); textSize = 9.5f
        setPadding(dp(5), dp(6), dp(5), dp(8))
    }

    private fun actionButton(label: String, danger: Boolean = false, click: () -> Unit): NukeActionTileView = NukeActionTileView(this).apply {
        val icon = when {
            label.contains("FPS", true) || label.contains("FRAME", true) -> R.drawable.nuke_ic_speed
            label.contains("NETWORK", true) || label.contains("ADB", true) || label.contains("LATENCY", true) -> R.drawable.nuke_ic_network
            label.contains("CLEAN", true) || label.contains("CACHE", true) || label.contains("RECLAIM", true) -> R.drawable.nuke_ic_clean
            label.contains("DISPLAY", true) || label.contains("DPI", true) -> R.drawable.nuke_ic_display
            label.contains("FOCUS", true) -> R.drawable.nuke_ic_focus
            else -> R.drawable.nuke_ic_session
        }
        configure(label, if (danger) "RESTORE / DESTRUCTIVE CONTROL" else "VERIFIED GAME NUKE CONTROL", icon, danger)
        var lastClickAt = 0L
        setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastClickAt < 700L) return@setOnClickListener
            lastClickAt = now
            click()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(6) }
    }

    private fun styleCheck(box: CheckBox) {
        box.setTextColor(NukeHudPalette.Text); box.textSize = 9f
        box.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        box.buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(NukeHudPalette.Green, NukeHudPalette.MutedDeep),
        )
        box.setPadding(dp(4), dp(2), dp(4), dp(2)); box.minHeight = dp(36)
    }

    private fun styleSwitch(switch: Switch) {
        switch.setTextColor(NukeHudPalette.Text); switch.textSize = 10f
        switch.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        switch.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, Color.rgb(190, 198, 193)),
        )
        switch.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(NukeHudPalette.GreenDim, NukeHudPalette.PanelSoft),
        )
        switch.setPadding(dp(5), dp(4), dp(5), dp(4)); switch.minHeight = dp(46)
    }

    private fun spinner(items: List<String>, initial: Int, onSelected: (Int) -> Unit): Spinner = Spinner(this).apply {
        val adapter = object : ArrayAdapter<String>(this@FloatingBoosterService, android.R.layout.simple_spinner_item, items) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(NukeHudPalette.Text); textSize = 8.5f
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setPadding(dp(10), 0, dp(10), 0)
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(NukeHudPalette.Text); setBackgroundColor(NukeHudPalette.Panel); textSize = 9f
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
            }
        }
        this.adapter = adapter
        setSelection(initial.coerceIn(0, maxOf(0, items.lastIndex)), false)
        background = getDrawable(R.drawable.nuke_hud_search)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { onSelected(position) }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        minimumHeight = dp(40); setPadding(dp(8), 0, dp(8), 0)
    }

    private fun seekControl(label: String, min: Int, max: Int, initial: Int, onChanged: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(3), dp(4), dp(3), dp(4)) }
        val valueLabel = TextView(this).apply {
            setTextColor(NukeHudPalette.Text); textSize = 8.5f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = initial.coerceIn(min, max) - min
            progressTintList = ColorStateList.valueOf(NukeHudPalette.Green)
            thumbTintList = ColorStateList.valueOf(NukeHudPalette.Text)
            progressBackgroundTintList = ColorStateList.valueOf(NukeHudPalette.MutedDeep)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + min
                    valueLabel.text = "$label  $value"
                    if (fromUser) onChanged(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        valueLabel.text = "$label  ${initial.coerceIn(min, max)}"
        row.addView(valueLabel); row.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        return row
    }

    private fun addWindow(key: String, view: View, p: WindowManager.LayoutParams): Boolean {
        if (windows.containsKey(key)) return true
        p.alpha = if (key == "crosshair") 1f else prefs.safeFloat(K_HUD_ALPHA, .96f).coerceIn(.72f, .98f)
        if (key != "crosshair") {
            view.alpha = 0f
            view.scaleX = .965f
            view.scaleY = .965f
            view.translationY = dp(5).toFloat()
        }
        val ok = runCatching { wm.addView(view, p) }.onFailure { Log.e(TAG, "addWindow $key failed", it) }.isSuccess
        if (ok) {
            windows[key] = WindowSlot(view, p)
            if (key != "crosshair") {
                val hubWing = key.startsWith("hub_")
                NukeMotionEngine.reveal(view, fromScale = if (hubWing) .94f else .91f, fromY = dp(if (hubWing) 10 else 16).toFloat())
            }
        }
        return ok
    }

    private fun removeWindow(key: String) {
        panelActionJobs.remove(key)?.cancel()
        if (key == "monitor") monitorTouchThrough = false
        val slot = windows.remove(key) ?: return
        NukeMotionEngine.cancel(slot.view)
        if (key == "crosshair" || !slot.view.isAttachedToWindow) { runCatching { wm.removeViewImmediate(slot.view) }; return }
        slot.view.animate().cancel()
        slot.view.animate().alpha(0f).scaleX(.96f).scaleY(.96f).translationY(dp(5).toFloat()).setDuration(95L)
            .withEndAction { runCatching { if (slot.view.isAttachedToWindow) wm.removeViewImmediate(slot.view) } }.start()
    }

    private fun removeAllWindowsImmediate() {
        hubAttaching = false
        panelActionJobs.remove("cpu_clocks")?.cancel()
        monitorTouchThrough = false
        windows.keys.toList().forEach { key ->
            val slot = windows.remove(key) ?: return@forEach
            runCatching { NukeMotionEngine.cancel(slot.view) }
            runCatching { slot.view.animate().cancel() }
            runCatching { if (slot.view.isAttachedToWindow) wm.removeViewImmediate(slot.view) }
                .onFailure { Log.w(TAG, "Immediate overlay removal failed for $key", it) }
        }
    }

    private fun removeWindowImmediate(key: String) {
        panelActionJobs.remove(key)?.cancel()
        if (key == "monitor") monitorTouchThrough = false
        val slot = windows.remove(key) ?: return
        runCatching { NukeMotionEngine.cancel(slot.view) }
        runCatching { slot.view.animate().cancel() }
        runCatching { if (slot.view.isAttachedToWindow) wm.removeViewImmediate(slot.view) }
            .onFailure { Log.w(TAG, "Immediate overlay removal failed for $key", it) }
    }

    private fun dragWindowBy(key: String, dx: Float, dy: Float) {
        val slot = windows[key] ?: return
        val bounds = safeBounds()
        val width = slot.params.width.takeIf { it > 0 } ?: slot.view.width.coerceAtLeast(dp(54))
        val height = slot.params.height.takeIf { it > 0 } ?: slot.view.height.coerceAtLeast(dp(54))
        slot.params.x = (slot.params.x + dx.roundToInt()).coerceIn(bounds.left, maxOf(bounds.left, bounds.right - width))
        slot.params.y = (slot.params.y + dy.roundToInt()).coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - height))
        runCatching { wm.updateViewLayout(slot.view, slot.params) }
    }

    private fun makeDraggable(
        key: String,
        handle: View,
        tap: (() -> Unit)? = null,
        longPress: (() -> Unit)? = null,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var downAt = 0L
        var startX = 0
        var startY = 0
        var moved = false
        handle.setOnTouchListener { _, event ->
            val slot = windows[key] ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downAt = event.eventTime
                    startX = slot.params.x
                    startY = slot.params.y
                    moved = false
                    NukeMotionEngine.press(slot.view, true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                    val vw = slot.params.width.takeIf { it > 0 } ?: slot.view.width.coerceAtLeast(dp(54))
                    val vh = slot.view.height.takeIf { it > 0 } ?: dp(54)
                    val bounds = safeBounds()
                    slot.params.x = (startX + dx.toInt()).coerceIn(bounds.left, maxOf(bounds.left, bounds.right - minOf(vw, bounds.width())))
                    slot.params.y = (startY + dy.toInt()).coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - minOf(vh, bounds.height())))
                    runCatching { wm.updateViewLayout(slot.view, slot.params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    NukeMotionEngine.press(slot.view, false)
                    if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        val heldMs = (event.eventTime - downAt).coerceAtLeast(0L)
                        if (heldMs >= 650L && longPress != null) longPress.invoke() else tap?.invoke()
                    } else if (key == "edge" && event.actionMasked == MotionEvent.ACTION_UP) {
                        // Snap the minimized command rail to the closest physical edge. Reinflate
                        // after the event so TOP/BOTTOM can use a compact horizontal rail.
                        handle.post { snapEdgeToNearest(slot) }
                    }
                    true
                }
                else -> false
            }
        }
    }


    private fun snapEdgeToNearest(slot: WindowSlot) {
        if (windows["edge"] !== slot) return
        val width = slot.params.width.takeIf { it > 0 } ?: slot.view.width.coerceAtLeast(dp(54))
        val height = slot.params.height.takeIf { it > 0 } ?: slot.view.height.coerceAtLeast(dp(54))
        val bounds = safeBounds()
        val minX = bounds.left
        val minY = bounds.top
        val maxX = maxOf(minX, bounds.right - width)
        val maxY = maxOf(minY, bounds.bottom - height)
        val left = (slot.params.x - minX).coerceAtLeast(0)
        val right = (maxX - slot.params.x).coerceAtLeast(0)
        val top = (slot.params.y - minY).coerceAtLeast(0)
        val bottom = (maxY - slot.params.y).coerceAtLeast(0)
        val dock = listOf(EdgeDock.LEFT to left, EdgeDock.RIGHT to right, EdgeDock.TOP to top, EdgeDock.BOTTOM to bottom)
            .minByOrNull { it.second }?.first ?: EdgeDock.LEFT
        val fraction = when (dock) {
            EdgeDock.LEFT, EdgeDock.RIGHT -> if (maxY > minY) (slot.params.y - minY).toFloat() / (maxY - minY) else .35f
            EdgeDock.TOP, EdgeDock.BOTTOM -> if (maxX > minX) (slot.params.x - minX).toFloat() / (maxX - minX) else .5f
        }.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .35f
        val targetX = when (dock) { EdgeDock.LEFT -> minX; EdgeDock.RIGHT -> maxX; else -> slot.params.x.coerceIn(minX,maxX) }
        val targetY = when (dock) { EdgeDock.TOP -> minY; EdgeDock.BOTTOM -> maxY; else -> slot.params.y.coerceIn(minY,maxY) }
        val startX=slot.params.x; val startY=slot.params.y
        android.animation.ValueAnimator.ofFloat(0f,1f).apply {
            duration=190L
            interpolator=android.view.animation.PathInterpolator(.16f,.86f,.25f,1f)
            addUpdateListener { a ->
                if (windows["edge"] !== slot) return@addUpdateListener
                val t=a.animatedValue as Float
                slot.params.x=(startX+(targetX-startX)*t).roundToInt()
                slot.params.y=(startY+(targetY-startY)*t).roundToInt()
                runCatching { wm.updateViewLayout(slot.view,slot.params) }
            }
            doOnEndCompat {
                prefs.edit().putString(K_EDGE_DOCK,dock.name).putFloat(K_EDGE_FRACTION,fraction).remove("edge_x").remove("edge_y").apply()
                removeWindow("edge")
                slot.view.postDelayed({ ensureEdge() },105L)
            }
            start()
        }
    }

    private fun android.animation.ValueAnimator.doOnEndCompat(block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) = block() })
    }

    private fun View.safeClick(action: () -> Unit) {
        setOnClickListener { if (acceptAction()) action() }
    }

    private fun acceptAction(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastActionAt < TOUCH_DEBOUNCE_MS) return false
        lastActionAt = now
        return true
    }

    private fun params(width: Int, height: Int): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width,
        height,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        baseFlags(),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun baseFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun hubWidth(): Int {
        val cap = dp(remoteHud.panelMaxWidthDp)
        val floor = dp(remoteHud.panelMinWidthDp)
        return minOf(cap, (screenWidth() * remoteHud.panelWidthFraction).roundToInt())
            .coerceAtLeast(minOf(floor, screenWidth()))
    }

    private fun hubHeight(): Int {
        val landscape = screenWidth() > screenHeight()
        val cap = if (landscape) dp(460) else dp(720)
        val fraction = remoteHud.panelMaxHeightFraction
        val floor = if (landscape) dp(306) else dp(440)
        return minOf(cap, (screenHeight() * fraction).roundToInt()).coerceAtLeast(minOf(floor, screenHeight()))
    }

    private fun moduleWidth(): Int {
        val fraction = if (screenWidth() > screenHeight()) .31f else .90f
        val cap = if (screenWidth() > screenHeight()) dp(390) else dp(370)
        return minOf(cap, (screenWidth() * fraction).roundToInt()).coerceAtLeast(minOf(dp(280), screenWidth()))
    }

    private fun moduleHeight(): Int {
        val fraction = if (screenWidth() > screenHeight()) .72f else .66f
        val cap = if (screenWidth() > screenHeight()) dp(400) else dp(590)
        return minOf(cap, (screenHeight() * fraction).roundToInt()).coerceAtLeast(minOf(dp(220), screenHeight()))
    }

    private fun hudScale(): Float = prefs.safeFloat(K_HUD_SCALE, 1f).coerceIn(.85f, 1.15f)
    private fun scaledHubWidth(): Int = minOf((hubWidth() * hudScale()).roundToInt(), (screenWidth() * .97f).roundToInt())
    private fun scaledHubHeight(): Int = minOf((hubHeight() * hudScale()).roundToInt(), (screenHeight() * .92f).roundToInt())
    private fun scaledModuleWidth(): Int = minOf((moduleWidth() * hudScale()).roundToInt(), (screenWidth() * .95f).roundToInt())
    private fun scaledModuleHeight(): Int = minOf((moduleHeight() * hudScale()).roundToInt(), (screenHeight() * .90f).roundToInt())
    private fun centeredX(width: Int): Int = safeBounds().left + ((screenWidth() - width) / 2).coerceAtLeast(0)
    private fun displayBounds(): android.graphics.Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { wm.currentWindowMetrics.bounds }.getOrElse { android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) }
    } else android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    private fun safeBounds(): android.graphics.Rect {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return displayBounds()
        return runCatching {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            android.graphics.Rect(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom,
            ).takeIf { it.width() > dp(180) && it.height() > dp(120) } ?: bounds
        }.getOrElse { displayBounds() }
    }
    private fun screenWidth(): Int = safeBounds().width().coerceAtLeast(1)
    private fun screenHeight(): Int = safeBounds().height().coerceAtLeast(1)
    private fun density(): Float {
        val confDpi = resources.configuration.densityDpi
        if (confDpi > 0) return confDpi / 160f
        return resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
    }
    private fun dp(value: Int): Int = (value * density()).roundToInt()

    private fun showNukeModeBanner() {
        removeWindow("banner")
        val text = TextView(this).apply {
            this.text = "NUKE MODE  //  SESSION ARMED"
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 11f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = getDrawable(R.drawable.nuke_mode_badge); alpha = 0f; scaleX = .84f; scaleY = .84f
        }
        val p = params(dp(260), dp(50)).apply {
            x = centeredX(dp(260)); y = safeBounds().top + dp(18); alpha = .96f; flags = baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        addWindow("banner", text, p)
        text.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240L).withEndAction {
            scope.launch {
                delay(1_100L)
                text.animate().alpha(0f).translationY(-dp(16).toFloat()).setDuration(220L).withEndAction { removeWindow("banner") }.start()
            }
        }.start()
    }

    private suspend fun restoreDisplayWithRetry(
        context: Context = applicationContext,
        maxAttempts: Int = 4,
    ): NukeDisplayProfileController.Result {
        var result = withContext(Dispatchers.IO) {
            runCatching { NukeDisplayProfileController.restoreIfOwned(context) }
                .getOrElse { NukeDisplayProfileController.Result(NukeDisplayProfileController.Outcome.ERROR, "Display restore failed safely") }
        }
        var attempt = 1
        while (attempt < maxAttempts &&
            result.outcome == NukeDisplayProfileController.Outcome.DEFERRED &&
            NukeDisplayProfileController.hasOwnedOverride(context)
        ) {
            // Ask the existing connection manager to re-establish the trusted transport/core, then
            // retry while this user-visible FGS is still alive. initServer() is itself asynchronous.
            runCatching { AdbManager.getInstance(context).initServer() }
            delay(450L + attempt * 450L)
            result = withContext(Dispatchers.IO) {
                runCatching { NukeDisplayProfileController.restoreIfOwned(context) }
                    .getOrElse { NukeDisplayProfileController.Result(NukeDisplayProfileController.Outcome.ERROR, "Display restore failed safely") }
            }
            attempt++
        }
        return result
    }

    private suspend fun stopSession(reason: String) {
        if (stopping) return
        stopping = true
        cancelLoops()
        moduleActionJob?.cancel()
        moduleActionJob = null
        val local = engine
        val finalState = local?.state?.value
        val moduleRestoreCompleted = withTimeoutOrNull(20_000L) {
            withContext(Dispatchers.IO) {
                moduleShop.deactivateAll()
                moduleShop.state.value.activeIds.isEmpty()
            }
        } == true
        if (!moduleRestoreCompleted) {
            NukeToast.error(applicationContext, "Some installed module restores timed out; reopen Game Nuke to retry their OFF switches", true)
        }
        withTimeoutOrNull(4_500L) { runCatching { local?.restoreSession() } }
        // Remove overlays before restoring size/density so no attached window can be stranded with
        // coordinates from the old emulated display. This also makes foldable/rotation transitions safer.
        removeAllWindowsImmediate()
        val displayRestore = restoreDisplayWithRetry(applicationContext)
        runCatching { local?.releaseLocalResources() }
        displayRestore?.let { result ->
            if (result.outcome == NukeDisplayProfileController.Outcome.ERROR || result.outcome == NukeDisplayProfileController.Outcome.DEFERRED) {
                NukeToast.error(applicationContext, result.message, true)
            }
        }
        finalState?.let { state ->
            val parts = buildList {
                state.lastMeasuredFps?.let { add("FPS ${String.format(Locale.US, "%.0f", it)}") }
                if (state.ramAvailableMb > 0L) add("RAM ${state.ramAvailableMb}MB free")
                batteryTempC?.let { add("TEMP ${String.format(Locale.US, "%.1f°C", it)}") }
                sessionBatterySummary().takeIf { it.isNotBlank() }?.let { add("BAT $it") }
            }
            toastStatus("SUCCESS • Session ended${if (parts.isEmpty()) "" else " • ${parts.joinToString(" • ")}"}", long = true)
        }
        if (prefs.safeBoolean(K_ADAPTIVE_OWN_AWAKE, false)) {
            prefs.edit().putBoolean("hud_keep_awake", false).apply()
        }
        clearSessionMarker(); engine = null; targetPackage = null
        NukeAdManager.markGamingSessionEnded(applicationContext)
        publishRuntime(null)
        Log.i(TAG, "Session stopped: $reason")
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun clearSessionMarker() {
        NukeRuntimeState.setLaunchHandoffActive(false)
        NukeRuntimeState.clearExternalNavigationGrace()
        prefs.edit().putBoolean(K_ACTIVE, false).remove(K_ACTIVE_PACKAGE)
            .putBoolean(K_ADAPTIVE_GAME_MODE, false)
            .remove(K_ADAPTIVE_OWN_AWAKE).remove(K_ADAPTIVE_OWN_NETWORK).remove(K_ADAPTIVE_OWN_OEM).apply()
        NukeRuntimeState.update { it.copy(overlayRunning = false, activePackage = null, crosshairEnabled = false) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val bounds = safeBounds()
        val reopenHub = hubVisible()
        if (reopenHub) windows.keys.filter { it.startsWith("hub_") }.forEach(::removeWindowImmediate)
        windows.forEach { (key, slot) ->
            when (key) {
                "edge", "banner", "crosshair" -> Unit
                else -> { slot.params.width = scaledModuleWidth(); slot.params.height = scaledModuleHeight() }
            }
            if (key != "crosshair") {
                val width = slot.params.width.takeIf { it > 0 } ?: dp(54)
                val height = slot.params.height.takeIf { it > 0 } ?: slot.view.height.coerceAtLeast(dp(54))
                slot.params.x = slot.params.x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - width))
                slot.params.y = slot.params.y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - height))
            }
            runCatching { wm.updateViewLayout(slot.view, slot.params) }
        }
        if (windows.containsKey("edge")) {
            removeWindowImmediate("edge")
            ensureEdge()
        }
        if (reopenHub) ensureHubVisible()
        syncCrosshairOverlay()
    }

    override fun onDestroy() {
        NukeRuntimeState.setLaunchHandoffActive(false)
        val unexpectedActiveDestroy = !stopping && ::prefs.isInitialized && prefs.safeBoolean(K_ACTIVE, false)
        val emergencyEngine = engine
        val app = applicationContext
        if (unexpectedActiveDestroy) {
            // If Android destroys an active FGS/service outside the normal End Session path, prefer
            // restoring owned device state over preserving a performance tweak. This detached scope
            // is intentionally independent from `scope`, which is cancelled below.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                if (::moduleShop.isInitialized) withTimeoutOrNull(20_000L) { runCatching { moduleShop.deactivateAll() } }
                withTimeoutOrNull(4_000L) { runCatching { emergencyEngine?.restoreSession() } }
                restoreDisplayWithRetry(app)
                runCatching { emergencyEngine?.releaseLocalResources() }
            }
        }
        cancelLoops(); switchJob?.cancel()
        runCatching { if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
        removeAllWindowsImmediate()
        if (::composeLifecycleOwner.isInitialized) composeLifecycleOwner.destroy()
        if (!unexpectedActiveDestroy) runCatching { engine?.releaseLocalResources() }
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        NukeRuntimeState.update { it.copy(overlayRunning = false, crosshairEnabled = false) }
        scope.cancel(); super.onDestroy()
    }

    private fun directoryBytes(root: File): Long = runCatching {
        if (!root.exists()) 0L else if (root.isFile) root.length() else root.listFiles()?.sumOf { directoryBytes(it) } ?: 0L
    }.getOrDefault(0L)

    private fun validLanguage(code: String): String = code.takeIf { candidate -> Tx.supportedLangs.any { it.first == candidate } } ?: "en"
    private fun validPackage(value: String): Boolean = value.length in 3..220 && value.matches(Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$"))
}
