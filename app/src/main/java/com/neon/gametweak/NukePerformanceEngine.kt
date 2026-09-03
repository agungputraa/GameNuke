package com.neon.gametweak

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.StatFs
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Session-scoped, capability-driven gaming engine for non-root Android devices with optional
 * paired Wireless ADB access. It never promises extra frames that the game itself does not render.
 * It removes avoidable platform-side constraints only when the device confirms that an operation
 * exists and the write can be read back, then restores owned session changes.
 */
class NukePerformanceEngine(
    context: Context,
    val targetPackage: String,
    private val onBeforeExternalNavigation: ((String) -> Unit)? = null,
) {
    enum class Phase { IDLE, PROBING, APPLYING, ACTIVE, DEGRADED, RESTORING, ERROR }

    data class Capability(
        val id: String,
        val title: String,
        val supported: Boolean,
        val detail: String,
    )

    data class State(
        val phase: Phase = Phase.IDLE,
        val gameLabel: String = "GAME",
        val adbConnected: Boolean = false,
        val currentHz: Int = 0,
        val maxHz: Int = 0,
        val cpuLoadPercent: Int? = null,
        val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
        val thermalHeadroom: Float? = null,
        val ramAvailableMb: Long = 0,
        val ramTotalMb: Long = 0,
        val storageFreeGb: Float = 0f,
        val storageTotalGb: Float = 0f,
        val wifiLockHeld: Boolean = false,
        val thermalGuarded: Boolean = false,
        val appliedFeatures: Set<String> = emptySet(),
        val capabilities: List<Capability> = emptyList(),
        val busyAction: String? = null,
        val message: String = "CORE STANDBY",
        val lastMemoryGainMb: Long? = null,
        val lastCacheGainMb: Long? = null,
        val lastMeasuredFps: Float? = null,
        val lastFrameCount: Long? = null,
        val deviceLabel: String = "ANDROID DEVICE",
        val socLabel: String = "UNKNOWN SOC",
        val dndAccess: Boolean = false,
        val dndActive: Boolean = false,
        val gameModePerformance: Boolean? = null,
        val batterySaverActive: Boolean? = null,
        val dataSaverActive: Boolean? = null,
        val networkBoostActive: Boolean = false,
        val rotationLocked: Boolean? = null,
        val gpuReliefActive: Boolean = false,
    ) {
        val capabilityCount: Int get() = capabilities.count { it.supported }
        val capabilityTotal: Int get() = capabilities.size
        val ramFreePercent: Int get() = if (ramTotalMb <= 0L) 0 else
            ((ramAvailableMb.toDouble() / ramTotalMb.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    }

    private data class SessionSnapshot(
        val peakRefresh: String?,
        val minRefresh: String?,
        val lowPower: String?,
        val gameDefaultFrameGuard: String?,
        val gameMode: String?,
        val gameOverlayConfig: String?,
        val dataSaver: Boolean?,
    )

    private val appContext = context.applicationContext
    private val adb = AdbManager.getInstance(appContext)
    private val shell = NukeGamingShellGateway(adb)
    private val wifiLock = NukeWifiSessionLock(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val deviceProfile = NukeDeviceProfile.current()
    private val operationMutex = Mutex()
    private val capabilityProbeMutex = Mutex()
    private val hudMetricsMutex = Mutex()
    private val _state = MutableStateFlow(
        State(
            gameLabel = resolveGameLabel(),
            deviceLabel = deviceProfile.compactLabel.uppercase(),
            socLabel = deviceProfile.soc.uppercase(),
            dndAccess = hasDndAccess(),
            dndActive = isDndActive(),
            batterySaverActive = readLocalBatterySaver(),
            dataSaverActive = readLocalDataSaver(),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var snapshot: SessionSnapshot? = null
    private var refreshPeakOwned = false
    private var refreshMinOwned = false
    private var powerSaverOwned = false
    private var powerSaverWritten: String? = null
    private var dataSaverOwned = false
    private var dataSaverWritten: Boolean? = null
    private var frameGuardOwned = false
    private var gameModeOwned = false
    private var gameModeWritten: String? = null
    private var forcedWifiShellOwned = false
    private var gameOverlayOwned = false
    private var gameOverlayWritten: String? = null
    private var maxRefreshWritten: String? = null
    private var minRefreshWritten: String? = null
    private var dndOwned = false
    private var dndOriginalFilter: Int? = null
    private val thermalSampleLock = Any()
    private var lastThermalHeadroomAt = 0L
    private var cachedThermalHeadroom: Float? = null

    suspend fun initializeAndActivate() {
        var shouldProbe = false
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                if (_state.value.phase == Phase.ACTIVE && snapshot != null) return@withContext
                recoverStaleSnapshotIfPossible()
                // Bring the session path online first. The exhaustive read-only capability matrix
                // runs under its own mutex afterwards, so UI commands do not queue behind probes.
                activateInternal()
                shouldProbe = true
            }
        }
        if (!shouldProbe) return
        capabilityProbeMutex.withLock {
            withContext(Dispatchers.IO) { probeInternal() }
        }
    }

    suspend fun probeCapabilities() = capabilityProbeMutex.withLock {
        withContext(Dispatchers.IO) { probeInternal() }
    }

    suspend fun refreshMetrics() {
        if (!operationMutex.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
                val metrics = readMetrics()
                val linkConnected = shell.connected()
                _state.value = _state.value.copy(
                    adbConnected = linkConnected,
                    currentHz = metrics.currentHz,
                    maxHz = metrics.maxHz,
                    thermalStatus = metrics.thermalStatus,
                    thermalHeadroom = metrics.thermalHeadroom,
                    ramAvailableMb = metrics.ramAvailableMb,
                    ramTotalMb = metrics.ramTotalMb,
                    storageFreeGb = metrics.storageFreeGb,
                    storageTotalGb = metrics.storageTotalGb,
                    wifiLockHeld = wifiLock.isHeld(),
                    dndAccess = hasDndAccess(),
                    dndActive = isDndActive(),
                    batterySaverActive = readLocalBatterySaver(),
                    dataSaverActive = readLocalDataSaver(),
                    networkBoostActive = wifiLock.isHeld() || forcedWifiShellOwned,
                )
                applyThermalHysteresis(metrics)
            }
        } finally {
            operationMutex.unlock()
        }
    }

    /**
     * Lightweight HUD telemetry refresh. It deliberately avoids getThermalHeadroom(), whose
     * platform guidance requires slower polling, so CPU/RAM/display gauges can remain responsive.
     */
    suspend fun refreshHudMetrics(includeCpu: Boolean = true) {
        if (!hudMetricsMutex.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                @Suppress("DEPRECATION")
                val display = wm?.defaultDisplay
                val currentHz = runCatching { display?.refreshRate?.roundToInt() ?: 0 }.getOrDefault(0)
                val maxHz = runCatching { display?.supportedModes?.maxOfOrNull { it.refreshRate }?.roundToInt() ?: currentHz }
                    .getOrDefault(currentHz)
                val memory = readMemory()
                val linkConnected = shell.connected()
                val cpu = if (includeCpu && linkConnected) (shell.readCpuLoadPercent() ?: NukeLocalCpuSampler.readPercent())
                    else if (includeCpu) NukeLocalCpuSampler.readPercent() else null
                _state.value = _state.value.copy(
                    adbConnected = linkConnected,
                    currentHz = currentHz,
                    maxHz = maxHz,
                    cpuLoadPercent = cpu ?: _state.value.cpuLoadPercent,
                    ramAvailableMb = memory.first / MIB,
                    ramTotalMb = memory.second / MIB,
                    wifiLockHeld = wifiLock.isHeld(),
                    dndAccess = hasDndAccess(),
                )
            }
        } finally {
            hudMetricsMutex.unlock()
        }
    }

    suspend fun setGameFocus(enabled: Boolean) = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val manager = notificationManager
            if (manager == null || !manager.isNotificationPolicyAccessGranted) {
                _state.value = _state.value.copy(dndAccess = false, dndActive = isDndActive())
                setMessage("DO NOT DISTURB ACCESS REQUIRED", Phase.DEGRADED)
                return@withContext
            }

            val current = runCatching { manager.currentInterruptionFilter }
                .getOrDefault(NotificationManager.INTERRUPTION_FILTER_ALL)
            if (enabled) {
                if (current != NotificationManager.INTERRUPTION_FILTER_ALL && !dndOwned) {
                    _state.value = _state.value.copy(
                        dndAccess = true,
                        dndActive = true,
                        message = "DO NOT DISTURB ALREADY ACTIVE • SYSTEM STATE LEFT UNCHANGED",
                    )
                    return@withContext
                }
                if (!dndOwned) {
                    dndOriginalFilter = current
                    val applied = runCatching {
                        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                        manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                    }.getOrDefault(false)
                    if (applied) {
                        dndOwned = true
                        prefs.edit().putBoolean(K_DND_OWNED, true).putInt(K_DND_FILTER, current).apply()
                        _state.value = _state.value.copy(
                            dndAccess = true,
                            dndActive = true,
                            message = "DO NOT DISTURB ACTIVE • PRIORITY INTERRUPTIONS ONLY",
                        )
                    } else {
                        _state.value = _state.value.copy(dndAccess = true, dndActive = isDndActive())
                        setMessage("DO NOT DISTURB WAS REJECTED BY DEVICE POLICY", Phase.DEGRADED)
                    }
                }
            } else {
                if (dndOwned) {
                    restoreDndIfOwned()
                    _state.value = _state.value.copy(
                        dndAccess = true,
                        dndActive = isDndActive(),
                        message = "DO NOT DISTURB RESTORED",
                    )
                } else if (current != NotificationManager.INTERRUPTION_FILTER_ALL) {
                    _state.value = _state.value.copy(
                        dndAccess = true,
                        dndActive = true,
                        message = "SYSTEM DO NOT DISTURB IS ACTIVE • GAME NUKE DID NOT CHANGE IT",
                    )
                } else {
                    _state.value = _state.value.copy(dndAccess = true, dndActive = false, message = "DO NOT DISTURB OFF")
                }
            }
        }
    }

    suspend fun toggleGameFocus() = setGameFocus(!isDndActive())

    private fun captureSessionSnapshot(): SessionSnapshot = SessionSnapshot(
        peakRefresh = shell.readSetting("system", "peak_refresh_rate"),
        minRefresh = shell.readSetting("system", "min_refresh_rate"),
        lowPower = null,
        gameDefaultFrameGuard = if (Build.VERSION.SDK_INT >= 35) shell.readGameDefaultFrameRateGuard() else null,
        gameMode = null,
        gameOverlayConfig = null,
        dataSaver = null,
    )

    private fun ensureSessionSnapshot(): SessionSnapshot {
        val existing = snapshot
        if (existing != null) return existing
        val captured = captureSessionSnapshot()
        snapshot = captured
        persistSnapshot(captured)
        return captured
    }

    suspend fun setPerformanceGameMode(enabled: Boolean) = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!shell.connected()) {
                setMessage("EXTENDED CONTROL REQUIRED FOR OEM GAME MODE", Phase.DEGRADED)
                return@withContext
            }
            if (!shell.supportsGameMode(targetPackage)) {
                _state.value = _state.value.copy(gameModePerformance = null, message = "OEM GAME MODE NOT SUPPORTED FOR THIS GAME")
                return@withContext
            }
            val current = shell.readGameMode(targetPackage)
            if (current == null) {
                _state.value = _state.value.copy(gameModePerformance = null, message = "OEM GAME MODE STATE UNREADABLE • LEFT UNCHANGED")
                return@withContext
            }

            var snap = ensureSessionSnapshot()
            if (snap.gameMode == null) {
                snap = snap.copy(gameMode = current)
                snapshot = snap
                persistSnapshot(snap)
            }

            if (enabled) {
                if (current == "performance") {
                    // User/OEM already selected Performance; never claim ownership of it.
                    gameModeOwned = false
                    gameModeWritten = null
                    persistOwnedFlags()
                    _state.value = _state.value.copy(
                        gameModePerformance = true,
                        message = "OEM PERFORMANCE MODE ALREADY ACTIVE • LEFT USER/OEM OWNED",
                    )
                    return@withContext
                }
                updateBusy("OEM GAME MODE")
                val result = shell.setGameMode(targetPackage, "performance")
                val after = shell.readGameMode(targetPackage)
                val verified = result.isSuccess && after == "performance"
                if (verified) {
                    gameModeOwned = snap.gameMode != after
                    gameModeWritten = if (gameModeOwned) after else null
                    persistOwnedFlags()
                }
                _state.value = _state.value.copy(
                    busyAction = null,
                    gameModePerformance = after?.let { it == "performance" },
                    message = if (verified) "OEM PERFORMANCE MODE VERIFIED" else "OEM GAME MODE REJECTED: ${result.output.cleanShellMessage()}",
                )
                return@withContext
            }

            // Disable only what Game Nuke owns. If the user/OEM enabled Performance independently,
            // leave it untouched rather than silently forcing Standard.
            if (!gameModeOwned) {
                _state.value = _state.value.copy(
                    gameModePerformance = current == "performance",
                    message = if (current == "performance")
                        "OEM PERFORMANCE MODE IS USER/OEM OWNED • LEFT UNCHANGED"
                    else "OEM PERFORMANCE MODE OFF",
                )
                return@withContext
            }
            val written = gameModeWritten
            if (written == null || current != written) {
                gameModeOwned = false
                gameModeWritten = null
                persistOwnedFlags()
                _state.value = _state.value.copy(
                    gameModePerformance = current == "performance",
                    message = "OEM GAME MODE CHANGED OUTSIDE GAME NUKE • LEFT UNCHANGED",
                )
                return@withContext
            }
            val original = snap.gameMode
            if (original.isNullOrBlank()) {
                _state.value = _state.value.copy(message = "ORIGINAL OEM GAME MODE UNKNOWN • LEFT UNCHANGED")
                return@withContext
            }
            updateBusy("OEM GAME MODE RESTORE")
            val result = shell.setGameMode(targetPackage, original)
            val after = shell.readGameMode(targetPackage)
            val verified = result.isSuccess && after == original
            if (verified) {
                gameModeOwned = false
                gameModeWritten = null
                persistOwnedFlags()
            }
            _state.value = _state.value.copy(
                busyAction = null,
                gameModePerformance = after?.let { it == "performance" },
                message = if (verified) "OEM GAME MODE RESTORED" else "OEM GAME MODE RESTORE DEFERRED",
            )
        }
    }

    suspend fun togglePerformanceGameMode() = setPerformanceGameMode(state.value.gameModePerformance != true)

    suspend fun setBatterySaver(enabled: Boolean) = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!shell.connected()) {
                val opened = openSystemSettings(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
                _state.value = _state.value.copy(
                    batterySaverActive = readLocalBatterySaver(),
                    message = if (opened) "BATTERY SAVER NEEDS ANDROID CONTROL • SETTINGS OPENED"
                    else "BATTERY SAVER SETTINGS ALREADY OPENING",
                    phase = Phase.DEGRADED,
                )
                return@withContext
            }
            // PowerManager is an immediate in-process source of truth. Use the shell only as a
            // fallback, then spend device round-trips on the write and its mandatory read-back.
            val current = readLocalBatterySaver()?.let { if (it) "1" else "0" }
                ?: shell.readSetting("global", "low_power")
            if (current !in setOf("0", "1")) {
                val opened = openSystemSettings(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
                _state.value = _state.value.copy(
                    batterySaverActive = readLocalBatterySaver(),
                    message = if (opened) "BATTERY SAVER STATE UNREADABLE • ANDROID SETTINGS OPENED"
                    else "BATTERY SAVER SETTINGS ALREADY OPENING",
                )
                return@withContext
            }
            val expected = if (enabled) "1" else "0"
            if (current == expected) {
                _state.value = _state.value.copy(batterySaverActive = enabled, message = "BATTERY SAVER ${if (enabled) "ON" else "OFF"}")
                return@withContext
            }
            var snap = ensureSessionSnapshot()
            if (snap.lowPower == null) {
                snap = snap.copy(lowPower = current)
                snapshot = snap
                persistSnapshot(snap)
            }
            updateBusy("BATTERY SAVER")
            val result = shell.setPowerSaver(enabled)
            val after = shell.readSetting("global", "low_power")
            val verified = result.isSuccess && after == expected
            if (verified) {
                powerSaverOwned = snap.lowPower != after
                powerSaverWritten = if (powerSaverOwned) after else null
                persistOwnedFlags()
            }
            _state.value = _state.value.copy(
                busyAction = null,
                batterySaverActive = after?.let { it == "1" } ?: readLocalBatterySaver(),
                message = if (verified) "BATTERY SAVER ${if (enabled) "ON" else "OFF"} VERIFIED" else "BATTERY SAVER CONTROL REJECTED • ANDROID SETTINGS AVAILABLE",
            )
            if (!verified) openSystemSettings(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
        }
    }

    suspend fun toggleBatterySaver() = setBatterySaver(!(state.value.batterySaverActive ?: readLocalBatterySaver() ?: false))

    suspend fun setDataSaver(enabled: Boolean) = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!shell.connected()) {
                val opened = openSystemSettings("android.settings.DATA_SAVER_SETTINGS")
                _state.value = _state.value.copy(
                    dataSaverActive = readLocalDataSaver(),
                    message = if (opened) "DATA SAVER NEEDS ANDROID CONTROL • SETTINGS OPENED"
                    else "DATA SAVER SETTINGS ALREADY OPENING",
                    phase = Phase.DEGRADED,
                )
                return@withContext
            }
            val current = readLocalDataSaver() ?: shell.readDataSaverEnabled()
            if (current == null) {
                val opened = openSystemSettings("android.settings.DATA_SAVER_SETTINGS")
                _state.value = _state.value.copy(
                    dataSaverActive = readLocalDataSaver(),
                    message = if (opened) "DATA SAVER STATE UNREADABLE • ANDROID SETTINGS OPENED"
                    else "DATA SAVER SETTINGS ALREADY OPENING",
                )
                return@withContext
            }
            if (current == enabled) {
                _state.value = _state.value.copy(dataSaverActive = enabled, message = "DATA SAVER ${if (enabled) "ON" else "OFF"}")
                return@withContext
            }
            var snap = ensureSessionSnapshot()
            if (snap.dataSaver == null) {
                snap = snap.copy(dataSaver = current)
                snapshot = snap
                persistSnapshot(snap)
            }
            updateBusy("DATA SAVER")
            val result = shell.setDataSaver(enabled)
            val after = shell.readDataSaverEnabled()
            val verified = result.isSuccess && after == enabled
            if (verified) {
                dataSaverOwned = snap.dataSaver != after
                dataSaverWritten = if (dataSaverOwned) after else null
                persistOwnedFlags()
            }
            _state.value = _state.value.copy(
                busyAction = null,
                dataSaverActive = after ?: readLocalDataSaver(),
                message = if (verified) "DATA SAVER ${if (enabled) "ON" else "OFF"} VERIFIED" else "DATA SAVER CONTROL REJECTED • ANDROID SETTINGS AVAILABLE",
            )
            if (!verified) openSystemSettings("android.settings.DATA_SAVER_SETTINGS")
        }
    }

    suspend fun toggleDataSaver() = setDataSaver(!(state.value.dataSaverActive ?: readLocalDataSaver() ?: false))

    suspend fun setNetworkBoost(enabled: Boolean) = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (enabled) {
                if (wifiLock.isHeld() || _state.value.networkBoostActive) {
                    _state.value = _state.value.copy(wifiLockHeld = wifiLock.isHeld(), networkBoostActive = true, message = "NETWORK BOOST ACTIVE")
                    return@withContext
                }
                val lock = wifiLock.acquire()
                var shellLayer = false
                if (shell.connected()) {
                    val command = shell.forceWifiLowLatency(true)
                    val rejected = command.output.contains("permission", true) ||
                        command.output.contains("unknown", true) || command.output.contains("unsupported", true) ||
                        command.output.contains("root", true) || command.output.contains("error", true)
                    shellLayer = command.isSuccess && !rejected
                    if (shellLayer) {
                        forcedWifiShellOwned = true
                        persistOwnedFlags()
                    }
                }
                val active = lock.held || shellLayer
                _state.value = _state.value.copy(
                    wifiLockHeld = lock.held,
                    networkBoostActive = active,
                    message = when {
                        lock.held && shellLayer -> "NETWORK BOOST ACTIVE • LOW-LATENCY WIFI + DEVICE LAYER"
                        lock.held -> "NETWORK BOOST ACTIVE • ${lock.detail.uppercase()}"
                        shellLayer -> "NETWORK BOOST ACTIVE • DEVICE LOW-LATENCY LAYER"
                        else -> "NETWORK BOOST UNAVAILABLE • ${lock.detail.uppercase()}"
                    },
                )
            } else {
                wifiLock.release()
                if (forcedWifiShellOwned && shell.connected()) runCatching { shell.forceWifiLowLatency(false) }
                forcedWifiShellOwned = false
                persistOwnedFlags()
                _state.value = _state.value.copy(wifiLockHeld = false, networkBoostActive = false, message = "NETWORK BOOST RELEASED")
            }
        }
    }

    suspend fun toggleNetworkBoost() = setNetworkBoost(!state.value.networkBoostActive)

    suspend fun sweepSafeBackground() = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!shell.connected() || !shell.supportsSafeBackgroundSweep()) {
                setMessage("BACKGROUND SWEEP NEEDS EXTENDED DEVICE CONTROL", Phase.DEGRADED)
                return@withContext
            }
            updateBusy("SAFE BACKGROUND SWEEP")
            val before = readMemoryStatus()
            val candidates = shell.readProcessMemoryRows()
                .asSequence()
                .filter { isSafeBackgroundCandidate(it.packageName) }
                .sortedByDescending { it.rssKb }
                .take(8)
                .toList()

            var requested = 0
            var accepted = 0
            for (candidate in candidates) {
                requested++
                val result = runCatching { shell.killBackgroundPackage(candidate.packageName) }.getOrNull()
                if (result?.isSuccess == true) accepted++
            }
            if (requested > 0) delay(450L)
            val after = readMemoryStatus()
            val gain = ((after.available - before.available) / MIB).coerceAtLeast(0L)
            _state.value = _state.value.copy(
                busyAction = null,
                ramAvailableMb = after.available / MIB,
                ramTotalMb = after.total / MIB,
                lastMemoryGainMb = gain,
                message = when {
                    requested == 0 -> "NO SAFE USER BACKGROUND PROCESS CANDIDATE FOUND"
                    accepted > 0 -> "SAFE BACKGROUND SWEEP • $accepted/$requested RELEASE REQUESTS • +${gain}MB AVAILABLE"
                    else -> "BACKGROUND RELEASE REQUESTS WERE REJECTED • GAME/SYSTEM LEFT UNCHANGED"
                },
            )
        }
    }

    private fun isSafeBackgroundCandidate(packageName: String): Boolean {
        if (packageName == appContext.packageName || packageName == targetPackage) return false
        val lower = packageName.lowercase()
        if (lower == "android" || lower.startsWith("com.android.") ||
            lower.startsWith("com.google.android.gms") || lower.startsWith("com.google.android.gsf") ||
            lower.contains("systemui") || lower.contains("launcher") || lower.contains("inputmethod") ||
            lower.contains("keyboard") || lower.contains("dialer") || lower.contains("telecom")) return false

        val pm = appContext.packageManager
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.getApplicationInfo(packageName, 0)
            }
        }.getOrNull() ?: return false
        val system = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (system) return false

        // Limit the sweep to visible user apps with a launcher entry. Unknown/headless packages are
        // left untouched to avoid disrupting VPNs, accessibility helpers, wearables or OEM agents.
        return GameCatalogRepository.resolveLaunchIntent(appContext, packageName) != null
    }

    suspend fun setRotationLock(enabled: Boolean) = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!shell.connected()) {
                _state.value = _state.value.copy(rotationLocked = null)
                setMessage("ROTATION CONTROL NEEDS EXTENDED DEVICE CONTROL", Phase.DEGRADED)
                return@withContext
            }
            val currentAuto = shell.readSetting("system", "accelerometer_rotation")
            val currentUser = shell.readSetting("system", "user_rotation")
            if (currentAuto !in setOf("0", "1")) {
                _state.value = _state.value.copy(rotationLocked = null, message = "ROTATION CONTROL UNSUPPORTED ON THIS DEVICE")
                return@withContext
            }
            val currentlyLocked = currentAuto == "0"
            if (currentlyLocked == enabled) {
                _state.value = _state.value.copy(rotationLocked = enabled, message = if (enabled) "ROTATION LOCKED" else "AUTO-ROTATE ACTIVE")
                return@withContext
            }
            if (!prefs.safeBoolean(K_ROTATION_OWNED, false)) {
                prefs.edit()
                    .putBoolean(K_ROTATION_OWNED, true)
                    .putString(K_ROTATION_AUTO, currentAuto)
                    .putString(K_ROTATION_USER, currentUser ?: NULL)
                    .apply()
            }
            updateBusy("ROTATION LOCK")
            val writeOk = if (enabled) {
                val rotation = runCatching {
                    val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    @Suppress("DEPRECATION")
                    wm?.defaultDisplay?.rotation ?: 1
                }.getOrDefault(1).coerceIn(0, 3)
                shell.writeNumericSetting("system", "user_rotation", rotation.toString()).isSuccess &&
                    shell.writeNumericSetting("system", "accelerometer_rotation", "0").isSuccess
            } else {
                shell.writeNumericSetting("system", "accelerometer_rotation", "1").isSuccess
            }
            val after = shell.readSetting("system", "accelerometer_rotation")
            val afterUser = shell.readSetting("system", "user_rotation")
            val verified = writeOk && after == if (enabled) "0" else "1"
            if (verified) {
                prefs.edit()
                    .putString(K_ROTATION_WRITTEN_AUTO, after ?: NULL)
                    .putString(K_ROTATION_WRITTEN_USER, afterUser ?: NULL)
                    .apply()
            }
            _state.value = _state.value.copy(
                busyAction = null,
                rotationLocked = after?.let { it == "0" },
                message = if (verified) {
                    if (enabled) "ROTATION LOCKED TO CURRENT ORIENTATION" else "AUTO-ROTATE ENABLED FOR THIS SESSION"
                } else "ROTATION CONTROL REJECTED",
            )
        }
    }

    suspend fun toggleRotationLock() = setRotationLock(!(state.value.rotationLocked ?: false))

    private fun readLocalBatterySaver(): Boolean? {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return runCatching { pm.isPowerSaveMode }.getOrNull()
    }

    private fun readLocalDataSaver(): Boolean? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return runCatching {
            when (cm.restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> true
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> false
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> false
                else -> null
            }
        }.getOrNull()
    }

    private fun openSystemSettings(action: String): Boolean {
        if (!NukeRuntimeState.tryBeginExternalNavigation()) return false
        val destination = when (action) {
            android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS -> "Battery Saver settings"
            "android.settings.DATA_SAVER_SETTINGS" -> "Data Saver settings"
            else -> "Android settings"
        }
        runCatching { onBeforeExternalNavigation?.invoke(destination) }
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        return runCatching {
            appContext.startActivity(Intent(action).addFlags(flags))
            NukeRuntimeState.markExternalNavigationOpened()
            true
        }.recoverCatching {
            appContext.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(flags))
            NukeRuntimeState.markExternalNavigationOpened()
            true
        }.getOrDefault(false)
    }

    fun launchTargetGame(): Boolean = runCatching {
        val launch = GameCatalogRepository.resolveLaunchIntent(appContext, targetPackage)
            ?: return@runCatching false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        appContext.startActivity(launch)
        true
    }.getOrDefault(false)

    /**
     * Maximizes the device's native frame path without claiming to unlock game FPS.
     * The method only writes settings already owned by this session, verifies read-back, observes
     * thermal safety, and deliberately avoids an OEM Performance profile when it declares an FPS cap.
     */
    suspend fun maximizeNativeFps() = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!shell.connected()) {
                setMessage("EXTENDED CONTROL REQUIRED FOR NATIVE FPS MAXIMIZER", Phase.DEGRADED)
                return@withContext
            }
            if (snapshot == null) {
                probeInternal()
                activateInternal()
            }
            val snap = snapshot
            if (snap == null) {
                setMessage("SESSION SNAPSHOT UNAVAILABLE • FPS PATH LEFT UNCHANGED", Phase.DEGRADED)
                return@withContext
            }

            updateBusy("NATIVE FPS MAXIMIZER")
            val metrics = readMetrics()
            val applied = _state.value.appliedFeatures.toMutableSet()
            var verifiedLayers = 0
            val notes = mutableListOf<String>()

            if (metrics.maxHz > 0) {
                val target = metrics.maxHz.toFloat().toString()
                val peak = shell.writeNumericSetting("system", "peak_refresh_rate", target)
                val peakRead = shell.readSetting("system", "peak_refresh_rate")
                if (peak.isSuccess && numericClose(peakRead, target)) {
                    refreshPeakOwned = refreshPeakOwned || (snap.peakRefresh?.let { !numericClose(it, target) } ?: true)
                    maxRefreshWritten = target
                    applied += "PEAK_${metrics.maxHz}HZ"
                    verifiedLayers++
                }

                val thermalSafe = metrics.thermalStatus <= PowerManager.THERMAL_STATUS_LIGHT &&
                    (metrics.thermalHeadroom == null || metrics.thermalHeadroom < THERMAL_RELEASE_AT)
                if (thermalSafe && metrics.maxHz >= 90) {
                    val min = shell.writeNumericSetting("system", "min_refresh_rate", target)
                    val minRead = shell.readSetting("system", "min_refresh_rate")
                    if (min.isSuccess && numericClose(minRead, target)) {
                        refreshMinOwned = refreshMinOwned || (snap.minRefresh?.let { !numericClose(it, target) } ?: true)
                        minRefreshWritten = target
                        applied += "REFRESH_FLOOR"
                        verifiedLayers++
                    }
                } else if (!thermalSafe) {
                    notes += "THERMAL GUARD"
                }
            }

            when (shell.readSetting("global", "low_power")) {
                "1" -> notes += "BATTERY SAVER ACTIVE"
                "0" -> verifiedLayers++
            }

            if (Build.VERSION.SDK_INT >= 35) {
                val currentGuard = shell.readGameDefaultFrameRateGuard()
                if (!currentGuard.equals("true", ignoreCase = true)) {
                    val result = shell.setGameDefaultFrameRateGuard(true)
                    if (result.isSuccess && shell.readGameDefaultFrameRateGuard().equals("true", ignoreCase = true)) {
                        frameGuardOwned = true
                        applied += "ANDROID15_60HZ_GUARD_OFF"
                        verifiedLayers++
                    }
                } else {
                    verifiedLayers++
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val modes = parseGameModes(shell.gameModes(targetPackage))
                if (modes?.available?.contains("performance") == true) {
                    val overlay = shell.readGameOverlayConfig(targetPackage)
                    val cap = shell.performanceProfileFpsCap(overlay)
                    when {
                        cap != null -> notes += "OEM PERF ${cap}FPS CAP AVOIDED"
                        modes.current == "performance" -> notes += "OEM PERF ACTIVE"
                        else -> notes += "OEM PERF LEFT USER/OEM CONTROLLED"
                    }
                }
            }

            persistOwnedFlags()
            val latest = readMetrics()
            _state.value = _state.value.copy(
                phase = if (verifiedLayers > 0) Phase.ACTIVE else Phase.DEGRADED,
                busyAction = null,
                currentHz = latest.currentHz,
                maxHz = latest.maxHz,
                thermalStatus = latest.thermalStatus,
                thermalHeadroom = latest.thermalHeadroom,
                appliedFeatures = applied,
                message = buildString {
                    if (verifiedLayers > 0) append("NATIVE FPS PATH MAXIMIZED • $verifiedLayers VERIFIED LAYERS")
                    else append("NO WRITABLE FPS LAYER VERIFIED")
                    if (notes.isNotEmpty()) append(" • ${notes.joinToString(" • ")}")
                },
            )
        }
    }

    suspend fun toggleGpuRelief() = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!shell.connected()) {
                setMessage("EXTENDED CONTROL REQUIRED FOR GPU RELIEF", Phase.DEGRADED)
                return@withContext
            }
            var snap = snapshot ?: run {
                setMessage("ACTIVATE NUKE CORE BEFORE GPU RELIEF", Phase.DEGRADED)
                return@withContext
            }
            if (!shell.supportsGameOverlayConfig(targetPackage)) {
                setMessage("GAME INTERVENTION CONFIG UNSUPPORTED ON THIS OEM", Phase.DEGRADED)
                return@withContext
            }

            if (!gameOverlayOwned) {
                val existing = shell.readGameOverlayConfig(targetPackage)
                if (snap.gameOverlayConfig == null && existing != null) {
                    snap = snap.copy(gameOverlayConfig = existing)
                    snapshot = snap
                    persistSnapshot(snap)
                }
                val fpsCap = shell.performanceProfileFpsCap(existing)
                if (fpsCap != null) {
                    setMessage("GPU RELIEF BLOCKED • OEM PERFORMANCE PROFILE DECLARES ${fpsCap}FPS CAP", Phase.DEGRADED)
                    return@withContext
                }
                val expected = shell.buildGameOverlayGpuReliefConfig(existing)
                if (expected == null) {
                    setMessage("OEM GAME CONFIG NOT SAFELY PATCHABLE • LEFT UNCHANGED", Phase.DEGRADED)
                    return@withContext
                }
                if (existing == expected && snap.gameOverlayConfig == expected) {
                    _state.value = _state.value.copy(
                        busyAction = null,
                        gpuReliefActive = true,
                        appliedFeatures = _state.value.appliedFeatures + "GPU_RELIEF_90",
                        message = "GPU RELIEF 90 ALREADY ACTIVE • OEM/EXTERNAL CONFIG LEFT UNOWNED",
                    )
                    return@withContext
                }

                updateBusy("GPU RELIEF 90")
                val write = shell.setGameOverlayGpuRelief(targetPackage, existing)
                val readback = shell.readGameOverlayConfig(targetPackage)
                if (write.isSuccess && readback == expected) {
                    gameOverlayOwned = snap.gameOverlayConfig != expected
                    gameOverlayWritten = expected

                    // Do not switch OEM game mode automatically. Downscale is armed only;
                    // if the user/OEM selects Performance mode, Android may apply it on next start.
                    persistOwnedFlags()
                    _state.value = _state.value.copy(
                        busyAction = null,
                        gpuReliefActive = true,
                        appliedFeatures = _state.value.appliedFeatures + "GPU_RELIEF_90",
                        message = "GPU RELIEF 90 ARMED • RESTART GAME • PLATFORM/GAME MAY OVERRIDE",
                    )
                } else {
                    clearBusy("GPU RELIEF REJECTED: ${write.output.cleanShellMessage()}")
                }
            } else {
                updateBusy("GPU RELIEF RESTORE")
                val current = shell.readGameOverlayConfig(targetPackage)
                val written = gameOverlayWritten
                val stillOwned = written != null && current == written
                if (stillOwned) {
                    val restore = shell.restoreGameOverlayConfig(targetPackage, snap.gameOverlayConfig)
                    val after = shell.readGameOverlayConfig(targetPackage)
                    val verified = restore.isSuccess && after == snap.gameOverlayConfig
                    if (!verified) {
                        clearBusy("GPU RELIEF RESTORE FAILED • OWNERSHIP RETAINED FOR SESSION RESTORE")
                        return@withContext
                    }
                }
                gameOverlayOwned = false
                gameOverlayWritten = null
                persistOwnedFlags()
                _state.value = _state.value.copy(
                    busyAction = null,
                    gpuReliefActive = false,
                    appliedFeatures = _state.value.appliedFeatures - "GPU_RELIEF_90",
                    message = if (stillOwned) {
                        "GPU RELIEF CONFIG RESTORED • RESTART GAME"
                    } else {
                        "GPU CONFIG CHANGED OUTSIDE GAME NUKE • LEFT UNCHANGED"
                    },
                )
            }
        }
    }

    suspend fun deepReclaim() = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!shell.connected()) {
                setMessage("EXTENDED CONTROL REQUIRED FOR DEEP RECLAIM", Phase.DEGRADED)
                return@withContext
            }
            updateBusy("DEEP RECLAIM")
            val beforeStatus = readMemoryStatus()
            val compact = shell.compactSystem()
            if (!compact.isSuccess) {
                clearBusy("SYSTEM COMPACTION UNSUPPORTED: ${compact.output.cleanShellMessage()}")
                return@withContext
            }
            delay(650L)
            var afterStatus = readMemoryStatus()
            var killed = false
            // Use Android's own low-memory signal/threshold. kill-all only targets processes the
            // framework already considers safe-to-kill, and only when pressure still exists.
            val pressureFloor = maxOf(afterStatus.threshold + afterStatus.threshold / 4L, afterStatus.total / 8L)
            if (afterStatus.lowMemory || afterStatus.available < pressureFloor) {
                val kill = shell.killSafeBackground()
                killed = kill.isSuccess
                if (killed) {
                    delay(750L)
                    afterStatus = readMemoryStatus()
                }
            }
            val gain = ((afterStatus.available - beforeStatus.available) / MIB).coerceAtLeast(0L)
            _state.value = _state.value.copy(
                busyAction = null,
                ramAvailableMb = afterStatus.available / MIB,
                ramTotalMb = afterStatus.total / MIB,
                lastMemoryGainMb = gain,
                message = if (killed) "RECLAIM + SAFE BACKGROUND KILL: +${gain}MB" else "SYSTEM COMPACTION: +${gain}MB",
            )
        }
    }

    suspend fun trimCachesForStoragePressure() = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!shell.connected()) {
                setMessage("EXTENDED CONTROL REQUIRED FOR CACHE PURGE", Phase.DEGRADED)
                return@withContext
            }
            updateBusy("CACHE PURGE")
            val before = readStorageBytes()
            val target = cacheTargetFreeBytes(before.free, before.total)
            if (target <= before.free || target <= 0L) {
                _state.value = _state.value.copy(
                    busyAction = null,
                    lastCacheGainMb = 0L,
                    message = "CACHE SWEEP SKIPPED • NO SAFE HEADROOM TARGET",
                )
                return@withContext
            }
            val result = shell.trimCaches(target)
            val after = readStorageBytes()
            val gain = ((after.free - before.free) / MIB).coerceAtLeast(0L)
            _state.value = _state.value.copy(
                busyAction = null,
                storageFreeGb = bytesToGb(after.free),
                storageTotalGb = bytesToGb(after.total),
                lastCacheGainMb = gain,
                message = if (result.isSuccess) {
                    "CACHE TRIM COMPLETE: +${gain}MB FREE"
                } else {
                    "CACHE TRIM REJECTED: ${result.output.cleanShellMessage()}"
                },
            )
        }
    }

    suspend fun sampleActualGameFps(sampleMs: Long = 5_000L) = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!shell.connected()) {
                setMessage("EXTENDED CONTROL REQUIRED FOR FRAME SCAN", Phase.DEGRADED)
                return@withContext
            }
            updateBusy("FRAME SCAN")
            val start = shell.beginFrameTimestats()
            if (!start.isSuccess) {
                clearBusy("SURFACEFLINGER TIMESTATS UNSUPPORTED: ${start.output.cleanShellMessage()}")
                return@withContext
            }
            val dump = try {
                delay(sampleMs.coerceIn(3_000L, 10_000L))
                shell.dumpFrameTimestats()
            } finally {
                shell.endFrameTimestats()
            }
            if (!dump.isSuccess) {
                clearBusy("FRAME SCAN FAILED: ${dump.output.cleanShellMessage()}")
                return@withContext
            }
            val sample = parseFrameTimestats(dump.output)
            if (sample == null) {
                clearBusy("NO GAME SURFACE SAMPLE • KEEP GAME RENDERING AND RETRY")
            } else {
                _state.value = _state.value.copy(
                    busyAction = null,
                    lastMeasuredFps = sample.first,
                    lastFrameCount = sample.second,
                    message = "MEASURED GAME FPS ${String.format(java.util.Locale.US, "%.1f", sample.first)} • ${sample.second} FRAMES",
                )
            }
        }
    }

    suspend fun primeGameArt() = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!shell.connected()) {
                setMessage("EXTENDED CONTROL REQUIRED FOR ART PRIME", Phase.DEGRADED)
                return@withContext
            }
            val version = packageLastUpdateTime()
            val lastCompiled = prefs.safeLong("art_prime_$targetPackage", -1L)
            if (version > 0L && version == lastCompiled) {
                setMessage("ART PRIME ALREADY APPLIED TO THIS GAME VERSION")
                return@withContext
            }
            val metrics = readMetrics()
            if (metrics.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                setMessage("ART PRIME BLOCKED • DEVICE TOO HOT", Phase.DEGRADED)
                return@withContext
            }
            updateBusy("ART PRIME")
            val profile = shell.compileSpeedProfile(targetPackage)
            val result = if (profile.isSuccess) {
                profile
            } else if (metrics.storageFreeGb >= 4f) {
                // Explicit user action: full speed compilation is a documented ART compile mode.
                shell.compileSpeed(targetPackage)
            } else {
                profile
            }
            if (result.isSuccess && version > 0L) prefs.edit().putLong("art_prime_$targetPackage", version).apply()
            clearBusy(
                if (result.isSuccess) "ART BYTECODE PRIME COMPLETE" else
                    "ART PRIME FAILED: ${result.output.cleanShellMessage()}",
            )
        }
    }

    suspend fun restoreSession() = operationMutex.withLock {
        withContext(Dispatchers.IO) { restoreInternal(clearState = true) }
    }

    /** Release only app-owned resources; safe to call synchronously from Service.onDestroy(). */
    fun releaseLocalResources() {
        // DND is a local app-owned policy and can be restored even when the ADB transport vanished.
        restoreDndIfOwned()
        wifiLock.release()
    }

    private fun probeInternal() {
        val phaseBeforeProbe = _state.value.phase
        val messageBeforeProbe = _state.value.message
        val featuresBeforeProbe = _state.value.appliedFeatures
        if (_state.value.busyAction == null) {
            _state.value = _state.value.copy(phase = Phase.PROBING, message = "PROBING DEVICE CAPABILITIES")
        }
        val metrics = readMetrics()
        val adbConnected = shell.connected()
        val capabilities = mutableListOf<Capability>()

        capabilities += Capability(
            "device",
            "Universal device profile",
            true,
            "${deviceProfile.compactLabel} • ${deviceProfile.soc} • ${deviceProfile.androidLabel}",
        )
        capabilities += Capability(
            "wireless_debug",
            "Android Wireless debugging",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) deviceProfile.wirelessDebugHint else "Android 11+ required",
        )
        capabilities += Capability(
            "dnd",
            "Game Focus / DND",
            hasDndAccess(),
            if (hasDndAccess()) "Priority interruptions can be session-scoped and restored" else "Grant Do Not Disturb access for the floating Focus switch",
        )
        capabilities += Capability(
            "media_capture",
            "Screen recording",
            false,
            "Not exposed in the Play-oriented build; a future version must use MediaProjection + Android consent",
        )

        capabilities += Capability(
            "display",
            "Native display modes",
            metrics.maxHz > 0,
            if (metrics.maxHz > 0) "Physical panel up to ${metrics.maxHz}Hz" else "Display modes unavailable",
        )
        capabilities += Capability(
            "thermal",
            "Thermal governor",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "Thermal headroom API available" else "Thermal status fallback only",
        )
        capabilities += Capability(
            "wifi_lock",
            "Wi-Fi session lock",
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
            "Wi-Fi radio performance lock; low-latency activation remains subject to Android foreground/hardware policy",
        )
        capabilities += Capability(
            "wifi_link",
            "Wi-Fi link quality",
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
            "In-process RSSI/link-speed telemetry; redacted fields degrade gracefully on OEM builds",
        )
        capabilities += Capability(
            "session_battery",
            "Session battery summary",
            true,
            "Local battery percent/charge-counter baseline; no global batterystats reset",
        )
        capabilities += Capability(
            "adb",
            "Extended Device Control",
            adbConnected,
            if (adbConnected) "Trusted device-control connection active" else "Connect Wireless Debugging to unlock extended controls",
        )

        if (adbConnected) {
            val refreshReadable = shell.readSetting("system", "peak_refresh_rate") != null || metrics.maxHz > 0
            capabilities += Capability("refresh_guard", "Refresh guard", refreshReadable, "Session-scoped peak/min refresh with rollback")
            capabilities += Capability(
                "display_guard",
                "Native display guard",
                true,
                "Boost never writes wm size/density; legacy journal recovery remains available",
            )
            capabilities += Capability("frame_scan", "SurfaceFlinger FPS scan", shell.supportsFrameTimestats(), "On-demand measured game FPS via timestats")
            capabilities += Capability("cpu_clocks", "Per-core CPU clocks", shell.supportsCpuClocks(), "Read-only scaling_cur_freq telemetry; no governor/frequency writes")
            capabilities += Capability("compact", "System compaction", shell.supportsSystemCompaction(), "ActivityManager system compaction")
            capabilities += Capability("cache_trim", "Package cache trim", shell.supportsCacheTrim(), "Manual measured cache sweep toward a bounded free-space target")
            capabilities += Capability("art", "ART game prime", shell.supportsPackageCompile(), "DEX/AOT compile path supported")
            capabilities += Capability("power_saver", "Battery saver", shell.supportsPowerSaver(), "Verified Android power saver control")
            capabilities += Capability("data_saver", "Data saver", shell.supportsDataSaver(), "Verified Android netpolicy restrict-background control")
            val rotationControl = shell.readSetting("system", "accelerometer_rotation") in setOf("0", "1")
            capabilities += Capability("rotation_lock", "Rotation lock", rotationControl, "Session-owned orientation lock with exact rollback")
            capabilities += Capability("background_sweep", "Background sweep", shell.supportsSafeBackgroundSweep(), "Package-scoped background release path; foreground game and system apps are protected")
            val gpuReliefSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && shell.supportsGameOverlayConfig(targetPackage)
            capabilities += Capability(
                "gpu_relief",
                "Per-game GPU relief",
                gpuReliefSupported,
                if (gpuReliefSupported) "Android game_overlay 90% backbuffer preset • restart required • platform/game may opt out" else "OEM Game Mode intervention unavailable",
            )

            val gameModeInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                parseGameModes(shell.gameModes(targetPackage))
            } else null
            val gameModeCandidate = gameModeInfo?.available?.contains("performance") == true
            capabilities += Capability(
                "game_mode",
                "Android/OEM Game Mode",
                gameModeCandidate,
                if (gameModeCandidate) "Performance available • current ${gameModeInfo?.current ?: "unknown"}"
                else "Framework performance mode not reported",
            )
            capabilities += Capability(
                "android15_frame_guard",
                "Android 15 game frame guard",
                Build.VERSION.SDK_INT >= 35,
                if (Build.VERSION.SDK_INT >= 35) "Runtime property will be verified on activation" else "Android 15+ only",
            )
        }

        val live = _state.value
        val probeOwnsPhase = live.phase == Phase.PROBING
        val probeOwnsMessage = live.message == "PROBING DEVICE CAPABILITIES"
        _state.value = live.copy(
            phase = if (probeOwnsPhase) phaseBeforeProbe else live.phase,
            adbConnected = adbConnected,
            currentHz = metrics.currentHz,
            maxHz = metrics.maxHz,
            thermalStatus = metrics.thermalStatus,
            thermalHeadroom = metrics.thermalHeadroom,
            ramAvailableMb = metrics.ramAvailableMb,
            ramTotalMb = metrics.ramTotalMb,
            storageFreeGb = metrics.storageFreeGb,
            storageTotalGb = metrics.storageTotalGb,
            capabilities = capabilities,
            deviceLabel = deviceProfile.compactLabel.uppercase(),
            socLabel = deviceProfile.soc.uppercase(),
            dndAccess = hasDndAccess(),
            dndActive = isDndActive(),
            gameModePerformance = if (adbConnected) shell.readGameMode(targetPackage)?.let { it == "performance" } else null,
            batterySaverActive = if (adbConnected) shell.readSetting("global", "low_power")?.let { it == "1" } ?: readLocalBatterySaver() else readLocalBatterySaver(),
            dataSaverActive = if (adbConnected) shell.readDataSaverEnabled() ?: readLocalDataSaver() else readLocalDataSaver(),
            networkBoostActive = wifiLock.isHeld(),
            rotationLocked = if (adbConnected) shell.readSetting("system", "accelerometer_rotation")?.let { it == "0" } else null,
            appliedFeatures = live.appliedFeatures.ifEmpty { featuresBeforeProbe },
            message = if (probeOwnsMessage) {
                if (phaseBeforeProbe == Phase.ACTIVE || phaseBeforeProbe == Phase.DEGRADED) messageBeforeProbe
                else "CORE MATRIX ${capabilities.count { it.supported }}/${capabilities.size} READY"
            } else live.message,
        )
    }

    private fun activateInternal() {
        if (_state.value.phase == Phase.ACTIVE) return
        _state.value = _state.value.copy(phase = Phase.APPLYING, busyAction = "NUKE CORE", message = "APPLYING SESSION CORE")
        val applied = linkedSetOf<String>()

        // Network Boost is now an explicit user control instead of an always-on hidden side effect.

        if (shell.connected()) {
            val metrics = readMetrics()
            val snap = captureSessionSnapshot()
            snapshot = snap
            persistSnapshot(snap)

            if (metrics.maxHz > 0) {
                val target = metrics.maxHz.toFloat().toString()
                val peak = shell.writeNumericSetting("system", "peak_refresh_rate", target)
                val peakRead = shell.readSetting("system", "peak_refresh_rate")
                if (peak.isSuccess && numericClose(peakRead, target)) {
                    refreshPeakOwned = snap.peakRefresh?.let { !numericClose(it, target) } ?: true
                    maxRefreshWritten = target
                    applied += "PEAK_${metrics.maxHz}HZ"
                }

                // Locking the minimum refresh can improve display residency, but only while the
                // thermal state is healthy. Thermal hysteresis releases this floor before severe throttling.
                val thermalSafe = metrics.thermalStatus <= PowerManager.THERMAL_STATUS_LIGHT &&
                    (metrics.thermalHeadroom == null || metrics.thermalHeadroom < THERMAL_RELEASE_AT)
                if (thermalSafe && metrics.maxHz >= 90) {
                    val min = shell.writeNumericSetting("system", "min_refresh_rate", target)
                    val minRead = shell.readSetting("system", "min_refresh_rate")
                    if (min.isSuccess && numericClose(minRead, target)) {
                        refreshMinOwned = snap.minRefresh?.let { !numericClose(it, target) } ?: true
                        minRefreshWritten = target
                        applied += "REFRESH_FLOOR"
                    }
                }
            }

            // Battery Saver is an explicit user control. Core activation never silently changes it.

            // Do not force OEM Performance Game Mode automatically. OEM profiles may contain
            // frame-rate interventions/caps. Game Nuke protects the native frame path instead.

            if (Build.VERSION.SDK_INT >= 35 && !snap.gameDefaultFrameGuard.equals("true", ignoreCase = true)) {
                val frameGuard = shell.setGameDefaultFrameRateGuard(true)
                val verified = shell.readGameDefaultFrameRateGuard().equals("true", ignoreCase = true)
                if (frameGuard.isSuccess && verified) {
                    frameGuardOwned = true
                    applied += "ANDROID15_60HZ_GUARD_OFF"
                }
            }

            // Network Boost is also explicit; do not hide an OEM Wi-Fi mutation inside activation.

            val memoryPressure = readMemoryStatus()
            if ((memoryPressure.lowMemory ||
                    memoryPressure.available < memoryPressure.threshold + memoryPressure.threshold / 4L) &&
                shell.supportsSystemCompaction()) {
                if (shell.compactSystem().isSuccess) applied += "MEM_COMPACT"
            }
        }

        // Persist ownership only after all verified writes are known, so process-death recovery
        // can rollback exactly the settings Game Nuke changed.
        persistOwnedFlags()
        val latest = readMetrics()
        _state.value = _state.value.copy(
            phase = if (applied.isEmpty()) Phase.DEGRADED else Phase.ACTIVE,
            adbConnected = shell.connected(),
            currentHz = latest.currentHz,
            maxHz = latest.maxHz,
            thermalStatus = latest.thermalStatus,
            thermalHeadroom = latest.thermalHeadroom,
            ramAvailableMb = latest.ramAvailableMb,
            ramTotalMb = latest.ramTotalMb,
            storageFreeGb = latest.storageFreeGb,
            storageTotalGb = latest.storageTotalGb,
            wifiLockHeld = wifiLock.isHeld(),
            dndAccess = hasDndAccess(),
            dndActive = isDndActive(),
            gpuReliefActive = gameOverlayOwned,
            appliedFeatures = applied,
            busyAction = null,
            message = when {
                applied.isEmpty() -> "CORE ACTIVE IN MONITOR MODE"
                "MEM_COMPACT" in applied -> "NUKE CORE ACTIVE • ${applied.size} LAYERS • AUTO MEMORY COMPACT"
                else -> "NUKE CORE ACTIVE • ${applied.size} LAYERS"
            },
        )
    }

    private fun applyThermalHysteresis(metrics: Metrics) {
        if (!refreshMinOwned || !shell.connected()) return
        val state = _state.value
        val hot = metrics.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ||
            (metrics.thermalHeadroom != null && metrics.thermalHeadroom >= THERMAL_RELEASE_AT)
        val cool = metrics.thermalStatus <= PowerManager.THERMAL_STATUS_LIGHT &&
            (metrics.thermalHeadroom == null || metrics.thermalHeadroom <= THERMAL_RESTORE_AT)

        if (hot && !state.thermalGuarded) {
            restoreSingleSetting("system", "min_refresh_rate", snapshot?.minRefresh)
            _state.value = _state.value.copy(
                thermalGuarded = true,
                message = "THERMAL GUARD • REFRESH FLOOR RELEASED",
            )
        } else if (cool && state.thermalGuarded) {
            val target = minRefreshWritten
            if (target != null) {
                val result = shell.writeNumericSetting("system", "min_refresh_rate", target)
                val verified = result.isSuccess && numericClose(shell.readSetting("system", "min_refresh_rate"), target)
                if (verified) {
                    _state.value = _state.value.copy(
                        thermalGuarded = false,
                        message = "THERMAL RECOVERED • REFRESH FLOOR RESTORED",
                    )
                }
            }
        }
    }

    private fun restoreInternal(clearState: Boolean, gameModePackage: String = targetPackage) {
        _state.value = _state.value.copy(phase = Phase.RESTORING, busyAction = "RESTORE", message = "RESTORING OWNED SESSION CHANGES")
        val snap = snapshot ?: loadPersistedSnapshot()
        val needsShellRollback = prefs.safeBoolean(K_PEAK_OWNED, false) ||
            prefs.safeBoolean(K_MIN_OWNED, false) || prefs.safeBoolean(K_POWER_OWNED, false) ||
            prefs.safeBoolean(K_FRAME_OWNED, false) || prefs.safeBoolean(K_GAME_MODE_OWNED, false) ||
            prefs.safeBoolean(K_WIFI_SHELL_OWNED, false) || prefs.safeBoolean(K_GAME_OVERLAY_OWNED, false) ||
            prefs.safeBoolean(K_DATA_SAVER_OWNED, false) || prefs.safeBoolean(K_ROTATION_OWNED, false)
        val shellReady = shell.connected()
        if (needsShellRollback && !shellReady) {
            // Keep the exact snapshot/ownership flags. The next trusted local-core/ADB session will
            // recover them before applying a new session, instead of silently stranding settings.
            restoreDndIfOwned()
            wifiLock.release()
            _state.value = _state.value.copy(
                phase = Phase.DEGRADED,
                busyAction = null,
                networkBoostActive = false,
                wifiLockHeld = false,
                message = "RESTORE PENDING • RECONNECT TRUSTED CORE",
            )
            return
        }

        if (shellReady && snap != null) {
            if (refreshPeakOwned || prefs.safeBoolean(K_PEAK_OWNED, false)) {
                restoreSettingIfStillOwned("system", "peak_refresh_rate", maxRefreshWritten ?: prefs.safeNullableString(K_PEAK_WRITTEN), snap.peakRefresh)
            }
            if (refreshMinOwned || prefs.safeBoolean(K_MIN_OWNED, false)) {
                restoreSettingIfStillOwned("system", "min_refresh_rate", minRefreshWritten ?: prefs.safeNullableString(K_MIN_WRITTEN), snap.minRefresh)
            }
            if (powerSaverOwned || prefs.safeBoolean(K_POWER_OWNED, false)) {
                val written = powerSaverWritten ?: prefs.safeNullableString(K_POWER_WRITTEN)
                val current = shell.readSetting("global", "low_power")
                if (written != null && current == written && snap.lowPower in setOf("0", "1")) {
                    shell.setPowerSaver(snap.lowPower == "1")
                }
            }
            if (dataSaverOwned || prefs.safeBoolean(K_DATA_SAVER_OWNED, false)) {
                val written = dataSaverWritten ?: prefs.takeIf { it.contains(K_DATA_SAVER_WRITTEN) }?.safeBoolean(K_DATA_SAVER_WRITTEN, false)
                val current = shell.readDataSaverEnabled()
                if (written != null && current == written && snap.dataSaver != null) {
                    shell.setDataSaver(snap.dataSaver)
                }
            }
            if (gameModeOwned || prefs.safeBoolean(K_GAME_MODE_OWNED, false)) {
                val original = snap.gameMode
                val written = gameModeWritten ?: prefs.safeNullableString(K_GAME_MODE_WRITTEN)
                val current = shell.readGameMode(gameModePackage)
                if (written != null && current == written) {
                    original?.takeIf { it in setOf("standard", "performance", "battery", "custom") }
                        ?.let { shell.setGameMode(gameModePackage, it) }
                }
            }
            if (gameOverlayOwned || prefs.safeBoolean(K_GAME_OVERLAY_OWNED, false)) {
                val current = shell.readGameOverlayConfig(gameModePackage)
                val written = gameOverlayWritten ?: prefs.safeNullableString(K_GAME_OVERLAY_WRITTEN)
                if (current == written) {
                    shell.restoreGameOverlayConfig(gameModePackage, snap.gameOverlayConfig)
                }
            }
            if (frameGuardOwned || prefs.safeBoolean(K_FRAME_OWNED, false)) {
                val current = shell.readGameDefaultFrameRateGuard()
                if (current.equals("true", ignoreCase = true)) {
                    shell.setGameDefaultFrameRateGuard(snap.gameDefaultFrameGuard.equals("true", ignoreCase = true))
                }
            }
            if (forcedWifiShellOwned || prefs.safeBoolean(K_WIFI_SHELL_OWNED, false)) {
                shell.forceWifiLowLatency(false)
            }
        }

        if (prefs.safeBoolean(K_ROTATION_OWNED, false) && shellReady) {
            val originalUser = prefs.safeString(K_ROTATION_USER, NULL)?.takeUnless { it == NULL }
            val originalAuto = prefs.safeString(K_ROTATION_AUTO, NULL)?.takeUnless { it == NULL }
            val writtenUser = prefs.safeString(K_ROTATION_WRITTEN_USER, NULL)?.takeUnless { it == NULL }
            val writtenAuto = prefs.safeString(K_ROTATION_WRITTEN_AUTO, NULL)?.takeUnless { it == NULL }
            val currentUser = shell.readSetting("system", "user_rotation")
            val currentAuto = shell.readSetting("system", "accelerometer_rotation")
            // Do not overwrite a user/OEM rotation change made after Game Nuke last wrote it.
            if (writtenUser == null || currentUser == writtenUser) {
                if (originalUser != null && NUMERIC.matches(originalUser)) shell.writeNumericSetting("system", "user_rotation", originalUser)
            }
            if (writtenAuto == null || currentAuto == writtenAuto) {
                if (originalAuto != null && NUMERIC.matches(originalAuto)) shell.writeNumericSetting("system", "accelerometer_rotation", originalAuto)
            }
        }
        restoreDndIfOwned()
        wifiLock.release()
        clearPersistedSnapshot()
        snapshot = null
        refreshPeakOwned = false
        refreshMinOwned = false
        powerSaverOwned = false
        powerSaverWritten = null
        dataSaverOwned = false
        dataSaverWritten = null
        frameGuardOwned = false
        gameModeOwned = false
        gameModeWritten = null
        forcedWifiShellOwned = false
        gameOverlayOwned = false
        gameOverlayWritten = null
        maxRefreshWritten = null
        minRefreshWritten = null

        if (clearState) {
            val metrics = readMetrics()
            _state.value = _state.value.copy(
                phase = Phase.IDLE,
                currentHz = metrics.currentHz,
                maxHz = metrics.maxHz,
                thermalStatus = metrics.thermalStatus,
                thermalHeadroom = metrics.thermalHeadroom,
                ramAvailableMb = metrics.ramAvailableMb,
                ramTotalMb = metrics.ramTotalMb,
                wifiLockHeld = false,
                networkBoostActive = false,
                gameModePerformance = if (shell.connected()) shell.readGameMode(targetPackage)?.let { it == "performance" } else null,
                batterySaverActive = if (shell.connected()) shell.readSetting("global", "low_power")?.let { it == "1" } ?: readLocalBatterySaver() else readLocalBatterySaver(),
                dataSaverActive = if (shell.connected()) shell.readDataSaverEnabled() ?: readLocalDataSaver() else readLocalDataSaver(),
                rotationLocked = if (shell.connected()) shell.readSetting("system", "accelerometer_rotation")?.let { it == "0" } else null,
                thermalGuarded = false,
                dndAccess = hasDndAccess(),
                dndActive = isDndActive(),
                gpuReliefActive = false,
                appliedFeatures = emptySet(),
                busyAction = null,
                message = "SESSION RESTORED",
            )
        }
    }

    private fun recoverStaleSnapshotIfPossible() {
        dndOwned = prefs.safeBoolean(K_DND_OWNED, false)
        dndOriginalFilter = if (prefs.contains(K_DND_FILTER)) {
            prefs.safeInt(K_DND_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
        } else null

        // Game Focus does not depend on ADB. Restore it first after process death so a lost ADB
        // connection can never strand the user's phone in a Game Nuke-owned DND state.
        if (dndOwned) restoreDndIfOwned()

        if (!prefs.safeBoolean(K_ACTIVE, false)) return
        if (!shell.connected()) return
        snapshot = loadPersistedSnapshot()
        refreshPeakOwned = prefs.safeBoolean(K_PEAK_OWNED, false)
        refreshMinOwned = prefs.safeBoolean(K_MIN_OWNED, false)
        powerSaverOwned = prefs.safeBoolean(K_POWER_OWNED, false)
        powerSaverWritten = prefs.safeNullableString(K_POWER_WRITTEN)
        dataSaverOwned = prefs.safeBoolean(K_DATA_SAVER_OWNED, false)
        dataSaverWritten = prefs.takeIf { it.contains(K_DATA_SAVER_WRITTEN) }?.safeBoolean(K_DATA_SAVER_WRITTEN, false)
        frameGuardOwned = prefs.safeBoolean(K_FRAME_OWNED, false)
        gameModeOwned = prefs.safeBoolean(K_GAME_MODE_OWNED, false)
        gameModeWritten = prefs.safeNullableString(K_GAME_MODE_WRITTEN)
        forcedWifiShellOwned = prefs.safeBoolean(K_WIFI_SHELL_OWNED, false)
        gameOverlayOwned = prefs.safeBoolean(K_GAME_OVERLAY_OWNED, false)
        gameOverlayWritten = prefs.safeNullableString(K_GAME_OVERLAY_WRITTEN)
        maxRefreshWritten = prefs.safeNullableString(K_PEAK_WRITTEN)
        minRefreshWritten = prefs.safeNullableString(K_MIN_WRITTEN)
        val persistedTarget = prefs.safeNullableString(K_TARGET)?.takeIf { it.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) }
            ?: targetPackage
        restoreInternal(clearState = false, gameModePackage = persistedTarget)
    }

    private fun persistSnapshot(s: SessionSnapshot) {
        prefs.edit()
            .putBoolean(K_ACTIVE, true)
            .putString(K_TARGET, targetPackage)
            .putString(K_PEAK, s.peakRefresh ?: NULL)
            .putString(K_MIN, s.minRefresh ?: NULL)
            .putString(K_LOW_POWER, s.lowPower ?: NULL)
            .putString(K_FRAME, s.gameDefaultFrameGuard ?: NULL)
            .putString(K_GAME_MODE, s.gameMode ?: NULL)
            .putString(K_GAME_OVERLAY, s.gameOverlayConfig ?: NULL)
            .putString(K_DATA_SAVER, s.dataSaver?.toString() ?: NULL)
            .apply()
        persistOwnedFlags()
    }

    private fun persistOwnedFlags() {
        val editor = prefs.edit()
            .putBoolean(K_PEAK_OWNED, refreshPeakOwned)
            .putBoolean(K_MIN_OWNED, refreshMinOwned)
            .putBoolean(K_POWER_OWNED, powerSaverOwned)
            .putString(K_POWER_WRITTEN, powerSaverWritten)
            .putBoolean(K_DATA_SAVER_OWNED, dataSaverOwned)
            .putBoolean(K_FRAME_OWNED, frameGuardOwned)
            .putBoolean(K_GAME_MODE_OWNED, gameModeOwned)
            .putString(K_GAME_MODE_WRITTEN, gameModeWritten)
            .putBoolean(K_WIFI_SHELL_OWNED, forcedWifiShellOwned)
            .putBoolean(K_GAME_OVERLAY_OWNED, gameOverlayOwned)
            .putString(K_GAME_OVERLAY_WRITTEN, gameOverlayWritten)
            .putString(K_PEAK_WRITTEN, maxRefreshWritten)
            .putString(K_MIN_WRITTEN, minRefreshWritten)
        dataSaverWritten?.let { editor.putBoolean(K_DATA_SAVER_WRITTEN, it) }
            ?: editor.remove(K_DATA_SAVER_WRITTEN)
        editor.apply()
    }

    private fun loadPersistedSnapshot(): SessionSnapshot? {
        if (!prefs.safeBoolean(K_ACTIVE, false)) return null
        fun read(key: String): String? = prefs.safeString(key, NULL)?.takeUnless { it == NULL }
        return SessionSnapshot(
            read(K_PEAK), read(K_MIN), read(K_LOW_POWER), read(K_FRAME), read(K_GAME_MODE), read(K_GAME_OVERLAY),
            read(K_DATA_SAVER)?.toBooleanStrictOrNull(),
        )
    }

    private fun clearPersistedSnapshot() {
        prefs.edit()
            .remove(K_ACTIVE).remove(K_TARGET).remove(K_PEAK).remove(K_MIN).remove(K_LOW_POWER).remove(K_FRAME).remove(K_GAME_MODE).remove(K_GAME_OVERLAY).remove(K_DATA_SAVER)
            .remove(K_PEAK_OWNED).remove(K_MIN_OWNED).remove(K_POWER_OWNED).remove(K_POWER_WRITTEN)
            .remove(K_DATA_SAVER_OWNED).remove(K_DATA_SAVER_WRITTEN).remove(K_FRAME_OWNED).remove(K_GAME_MODE_OWNED).remove(K_GAME_MODE_WRITTEN)
            .remove(K_WIFI_SHELL_OWNED).remove(K_GAME_OVERLAY_OWNED).remove(K_GAME_OVERLAY_WRITTEN)
            .remove(K_PEAK_WRITTEN).remove(K_MIN_WRITTEN)
            .remove(K_DND_OWNED).remove(K_DND_FILTER)
            .remove(K_ROTATION_OWNED).remove(K_ROTATION_AUTO).remove(K_ROTATION_USER)
            .remove(K_ROTATION_WRITTEN_AUTO).remove(K_ROTATION_WRITTEN_USER)
            .apply()
    }

    private fun restoreSettingIfStillOwned(namespace: String, key: String, written: String?, original: String?) {
        if (written == null) return
        val current = shell.readSetting(namespace, key)
        if (!numericClose(current, written)) return // user/OEM changed it after us; do not clobber.
        restoreSingleSetting(namespace, key, original)
    }

    private fun restoreSingleSetting(namespace: String, key: String, original: String?) {
        if (original == null) shell.deleteSetting(namespace, key)
        else if (NUMERIC.matches(original)) shell.writeNumericSetting(namespace, key, original)
    }

    private fun numericClose(a: String?, b: String?): Boolean {
        val aa = a?.toFloatOrNull() ?: return false
        val bb = b?.toFloatOrNull() ?: return false
        return kotlin.math.abs(aa - bb) < 0.6f
    }

    private fun hasDndAccess(): Boolean = runCatching {
        notificationManager?.isNotificationPolicyAccessGranted == true
    }.getOrDefault(false)

    private fun isDndActive(): Boolean = runCatching {
        val filter = notificationManager?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        filter != NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrDefault(false)

    private fun restoreDndIfOwned() {
        if (!dndOwned && !prefs.safeBoolean(K_DND_OWNED, false)) return
        val manager = notificationManager
        val original = dndOriginalFilter
            ?: if (prefs.contains(K_DND_FILTER)) prefs.safeInt(K_DND_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL) else null
        if (manager != null && manager.isNotificationPolicyAccessGranted && original != null) {
            val current = runCatching { manager.currentInterruptionFilter }.getOrNull()
            if (Build.VERSION.SDK_INT >= 35) {
                // targetSdk 35+ maps setInterruptionFilter() to an app-owned implicit Zen rule.
                // ALL deactivates Game Nuke's own rule without turning off a user-owned global DND.
                runCatching { manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
            } else if (current == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                // Legacy Android still changes global DND. Restore only while our exact state remains,
                // so a user/OEM change made during the game is never overwritten.
                runCatching { manager.setInterruptionFilter(original) }
            }
        }
        dndOwned = false
        dndOriginalFilter = null
        prefs.edit().remove(K_DND_OWNED).remove(K_DND_FILTER).apply()
    }

    private fun updateBusy(action: String) {
        _state.value = _state.value.copy(busyAction = action, message = "$action IN PROGRESS")
    }

    private fun clearBusy(message: String) {
        _state.value = _state.value.copy(busyAction = null, message = message)
    }

    private fun setMessage(message: String, phase: Phase? = null) {
        _state.value = _state.value.copy(message = message, phase = phase ?: _state.value.phase, busyAction = null)
    }

    private data class Metrics(
        val currentHz: Int,
        val maxHz: Int,
        val thermalStatus: Int,
        val thermalHeadroom: Float?,
        val ramAvailableMb: Long,
        val ramTotalMb: Long,
        val storageFreeGb: Float,
        val storageTotalGb: Float,
    )

    private fun readMetrics(): Metrics {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        val display = wm?.defaultDisplay
        val currentHz = runCatching { display?.refreshRate?.roundToInt() ?: 0 }.getOrDefault(0)
        val maxHz = runCatching {
            display?.supportedModes?.maxOfOrNull { it.refreshRate }?.roundToInt() ?: currentHz
        }.getOrDefault(currentHz)

        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else PowerManager.THERMAL_STATUS_NONE
        val headroom = readThermalHeadroom(pm)

        val memory = readMemory()
        val storage = readStorageBytes()
        return Metrics(
            currentHz = currentHz,
            maxHz = maxHz,
            thermalStatus = thermalStatus,
            thermalHeadroom = headroom,
            ramAvailableMb = memory.first / MIB,
            ramTotalMb = memory.second / MIB,
            storageFreeGb = bytesToGb(storage.free),
            storageTotalGb = bytesToGb(storage.total),
        )
    }

    /** Android documents a maximum sampling frequency of roughly once per ten seconds. */
    private fun readThermalHeadroom(power: PowerManager?): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || power == null) return null
        return synchronized(thermalSampleLock) {
            val now = SystemClock.elapsedRealtime()
            if (lastThermalHeadroomAt > 0L && now - lastThermalHeadroomAt < 10_500L) {
                return@synchronized cachedThermalHeadroom
            }
            lastThermalHeadroomAt = now
            cachedThermalHeadroom = runCatching { power.getThermalHeadroom(10) }
                .getOrNull()
                ?.takeIf { it.isFinite() && it >= 0f }
            cachedThermalHeadroom
        }
    }

    private data class MemoryStatus(
        val available: Long,
        val total: Long,
        val threshold: Long,
        val lowMemory: Boolean,
    )

    private fun readMemoryStatus(): MemoryStatus = runCatching {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        MemoryStatus(info.availMem, info.totalMem, info.threshold, info.lowMemory)
    }.getOrDefault(MemoryStatus(0L, 0L, 0L, false))

    private fun readMemory(): Pair<Long, Long> {
        val status = readMemoryStatus()
        return status.available to status.total
    }

    private data class StorageBytes(val free: Long, val total: Long)

    private fun readStorageBytes(): StorageBytes = runCatching {
        val stat = StatFs(appContext.filesDir.absolutePath)
        StorageBytes(stat.availableBytes, stat.totalBytes)
    }.getOrDefault(StorageBytes(0L, 0L))

    private fun cacheTargetFreeBytes(currentFree: Long, total: Long): Long {
        if (total <= 0L || currentFree <= 0L) return 0L
        // pm trim-caches accepts DESIRED FREE SPACE, not bytes-to-delete. A manual sweep asks
        // PackageManager for modest additional headroom (roughly 2% of /data, 512MiB..2GiB).
        // This is deliberately bounded because deleting useful caches can make subsequent launches slower.
        val additional = ((total * 2L) / 100L).coerceIn(512L * MIB, 2L * GIB)
        return (currentFree + additional).coerceAtMost(total - 512L * MIB)
    }

    private fun bytesToGb(bytes: Long): Float = if (bytes <= 0L) 0f else bytes.toFloat() / GIB.toFloat()

    private fun resolveGameLabel(): String = runCatching {
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(targetPackage, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getApplicationInfo(targetPackage, 0)
        }
        pm.getApplicationLabel(info).toString().ifBlank { targetPackage.substringAfterLast('.') }
    }.getOrDefault(targetPackage.substringAfterLast('.').ifBlank { "GAME" })

    private fun packageLastUpdateTime(): Long = runCatching {
        val pm = appContext.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(targetPackage, PackageManager.PackageInfoFlags.of(0)).lastUpdateTime
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(targetPackage, 0).lastUpdateTime
        }
    }.getOrDefault(-1L)

    private data class GameModeInfo(val current: String, val available: Set<String>)

    private fun parseGameModes(result: NukeCommandResult): GameModeInfo? {
        if (!result.isSuccess) return null
        val current = Regex("current mode:\\s*([a-zA-Z]+)", RegexOption.IGNORE_CASE)
            .find(result.output)?.groupValues?.getOrNull(1)?.lowercase() ?: return null
        val availableText = Regex("available game modes:\\s*\\[([^]]*)]", RegexOption.IGNORE_CASE)
            .find(result.output)?.groupValues?.getOrNull(1).orEmpty()
        val available = availableText.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        return GameModeInfo(current, available)
    }

    fun openInternetPanel() {
        openSystemSettings(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
    }

    fun openDeveloperOptions() {
        openSystemSettings(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    }

    private fun parseFrameTimestats(output: String): Pair<Float, Long>? {
        // SurfaceFlinger emits one block per rendered layer. Games often expose several layers;
        // select the target-package layer with the largest frame count to avoid HUD/splash layers.
        val section = Regex("(?ms)layerName\\s*=\\s*(.+?)\\n(.*?)(?=\\nlayerName\\s*=|\\z)")
        val fpsRegex = Regex("averageFPS\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)")
        val framesRegex = Regex("totalFrames\\s*=\\s*([0-9]+)")
        return section.findAll(output).mapNotNull { match ->
            val name = match.groupValues[1]
            if (!name.contains(targetPackage, ignoreCase = true)) return@mapNotNull null
            val body = match.groupValues[2]
            val fps = fpsRegex.find(body)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
            val frames = framesRegex.find(body)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            if (!fps.isFinite() || fps <= 0f) null else Triple(fps, frames, name)
        }.maxByOrNull { it.second }?.let { it.first to it.second }
    }

    private fun String.cleanShellMessage(): String = lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?.take(96)
        ?: "UNSUPPORTED"

    private companion object {
        const val PREFS = "NukePerformanceEngine"
        const val NULL = "__NULL__"
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
        const val THERMAL_RELEASE_AT = 0.82f
        const val THERMAL_RESTORE_AT = 0.62f
        val NUMERIC = Regex("-?\\d+(?:\\.\\d+)?")

        const val K_ACTIVE = "session_active"
        const val K_TARGET = "session_target"
        const val K_PEAK = "snapshot_peak"
        const val K_MIN = "snapshot_min"
        const val K_LOW_POWER = "snapshot_low_power"
        const val K_FRAME = "snapshot_frame_guard"
        const val K_GAME_MODE = "snapshot_game_mode"
        const val K_GAME_OVERLAY = "snapshot_game_overlay"
        const val K_DATA_SAVER = "snapshot_data_saver"
        const val K_PEAK_OWNED = "owned_peak"
        const val K_MIN_OWNED = "owned_min"
        const val K_POWER_OWNED = "owned_power"
        const val K_POWER_WRITTEN = "written_power"
        const val K_DATA_SAVER_OWNED = "owned_data_saver"
        const val K_DATA_SAVER_WRITTEN = "written_data_saver"
        const val K_FRAME_OWNED = "owned_frame_guard"
        const val K_GAME_MODE_OWNED = "owned_game_mode"
        const val K_GAME_MODE_WRITTEN = "written_game_mode"
        const val K_WIFI_SHELL_OWNED = "owned_wifi_shell"
        const val K_GAME_OVERLAY_OWNED = "owned_game_overlay"
        const val K_GAME_OVERLAY_WRITTEN = "written_game_overlay"
        const val K_PEAK_WRITTEN = "written_peak"
        const val K_MIN_WRITTEN = "written_min"
        const val K_DND_OWNED = "owned_dnd"
        const val K_DND_FILTER = "snapshot_dnd_filter"
        const val K_ROTATION_OWNED = "owned_rotation_lock"
        const val K_ROTATION_AUTO = "snapshot_rotation_auto"
        const val K_ROTATION_USER = "snapshot_rotation_user"
        const val K_ROTATION_WRITTEN_AUTO = "written_rotation_auto"
        const val K_ROTATION_WRITTEN_USER = "written_rotation_user"
    }
}
