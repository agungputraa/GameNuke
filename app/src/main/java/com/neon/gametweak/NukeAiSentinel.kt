package com.neon.gametweak

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NukeAiSentinel — Intelligent Autonomous AI Game Sentinel & Thermal Stability Guardian.
 *
 * Realtime background supervisor that:
 * 1. Autonomously monitors thermal status and battery temperature from hardware sensors.
 * 2. Never aggressively force-stops user apps; dynamically protects active music, calls, games, and screen recorders.
 * 3. Intelligently suppresses non-essential OEM telemetry trackers in background.
 * 4. Prioritizes active games with high CPU/IO priority, OOM immunity, and kernel cpuset isolation.
 * 5. Applies gentle memory compaction and cache trimming without causing game micro-stutter.
 * 6. Operates on a smart, calm cadence (low CPU overhead < 0.1%) to keep devices cool, stable, and responsive.
 */
object NukeAiSentinel {

    private const val TAG = "NukeAiSentinel"
    private const val PREFS_NAME = "NukeAiSentinelPrefs"
    private const val KEY_ENABLED = "ai_sentinel_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var lastSweepTimestamp = 0L

    // ─── Protected System Whitelist ─────────────────────────────────────────

    private val SYSTEM_WHITELIST = setOf(
        "system_server",
        "surfaceflinger",
        "adbd",
        "init",
        "zygote",
        "zygote64",
        "webview_zygote",
        "com.google.android.webview",
        "com.android.webview",
        "com.google.android.trichromelibrary",
        "org.chromium",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.process.gapps",
        "com.android.vending",
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings",
        "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin",
        "com.miui.home",
        "com.mi.android.globallauncher",
        "moe.shizuku.privileged.api",
        "rikka.shizuku",
        "com.iadb"
    )

    // ─── Protected Media & Music Streaming Apps ─────────────────────────────

