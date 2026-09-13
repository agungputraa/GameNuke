package com.neon.gametweak

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NukeDynamicSessionRestoreManager
 *
 * Dynamically tracks only the components and hardware settings that are actually modified
 * or toggled during an active floating session.
 *
 * When the floating session ends (either by explicit user close, back to normal, or service stop),
 * ONLY the components that were modified during this session are safely rolled back to their
 * verified baseline state. Unmodified components remain completely untouched.
 */
object NukeDynamicSessionRestoreManager {
    private const val TAG = "NukeDynamicRestore"

    // Component modification trackers
    private val isDpiModified = AtomicBoolean(false)
    private val isVpnModified = AtomicBoolean(false)
    private val isTouchModified = AtomicBoolean(false)
    private val isBrightnessModified = AtomicBoolean(false)
    private val isCpuTurboModified = AtomicBoolean(false)
    private val isGameModeModified = AtomicBoolean(false)
    private val isNetBoostModified = AtomicBoolean(false)
    private val isAudioBoostModified = AtomicBoolean(false)
    private val isFpsLockModified = AtomicBoolean(false)
    private val isDndModified = AtomicBoolean(false)
    private val isGpuTunerModified = AtomicBoolean(false)
    private val isMacroModified = AtomicBoolean(false)

    // Baseline storage (captured before any modification)
    @Volatile private var baselineDensityOverride: Int? = null
    @Volatile private var baselineDensityHadOverride: Boolean = false
    @Volatile private var baselineBrightness: Int = -1
    @Volatile private var baselineBrightnessMode: Int = -1
    @Volatile private var baselinePointerSpeed: Int = 0

    // Dynamic quick setting state store: key -> original value
    private val baselineQuickSettings = ConcurrentHashMap<String, Any>()
    private val modifiedQuickSettings = ConcurrentHashMap.newKeySet<String>()

    /**
     * Called when FloatingBoosterService is created to initialize clean tracking.
     */
    fun onSessionStart(context: Context) {
        resetTracking()
        cacheInitialDensity(context)
        cacheInitialBrightness(context)
        cacheInitialPointerSpeed(context)
        Log.i(TAG, "Dynamic session restore tracking initialized.")
    }

    private fun resetTracking() {
        isDpiModified.set(false)
        isVpnModified.set(false)
        isTouchModified.set(false)
        isBrightnessModified.set(false)
        isCpuTurboModified.set(false)
        isGameModeModified.set(false)
        isNetBoostModified.set(false)
        isAudioBoostModified.set(false)
        isFpsLockModified.set(false)
        isDndModified.set(false)
        isGpuTunerModified.set(false)
        isMacroModified.set(false)
        baselineQuickSettings.clear()
        modifiedQuickSettings.clear()
    }

    // ── Baseline Captures ───────────────────────────────────────────────────

    private fun cacheInitialDensity(context: Context) {
        try {
            val res = executeShellFast("wm density 2>/dev/null", context)
            val output = res ?: ""
            if (output.contains("Override density:", ignoreCase = true)) {
                val density = Regex("Override density:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
                baselineDensityOverride = density
                baselineDensityHadOverride = density != null && density in 120..1000
            } else {
                baselineDensityOverride = null
                baselineDensityHadOverride = false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not pre-cache initial density: ${t.message}")
            baselineDensityOverride = null
            baselineDensityHadOverride = false
        }
    }

    private fun cacheInitialBrightness(context: Context) {
        try {
            baselineBrightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            baselineBrightnessMode = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            )
        } catch (t: Throwable) {
            baselineBrightness = 128
            baselineBrightnessMode = 1
        }
    }

    private fun cacheInitialPointerSpeed(context: Context) {
        try {
            baselinePointerSpeed = Settings.System.getInt(
                context.contentResolver,
                "pointer_speed",
                0
            )
        } catch (_: Throwable) {
            baselinePointerSpeed = 0
        }
    }

    // ── Modification Notifiers ──────────────────────────────────────────────

    fun markDpiModified() {
        isDpiModified.set(true)
        Log.d(TAG, "DPI marked as modified in current session")
    }

    fun markVpnModified() {
        isVpnModified.set(true)
        Log.d(TAG, "VPN Net Engine marked as modified in current session")
    }

    fun markTouchModified() {
        isTouchModified.set(true)
        Log.d(TAG, "Touch Tuning / Listener marked as modified in current session")
    }

    fun markBrightnessModified() {
        isBrightnessModified.set(true)
        Log.d(TAG, "Brightness marked as modified in current session")
    }

    fun markCpuTurboModified() {
        isCpuTurboModified.set(true)
        Log.d(TAG, "CPU Turbo marked as modified in current session")
    }

    fun markGameModeModified() {
        isGameModeModified.set(true)
        Log.d(TAG, "Game Mode marked as modified in current session")
    }

    fun markNetBoostModified() {
        isNetBoostModified.set(true)
        Log.d(TAG, "Packet Boost marked as modified in current session")
    }

    fun markAudioBoostModified() {
        isAudioBoostModified.set(true)
        Log.d(TAG, "Audio Boost marked as modified in current session")
    }

    fun markFpsLockModified() {
        isFpsLockModified.set(true)
        Log.d(TAG, "FPS Lock marked as modified in current session")
    }

    fun markDndModified() {
        isDndModified.set(true)
        Log.d(TAG, "Gaming DND marked as modified in current session")
    }

    fun markGpuTunerModified() {
        isGpuTunerModified.set(true)
        Log.d(TAG, "GPU / Display Tuner marked as modified in current session")
    }

    fun markMacroModified() {
        isMacroModified.set(true)
        Log.d(TAG, "Macro Studio marked as modified in current session")
    }

    fun recordQuickSettingModification(key: String, preValue: Any) {
        baselineQuickSettings.putIfAbsent(key, preValue)
        modifiedQuickSettings.add(key)
        Log.d(TAG, "Quick setting '$key' marked as modified (baseline: $preValue)")
    }

    // ── Restoration Logic ───────────────────────────────────────────────────

    /**
     * Restores ONLY the components that were modified during this session.
     * Guaranteed safe execution with thorough try-catch protection.
     */
    suspend fun restoreAllModified(context: Context) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Executing tailored dynamic restoration for ended session...")

        // 1. DPI / Density restoration
        if (isDpiModified.getAndSet(false)) {
            runCatching {
                Log.i(TAG, "Reverting display density to baseline...")
                if (baselineDensityHadOverride && baselineDensityOverride != null) {
                    executeShell("wm density ${baselineDensityOverride} 2>/dev/null", context)
                } else {
                    executeShell("wm density reset 2>/dev/null", context)
                }
            }.onFailure { Log.w(TAG, "Error reverting DPI", it) }
        }

        // 2. GPU / Resolution / Refresh Rate restoration
        if (isGpuTunerModified.getAndSet(false)) {
            runCatching {
                Log.i(TAG, "Reverting custom resolution and refresh rate to native...")
                executeShell(
                    "wm size reset 2>/dev/null ; wm density reset 2>/dev/null ; " +
                    "settings delete system min_refresh_rate 2>/dev/null ; " +
                    "settings delete system peak_refresh_rate 2>/dev/null ; " +
                    "settings delete system user_refresh_rate 2>/dev/null ; " +
                    "service call SurfaceFlinger 1008 i32 0 2>/dev/null",
                    context
                )
            }.onFailure { Log.w(TAG, "Error reverting GPU tuner overrides", it) }
        }

        // 3. Net Engine Gaming VPN Tunnel
        if (isVpnModified.getAndSet(false) || NukeGameVpnService.isRunning(context)) {
            runCatching {
                Log.i(TAG, "Deactivating Net Engine gaming tunnel...")
                NukeGameVpnService.stop(context)
            }.onFailure { Log.w(TAG, "Error stopping Net Engine tunnel", it) }
        }

        // 4. Touch Tuning Engine & Touch Listener Daemon
        if (isTouchModified.getAndSet(false) || NukeTouchTuningEngine.isDaemonTouchActive) {
            runCatching {
                Log.i(TAG, "Restoring touch sensitivity and stopping touch listener...")
                NukeTouchTuningEngine.resetToSystemDefaults(context)
                NukeTouchTuningEngine.stopDaemonTouchAsync()
                withContext(Dispatchers.Main) {
                    runCatching { NukeMagicTouchPanelOverlay.getInstance(context).hide() }
                }
                context.getSharedPreferences("nuke_touch_panel_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("touch_listener_active", false).apply()
            }.onFailure { Log.w(TAG, "Error reverting touch tuning", it) }
        }

        // 5. Brightness & Anti-dimmer Lock
        if (isBrightnessModified.getAndSet(false)) {
            runCatching {
                Log.i(TAG, "Restoring display brightness to pre-session baseline...")
                if (baselineBrightnessMode >= 0) {
                    executeShell("settings put system screen_brightness_mode $baselineBrightnessMode 2>/dev/null", context)
                }
                if (baselineBrightness >= 0) {
                    executeShell("settings put system screen_brightness $baselineBrightness 2>/dev/null", context)
                }
            }.onFailure { Log.w(TAG, "Error restoring brightness", it) }
        }

        // 6. CPU Performance Governor
        if (isCpuTurboModified.getAndSet(false)) {
            runCatching {
                Log.i(TAG, "Restoring CPU governor to auto/schedutil...")
                val script = """
                    for gov in $(ls /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor 2>/dev/null); do
                        echo schedutil > ${'$'}gov 2>/dev/null
                    done
                    cmd power set-fixed-performance-mode-enabled false 2>/dev/null
                """.trimIndent()
                executeShell(script, context)
                NukeSystemOptimizer.restoreSystemDefaults(context)
            }.onFailure { Log.w(TAG, "Error reverting CPU turbo", it) }
        }

        // 7. Gaming DND
        if (isDndModified.getAndSet(false)) {
            runCatching {
                Log.i(TAG, "Restoring notification DND policy...")
                executeShell("cmd notification set_interruption_filter 1 2>/dev/null ; settings put global zen_mode 0 2>/dev/null", context)
            }.onFailure { Log.w(TAG, "Error restoring DND", it) }
        }

        // 8. Audio Footstep Boost
        if (isAudioBoostModified.getAndSet(false)) {
            runCatching {
                Log.i(TAG, "Disabling Audio Boost...")
                NukeAudioBooster.disableBoost()
            }.onFailure { Log.w(TAG, "Error disabling Audio Boost", it) }
        }

        // 9. Universal FPS Target Lock
        if (isFpsLockModified.getAndSet(false)) {
            runCatching {
                Log.i(TAG, "Restoring FPS Target to dynamic standard...")
                NukeUniversalFpsLock.setTargetFps(context, 0)
            }.onFailure { Log.w(TAG, "Error resetting FPS target", it) }
        }

        // 10. Packet Boost / TCP Latency
        if (isNetBoostModified.getAndSet(false)) {
            runCatching {
                Log.i(TAG, "Restoring TCP socket parameters...")
                executeShell("setprop net.tcp.delack 1 2>/dev/null ; sysctl -w net.ipv4.tcp_low_latency=0 2>/dev/null", context)
            }.onFailure { Log.w(TAG, "Error restoring Net Boost", it) }
        }

        // 11b. Macro Studio — stop any running macro
        if (isMacroModified.getAndSet(false) || NukeMacroEngine.isRunning) {
            runCatching {
                Log.i(TAG, "Stopping Macro Studio engine...")
                NukeMacroEngine.stopMacro()
                withContext(Dispatchers.Main) {
                    runCatching { NukeMacroStudioOverlay.getInstance(context).hide() }
                }
            }.onFailure { Log.w(TAG, "Error stopping Macro Studio", it) }
        }

        // 11. Quick settings restoration (Hotspot, Silent, Airplane, etc. if modified)
        if (modifiedQuickSettings.isNotEmpty()) {
            runCatching {
                restoreQuickSettings(context)
            }.onFailure { Log.w(TAG, "Error restoring quick settings", it) }
            modifiedQuickSettings.clear()
            baselineQuickSettings.clear()
        }

        // 12. Dismiss any lingering floating overlays safely
        withContext(Dispatchers.Main) {
            runCatching { NukeDeepCoolingFloatingOverlay.getInstance(context).hide() }
            runCatching { NukeAntivirusFloatingOverlay.getInstance(context).hide() }
            runCatching { NukeMagicTouchPanelOverlay.getInstance(context).hide() }
            runCatching { NukeGpuGraphicsPanelOverlay.getInstance(context).hide() }
            runCatching { NukePhoneHealthOverlay.getInstance(context).hide() }
            runCatching { NukeTaskManagerPanelOverlay.getInstance(context).hide() }
            runCatching { NukeLiveChatOverlay.getInstance(context).hide() }
            runCatching { NukeTerminalOverlay.getInstance(context).hide() }
            runCatching { NukeGameDockOverlay.getInstance(context).hide() }
            runCatching { NukeSystemEditorFloatingOverlay.getInstance(context).hide() }
        }

        Log.i(TAG, "Dynamic component restoration completed successfully.")
    }

    private fun restoreQuickSettings(context: Context) {
        for (key in modifiedQuickSettings) {
            val baseline = baselineQuickSettings[key] ?: continue
            when (key) {
                "hotspot" -> {
                    if (baseline == false) {
                        executeShell("cmd wifi stop-tethering 2>/dev/null ; cmd connectivity stop-tethering 0 2>/dev/null", context)
                    }
                }
                "silent_mode" -> {
                    val ringer = if (baseline == true) 0 else 2
                    executeShell("cmd audio set-ringer-mode $ringer 2>/dev/null ; settings put global mode_ringer $ringer 2>/dev/null", context)
                }
                "reading_mode" -> {
                    val flag = if (baseline == true) "1" else "0"
                    executeShell("settings put secure night_display_activated $flag 2>/dev/null ; cmd color night-display ${if (flag == "1") "on" else "off"} 2>/dev/null", context)
                }
                "airplane_mode" -> {
                    val flag = if (baseline == true) "1" else "0"
                    executeShell("settings put global airplane_mode_on $flag 2>/dev/null ; cmd connectivity airplane-mode ${if (flag == "1") "enable" else "disable"} 2>/dev/null", context)
                }
                "vibration" -> {
                    val flag = if (baseline == true) "1" else "0"
                    executeShell("settings put system haptic_feedback_enabled $flag 2>/dev/null", context)
                }
                "screen_timeout_extend" -> {
                    val timeout = if (baseline == true) "1800000" else "60000"
                    executeShell("settings put system screen_off_timeout $timeout 2>/dev/null", context)
                }
                "data_saver" -> {
                    val flag = if (baseline == true) "true" else "false"
                    executeShell("cmd connectivity set-data-saver-mode $flag 2>/dev/null", context)
                }
            }
        }
    }

    // ── Shell Execution Helpers ─────────────────────────────────────────────

    private fun executeShell(script: String, context: Context, timeoutMs: Long = 3_500L) {
        try {
            val cmdRes = NukeConnectionManager.executeCommand(script, timeoutMs)
            if (cmdRes == null || !cmdRes.isSuccess) {
                val adb = AdbManager.getInstance(context)
                if (adb.isConnected()) {
                    adb.executeCommand(script, "/", timeoutMs)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Shell execution failed: ${t.message}")
        }
    }

    private fun executeShellFast(script: String, context: Context, timeoutMs: Long = 2_000L): String? {
        return try {
            val cmdRes = NukeConnectionManager.executeCommand(script, timeoutMs)
            if (cmdRes != null && cmdRes.isSuccess) {
                cmdRes.output
            } else {
                val adb = AdbManager.getInstance(context)
                if (adb.isConnected()) {
                    adb.executeCommand(script, "/", timeoutMs).output
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