    private val PROTECTED_MEDIA_PLAYERS = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.apple.android.music",
        "com.soundcloud.android",
        "deezer.android.app",
        "com.aspiro.tidal",
        "com.netease.cloudmusic",
        "com.shazam.android",
        "com.amazon.mp3",
        "com.jio.media.jiobeats",
        "com.anghami",
        "com.audiomack",
        "com.pandora.android",
        "tunein.player",
        "org.videolan.vlc",
        "com.maxmpz.audioplayer",
        "in.krosbits.musicolet",
        "com.foobar2000.foobar2000",
        "com.aimp.player"
    )

    // ─── Protected Voice & Communication Apps ───────────────────────────────

    private val PROTECTED_COMMUNICATION_APPS = setOf(
        "com.discord",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.plus",
        "org.thunderdog.challegram",
        "com.tencent.mm",
        "com.google.android.apps.tachyon",
        "com.google.android.talk",
        "us.zoom.videomeetings",
        "com.skype.raider",
        "com.microsoft.teams",
        "com.viber.voip",
        "com.linecorp.line"
    )

    // ─── OEM Telemetry & Non-Essential Background Trackers ──────────────────

    private val SYSTEM_TELEMETRY_TRACKERS = listOf(
        "com.quantaengine.core",
        "com.miui.analytics",
        "com.miui.msa.global",
        "com.miui.daemon",
        "com.xiaomi.mipicks",
        "com.xiaomi.mipicks:guard",
        "com.mi.appfinder",
        "com.mi.appfinder:BranchSearch",
        "com.mi.globalminusscreen",
        "com.facebook.appmanager",
        "com.facebook.services",
        "com.facebook.system",
        "com.samsung.android.rubin.app",
        "com.samsung.android.bixby.agent",
        "com.samsung.android.app.spage",
        "com.transsion.palmswitch",
        "com.transsion.carlcare",
        "com.heytap.mcs",
        "com.heytap.market",
        "com.vivo.upslide"
    )

    // ─── Candidates for Gentle Background Resource Check ────────────────────

    private val COMMON_BACKGROUND_HOGS = listOf(
        "mypoin.indomaret.android",
        "com.facebook.katana",
        "com.facebook.orca",
        "com.facebook.stella",
        "com.ss.android.ugc.trill",
        "com.lazada.android",
        "com.shopee.id",
        "com.tokopedia.tkpd",
        "com.storymatrix.drama",
        "com.worldance.drama",
        "com.quadrastudios.promax",
        "com.haibison.apksigner",
        "com.lemon.lvoverseas",
        "com.instagram.android",
        "com.transsion.hilauncher",
        "com.vivo.browser"
    )

    // ─── Observable State Flow Telemetry ────────────────────────────────────

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _isSweeping = MutableStateFlow(false)
    val isSweeping: StateFlow<Boolean> = _isSweeping.asStateFlow()

    private val _zombieKilledCount = MutableStateFlow(0)
    val zombieKilledCount: StateFlow<Int> = _zombieKilledCount.asStateFlow()

    private val _reclaimedRamMb = MutableStateFlow(0L)
    val reclaimedRamMb: StateFlow<Long> = _reclaimedRamMb.asStateFlow()

    private val _lastActionText = MutableStateFlow("AI Sentinel • Ready")
    val lastActionText: StateFlow<String> = _lastActionText.asStateFlow()

    private val _activeGame = MutableStateFlow("")
    val activeGame: StateFlow<String> = _activeGame.asStateFlow()

    // ─── Hardware Thermal & Cooling Telemetry ──────────────────────────────

    private val _batteryTemp = MutableStateFlow(0f)
    val batteryTemp: StateFlow<Float> = _batteryTemp.asStateFlow()

    private val _thermalStatusLevel = MutableStateFlow(0)
    val thermalStatusLevel: StateFlow<Int> = _thermalStatusLevel.asStateFlow()

    private val _isCoolingActive = MutableStateFlow(false)
    val isCoolingActive: StateFlow<Boolean> = _isCoolingActive.asStateFlow()

    private val _coolingEventsCount = MutableStateFlow(0)
    val coolingEventsCount: StateFlow<Int> = _coolingEventsCount.asStateFlow()

    // ─── Real Hardware Temperature Sampling ─────────────────────────────────

    fun getBatteryTemperature(context: Context): Float {
        return runCatching {
            val intent = androidx.core.content.ContextCompat.registerReceiver(
                context,
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            if (rawTemp > 0) rawTemp / 10f else 0f
        }.getOrDefault(0f)
    }

    fun getThermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.currentThermalStatus ?: 0
        }
        return 0
    }

    // ─── Initialization & Lifecycle ────────────────────────────────────────

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAuto = prefs.getBoolean(KEY_ENABLED, true)
        _enabled.value = isAuto
        if (isAuto) {
            start(context)
        }
    }

    fun setEnabled(context: Context, isEnabled: Boolean) {
        _enabled.value = isEnabled
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, isEnabled).apply()

        if (isEnabled) {
            start(context)
        } else {
            stop()
        }
    }

    fun toggle(context: Context) {
        setEnabled(context, !_enabled.value)
    }

    @Synchronized
    fun start(context: Context) {
        if (monitorJob?.isActive == true) return
        val appContext = context.applicationContext

        monitorJob = scope.launch {
            Log.d(TAG, "Smart AI Sentinel autonomous supervisor initialized")
            _lastActionText.value = "AI Sentinel • Active stability guardian"

            while (isActive) {
                try {
                    val currentGame = NukeRuntimeState.state.value.activePackage.orEmpty()
                    _activeGame.value = currentGame
                    val hasGame = currentGame.isNotBlank()

                    val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }

                    val totalMb = (memInfo.totalMem / (1024 * 1024)).coerceAtLeast(1L)
                    val availMb = memInfo.availMem / (1024 * 1024)
                    val usedPercent = (((totalMb - availMb).toFloat() / totalMb) * 100).toInt()

                    // Hardware temperature & thermal status
                    val bTemp = getBatteryTemperature(appContext)
                    val thermalStat = getThermalStatus(appContext)
                    _batteryTemp.value = bTemp
                    _thermalStatusLevel.value = thermalStat

                    val now = System.currentTimeMillis()

                    // Smart Adaptive Thermal Evaluation:
                    // 1. Critical Thermal Alert: >= 43.0°C or PowerManager thermal status >= SEVERE (3)
                    val isThermalCritical = (bTemp >= 43.0f || thermalStat >= 3)
                    // 2. Warm Threshold: >= 41.0°C (requires gentle heat mitigation)
                    val isThermalWarm = (bTemp >= 41.0f || thermalStat >= 2)

                    // 3. Memory Pressure: extreme low memory (< 600MB free or > 90% used or system lowMemory flag)
                    val isSevereMemoryPressure = (usedPercent >= 90 || availMb < 600L || memInfo.lowMemory)
                    // Moderate memory pressure: (< 850MB free or > 84% used)
                    val isModerateMemoryPressure = (usedPercent >= 84 || availMb < 850L)

                    // Cooldown limits to prevent micro-stutter from frequent sweeps:
                    // Critical thermal: minimum 60s cooldown
                    // Memory pressure / game maintenance: minimum 120s cooldown
                    val cooldownLimit = if (isThermalCritical) 60_000L else 120_000L
                    val cooldownPassed = (now - lastSweepTimestamp) >= cooldownLimit

                    // Periodic game stabilization sweep: every 180s, but ONLY if RAM is actually tight or device is warm
                    val isPeriodicGameSweep = hasGame && (now - lastSweepTimestamp) >= 180_000L && (isModerateMemoryPressure || isThermalWarm)

                    val isAdActive = NukeAdManager.isShowingFullScreen
                    val shouldClean = (isThermalCritical || isSevereMemoryPressure || isPeriodicGameSweep) && cooldownPassed && !isAdActive

                    if (shouldClean && _enabled.value) {
                        performAutonomousSweep(
                            context = appContext,
                            gamePkg = currentGame,
                            memBefore = memInfo,
                            isCoolingTrigger = isThermalCritical || isThermalWarm,
                            currentTemp = bTemp
                        )
                    } else if (hasGame && (now - lastSweepTimestamp) >= 45_000L) {
                        // Keep game priority refreshed softly without running a heavy sweep
                        refreshGamePriorityOnly(appContext, currentGame, isCoolingTrigger = isThermalWarm, currentTemp = bTemp)
                    }

                    // Reset cooling flag once temperature normalizes (< 40.0°C and thermal status < 2)
                    if (bTemp > 0f && bTemp < 40.0f && thermalStat < 2) {
                        _isCoolingActive.value = false
                    }

                    // Dynamic polling interval:
                    // 15s during critical thermal alert
                    // 30s during active gaming (ultra-low CPU overhead)
                    // 60s when idle
                    val interval = when {
                        isThermalCritical -> 15_000L
                        hasGame -> 30_000L
                        else -> 60_000L
                    }
                    delay(interval)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in AI Sentinel loop: ${e.message}")
                    delay(30_000L)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        _isSweeping.value = false
        _isCoolingActive.value = false
        _lastActionText.value = "AI Sentinel • Paused"
        Log.d(TAG, "AI Game Sentinel paused")
    }

    // ─── Autonomous Execution Engine ───────────────────────────────────────

    fun forceSweepNow(context: Context) {
        triggerManualSweep(context, null)
    }

    fun triggerManualSweep(context: Context, onComplete: ((killed: Int, freedMb: Long) -> Unit)? = null) {
        scope.launch {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val currentGame = NukeRuntimeState.state.value.activePackage.orEmpty()
            val bTemp = getBatteryTemperature(context)
            val (killed, freed) = performAutonomousSweep(
                context = context.applicationContext,
                gamePkg = currentGame,
                memBefore = memInfo,
                isCoolingTrigger = true,
                currentTemp = bTemp
            )
            withContext(Dispatchers.Main) {
                onComplete?.invoke(killed, freed)
            }
        }
    }

    private suspend fun refreshGamePriorityOnly(
        context: Context,
        gamePkg: String,
        isCoolingTrigger: Boolean,
        currentTemp: Float
    ) {
        if (!isPackageName(gamePkg)) return
        runCatching {
            val adb = AdbManager.getInstance(context)
            val script = buildGamePriorityScript(
                gamePkg = gamePkg,
                enablePerformanceGovernor = !isCoolingTrigger && currentTemp < 41.0f
            )
            if (script.isNotBlank()) {
                executePrivileged(adb, script, 3_000L, 4_096)
            }
        }
    }

    private suspend fun performAutonomousSweep(
        context: Context,
        gamePkg: String,
        memBefore: ActivityManager.MemoryInfo,
        isCoolingTrigger: Boolean = false,
        currentTemp: Float = 0f,
    ): Pair<Int, Long> {
        if (_isSweeping.value) return Pair(0, 0L)
        _isSweeping.value = true
        lastSweepTimestamp = System.currentTimeMillis()
        if (isCoolingTrigger) {
            _isCoolingActive.value = true
            _coolingEventsCount.value++
        }

        val myPkg = context.packageName
        val safeGamePkg = gamePkg.takeIf(::isPackageName).orEmpty()
        val availBeforeMb = memBefore.availMem / (1024 * 1024)
        _lastActionText.value = if (isCoolingTrigger) {
            "Smart Cooling • Optimizing thermal headroom (${String.format(Locale.US, "%.1f", currentTemp)}°C)…"
        } else {
            "AI Sentinel • Stabilizing background memory…"
        }

        if (NukeAdManager.isShowingFullScreen) {
            _isSweeping.value = false
            return Pair(0, 0L)
        }

        try {
            val adb = AdbManager.getInstance(context)
            val privileged = adb.isConnected() || NukeConnectionManager.isConnected()
            val focusedPkg = runCatching {
                ActiveGameDetector(context, adb).detectForegroundPackage()
            }.getOrNull().orEmpty().takeIf(::isPackageName).orEmpty()

            val protectedPackages = dynamicProtectedPackages(context, myPkg, safeGamePkg, focusedPkg)
            var actionCount = 0

            if (privileged) {
                val script = StringBuilder()

                // 1. Active Game High-Priority & Cpuset Isolation
                if (safeGamePkg.isNotBlank()) {
                    script.append(
                        buildGamePriorityScript(
                            gamePkg = safeGamePkg,
                            enablePerformanceGovernor = !isCoolingTrigger && currentTemp < 41.0f
                        )
                    )
                    script.append('\n')
                }

                // 2. Gentle memory compaction and cache trim (safe, non-destructive)
                script.append("pm trim-caches 256M 2>/dev/null\n")
                script.append("am compact system 2>/dev/null\n")

                // 3. Intelligently trim non-essential OEM telemetry trackers in background
                val telemetryTargets = SYSTEM_TELEMETRY_TRACKERS
                    .map { it.substringBefore(':') }
                    .filterNot { isProtectedPackage(it, protectedPackages) }
                    .distinct()

                telemetryTargets.take(8).forEach { pkg ->
                    script.append("cmd activity send-trim-memory $pkg RUNNING_CRITICAL 2>/dev/null\n")
                    script.append("am kill $pkg 2>/dev/null\n")
                    actionCount++
                }

                // 4. Sample background CPU & RAM hogs using lightweight top & ps inspection
                val topCmd = "top -b -n 1 -o PID,NAME,%CPU -m 25"
                val rssCmd = "ps -A -o RSS,NAME"
                val topOutput = executePrivileged(adb, topCmd, 3_500L, 10_000)
                val rssOutput = executePrivileged(adb, rssCmd, 3_500L, 12_000)

                val cpuHogs = linkedMapOf<String, Float>()
                topOutput.lineSequence().forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 3) return@forEach
                    val cpu = parts.last().removeSuffix("%").toFloatOrNull() ?: return@forEach
                    val rawName = parts.getOrNull(parts.size - 2).orEmpty()
                    val pkg = rawName.substringBefore(':')
                    if (!isPackageName(pkg) || isProtectedPackage(pkg, protectedPackages)) return@forEach
                    val knownHog = COMMON_BACKGROUND_HOGS.any { it.substringBefore(':') == pkg }
                    // Only target genuine background CPU hogs (>= 15% CPU, or known wake-hogs >= 8%)
                    if (cpu >= 15f || (knownHog && cpu >= 8f)) {
                        cpuHogs[pkg] = maxOf(cpuHogs[pkg] ?: 0f, cpu)
                    }
                }

                val ramHogs = linkedMapOf<String, Long>()
                rssOutput.lineSequence().forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size != 2) return@forEach
                    val rssKb = parts[0].toLongOrNull() ?: return@forEach
                    val pkg = parts[1].substringBefore(':').substringBefore(' ').trim()
                    if (!isPackageName(pkg) || isProtectedPackage(pkg, protectedPackages)) return@forEach
                    // Only target genuine RAM hogs (>= 450MB RSS in background)
                    if (rssKb >= 450_000L) {
                        ramHogs[pkg] = maxOf(ramHogs[pkg] ?: 0L, rssKb)
                    }
                }

                // Never force-stop user apps; gently trim and kill background instances
                val rogueHogs = (cpuHogs.keys + ramHogs.keys)
                    .filterNot { isProtectedPackage(it, protectedPackages) }
                    .distinct()
                    .take(6)

                rogueHogs.forEach { pkg ->
                    script.append("cmd activity send-trim-memory $pkg RUNNING_LOW 2>/dev/null\n")
                    script.append("am kill $pkg 2>/dev/null\n")
                    actionCount++
                }

                // 5. Thermal Emergency Cooling Shield (only when temp >= 43.0°C)
                if (isCoolingTrigger && currentTemp >= 43.0f) {
                    script.append("sync 2>/dev/null\n")
                    script.append("[ -w /proc/sys/vm/drop_caches ] && echo 3 > /proc/sys/vm/drop_caches 2>/dev/null\n")
                }

                if (script.isNotBlank()) {
                    executePrivileged(adb, script.toString(), 6_000L, 16_384)
                }
            } else {
                // Non-privileged Android fallback: safe ActivityManager background killing
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val running = runCatching { am?.runningAppProcesses.orEmpty() }.getOrDefault(emptyList())
                val candidates = running
                    .filter { it.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED }
                    .flatMap { it.pkgList?.toList().orEmpty() }
                    .filter(::isPackageName)
                    .filter { pkg -> COMMON_BACKGROUND_HOGS.any { it.substringBefore(':') == pkg } }
                    .filterNot { isProtectedPackage(it, protectedPackages) }
                    .distinct()
                    .take(4)

                candidates.forEach { pkg ->
                    runCatching { am?.killBackgroundProcesses(pkg) }
                    actionCount++
                }
            }

            delay(300L)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memAfter = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val availAfterMb = memAfter.availMem / (1024 * 1024)
            val freedMb = (availAfterMb - availBeforeMb).coerceAtLeast(0L)

            _zombieKilledCount.value += actionCount
            _reclaimedRamMb.value += freedMb

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val tempBadge = if (currentTemp > 0f) " • ${String.format(Locale.US, "%.1f", currentTemp)}°C" else ""

            _lastActionText.value = when {
                isCoolingTrigger && actionCount > 0 ->
                    "Smart Cooling • Optimized $actionCount background tasks • RAM +${freedMb}MB$tempBadge ($timeStr)"
                actionCount > 0 ->
                    "AI Sentinel • Stabilized $actionCount tasks • RAM +${freedMb}MB$tempBadge ($timeStr)"
                else ->
                    "AI Sentinel • System balanced & stable$tempBadge ($timeStr)"
            }

            Log.d(TAG, "Smart sweep completed: actions=$actionCount freedMb=$freedMb temp=${currentTemp}°C")
            return Pair(actionCount, freedMb)
        } catch (error: Exception) {
            Log.e(TAG, "Failed during autonomous sweep", error)
            _lastActionText.value = "AI Sentinel • Active stability guardian"
            return Pair(0, 0L)
        } finally {
            _isSweeping.value = false
        }
    }

    private fun executePrivileged(
        adb: AdbManager,
        command: String,
        timeoutMs: Long,
        maxOutputChars: Int,
    ): String {
        val managed = NukeConnectionManager.executeCommand(command, timeoutMs, maxOutputChars)
        if (managed != null) return managed.output
        return if (adb.isConnected()) {
            adb.executeCommand(command, "/", timeoutMs, maxOutputChars).output
        } else {
            ""
        }
    }

    private fun dynamicProtectedPackages(
        context: Context,
        myPkg: String,
        gamePkg: String,
        focusedPkg: String,
    ): Set<String> {
        val protected = SYSTEM_WHITELIST.toMutableSet()
        protected += myPkg
        if (gamePkg.isNotBlank()) protected += gamePkg
        if (focusedPkg.isNotBlank()) protected += focusedPkg

        // Protect essential daemon and privilege tools
        protected += setOf(
            "com.android.shell",
            "moe.shizuku.privileged.api",
            "rikka.shizuku",
            "com.github.uiautomator",
            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin",
        )

        // Always protect all popular communication and voice chat apps
        protected.addAll(PROTECTED_COMMUNICATION_APPS)

        // 1. Audio / Music Playback Protection:
        // If music is actively playing, protect all media players
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isMusicPlaying = runCatching { audioManager?.isMusicActive == true }.getOrDefault(false)
        if (isMusicPlaying) {
            protected.addAll(PROTECTED_MEDIA_PLAYERS)
        }

        // 2. Active Call & VoIP Protection:
        val isVoiceCallActive = runCatching {
            audioManager?.mode in listOf(
                AudioManager.MODE_IN_CALL,
                AudioManager.MODE_IN_COMMUNICATION,
                AudioManager.MODE_RINGTONE
            )
        }.getOrDefault(false)

        val telecomManager = runCatching { context.getSystemService(TelecomManager::class.java) }.getOrNull()
        val isInCall = runCatching { telecomManager?.isInCall == true }.getOrDefault(false)

        if (isVoiceCallActive || isInCall) {
            protected.addAll(PROTECTED_COMMUNICATION_APPS)
        }

        // 3. Default Launcher & Home App
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(
                homeIntent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()?.takeIf(::isPackageName)?.let(protected::add)

        // 4. Default Keyboard (IME)
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        }.getOrNull()?.takeIf(::isPackageName)?.let(protected::add)

        // 5. Default Phone Dialer
        runCatching {
            telecomManager?.defaultDialerPackage
        }.getOrNull()?.takeIf(::isPackageName)?.let(protected::add)

        return protected
    }

    private fun isProtectedPackage(pkg: String, protected: Set<String>): Boolean {
        if (protected.any { pkg == it || pkg.startsWith("$it:") }) return true
        if (NukeScreenRecordGuardian.isProtected(pkg)) return true
        val activeGame = NukeRuntimeState.state.value.activePackage
        if (!activeGame.isNullOrBlank() && pkg.equals(activeGame, ignoreCase = true)) return true
        if (pkg.equals("com.neon.gametweak", ignoreCase = true)) return true

        return pkg.startsWith("android.") ||
            pkg.startsWith("com.android.systemui") ||
            pkg.startsWith("com.google.android.gms") ||
            pkg.startsWith("vendor.") ||
            pkg.contains("launcher", ignoreCase = true) ||
            pkg.contains("inputmethod", ignoreCase = true) ||
            pkg.contains("keyboard", ignoreCase = true) ||
            pkg.contains("shizuku", ignoreCase = true)
    }

    private fun isPackageName(value: String): Boolean =
        value.length in 3..180 && value.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))

    private fun buildGamePriorityScript(gamePkg: String, enablePerformanceGovernor: Boolean): String {
        if (!isPackageName(gamePkg)) return ""
        val governorFlag = if (enablePerformanceGovernor) "1" else "0"
        val dollar = '$'
        return """
            GAME='$gamePkg'
            # 1. Elevate Game CPU Priority and Protect from Low Memory Killer
            for PID in ${dollar}(pidof "$gamePkg" 2>/dev/null); do
              renice -n -20 -p "${dollar}PID" >/dev/null 2>&1
              ionice -c 1 -n 0 -p "${dollar}PID" >/dev/null 2>&1 || ionice -c 2 -n 0 -p "${dollar}PID" >/dev/null 2>&1
              [ -w "/proc/${dollar}PID/oom_score_adj" ] && echo -1000 > "/proc/${dollar}PID/oom_score_adj" 2>/dev/null
            done

            # 2. Kernel Cpuset Isolation: Pin game to top-app and restrict background to efficiency cores
            if [ -d "/dev/cpuset" ]; then
              if [ -w "/dev/cpuset/top-app/cgroup.procs" ]; then
                for PID in ${dollar}(pidof "$gamePkg" 2>/dev/null); do
                  echo "${dollar}PID" > /dev/cpuset/top-app/cgroup.procs 2>/dev/null
                done
              fi
              # Restrict background processes to little cores (0-3) if writable
              [ -w "/dev/cpuset/background/cpus" ] && echo "0-3" > /dev/cpuset/background/cpus 2>/dev/null
            fi

            # 3. CPU Governor Tweak (Root / Privileged fallback)
            if command -v su >/dev/null 2>&1 && [ "${dollar}(su -c 'id -u' 2>/dev/null | head -n 1)" = "0" ]; then
              for PID in ${dollar}(pidof "$gamePkg" 2>/dev/null); do
                su -c "renice -n -20 -p ${dollar}PID >/dev/null 2>&1; echo -1000 > /proc/${dollar}PID/oom_score_adj 2>/dev/null" >/dev/null 2>&1
              done
              if [ "$governorFlag" = "1" ]; then
                su -c 'for G in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do A="${dollar}{G%/*}/scaling_available_governors"; if [ -w "${dollar}G" ] && [ -r "${dollar}A" ] && grep -qw performance "${dollar}A"; then echo performance > "${dollar}G" 2>/dev/null; fi; done' >/dev/null 2>&1
              fi
            elif [ "${dollar}(id -u 2>/dev/null)" = "0" ] && [ "$governorFlag" = "1" ]; then
              for G in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
                A="${dollar}{G%/*}/scaling_available_governors"
                if [ -w "${dollar}G" ] && [ -r "${dollar}A" ] && grep -qw performance "${dollar}A"; then echo performance > "${dollar}G" 2>/dev/null; fi
              done
            fi
        """.trimIndent()
    }
}

