package com.neon.gametweak

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NukeSystemOptimizer — Safe Non-Root System Tweaks & Latency Eliminator.
 *
 * Implements vetted, non-destructive system settings optimizations across:
 * 1. Gaming Touch Engine (pointer speed, high touch sampling, instant pressure scale, deadzone unlock).
 * 2. UI Latency Engine (0.5x animator, window and transition scales).
 * 3. GPU / Game Driver Force (updatable driver for all apps, hardware accelerated rendering).
 * 4. OEM Throttle Guard (Joyose / Powerkeeper / GOS appops restriction).
 * 5. Network Pacing & Jitter Reducer (Wi-Fi sleep policy, mobile data standby).
 */
object NukeSystemOptimizer {

    private const val TAG = "NukeSystemOptimizer"

    suspend fun applyGamingOptimizations(context: Context): Boolean = withContext(Dispatchers.IO) {
        val adb = AdbManager.getInstance(context)
        if (!adb.isConnected()) {
            Log.w(TAG, "ADB not connected, cannot apply system optimizations")
            return@withContext false
        }

        val mfr = Build.MANUFACTURER.lowercase()
        val sb = StringBuilder()

        // ── 1. GAMING TOUCH & SAMPLING ENGINE ──────────────────────────
        // Increase pointer speed to max (7 is standard Android max)
        sb.appendLine("settings put system pointer_speed 7 2>/dev/null")
        // Sensitive touch response & pressure scale (bypasses tap latency)
        sb.appendLine("settings put system touch.pressure.scale 0.001 2>/dev/null")
        sb.appendLine("settings put system touch_blocking_period 0 2>/dev/null")
        // Remove edge deadzones for gaming controls
        sb.appendLine("settings put system edge_mistouch_prevention 0 2>/dev/null")
        sb.appendLine("settings put system edge_touch_prevention 0 2>/dev/null")
        // High touch sensitivity / glove mode for supported OEMs
        sb.appendLine("settings put system high_touch_sensitivity_enable 1 2>/dev/null")

        // ── 2. UI LATENCY & RESPONSIVENESS (0.5x SCALES) ───────────────
        sb.appendLine("settings put global window_animation_scale 0.5 2>/dev/null")
        sb.appendLine("settings put global transition_animation_scale 0.5 2>/dev/null")
        sb.appendLine("settings put global animator_duration_scale 0.5 2>/dev/null")
        sb.appendLine("settings put system miui_home_animation_rate 0 2>/dev/null")

        // ── 3. GPU / UP-TO-DATE GAME DRIVER FORCE ─────────────────────
        sb.appendLine("settings put global updatable_driver_all_apps 1 2>/dev/null")
        sb.appendLine("settings put global force_gpu_rendering 1 2>/dev/null")

        // ── 4. OEM THROTTLE GUARD (JOYOSE / GOS / GAMESPACE BYPASS) ───
        when {
            mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco") -> {
                // Deny usage stats and system alert to Joyose so it cannot detect games to throttle
                sb.appendLine("cmd appops set com.xiaomi.joyose GET_USAGE_STATS ignore 2>/dev/null")
                sb.appendLine("cmd appops set com.xiaomi.joyose SYSTEM_ALERT_WINDOW ignore 2>/dev/null")
                sb.appendLine("cmd appops set com.miui.powerkeeper GET_USAGE_STATS ignore 2>/dev/null")
                sb.appendLine("am stop-app com.xiaomi.joyose 2>/dev/null")
            }
            mfr.contains("samsung") -> {
                // Deny Game Optimizing Service (GOS) access on Samsung
                sb.appendLine("cmd appops set com.samsung.android.game.gos GET_USAGE_STATS ignore 2>/dev/null")
                sb.appendLine("cmd appops set com.samsung.android.game.gametools GET_USAGE_STATS ignore 2>/dev/null")
                sb.appendLine("settings put global sem_low_power_mode 0 2>/dev/null")
            }
            mfr.contains("oppo") || mfr.contains("realme") || mfr.contains("oneplus") -> {
                sb.appendLine("cmd appops set com.coloros.gamespace GET_USAGE_STATS ignore 2>/dev/null")
            }
        }

        // ── 5. NETWORK PACING & ZERO-DROP HANDOVER ────────────────────
        // Keep Wi-Fi awake constantly
        sb.appendLine("settings put global wifi_sleep_policy 2 2>/dev/null")
        sb.appendLine("settings put global mobile_data_always_on 1 2>/dev/null")

        val res = adb.executeCommand(sb.toString(), "/", 6_000L, 8_192)
        Log.i(TAG, "Applied Gaming System Optimizations: ${res.isSuccess}")
        res.isSuccess
    }

    suspend fun restoreSystemDefaults(context: Context): Boolean = withContext(Dispatchers.IO) {
        val adb = AdbManager.getInstance(context)
        if (!adb.isConnected()) return@withContext false

        val sb = StringBuilder()
        sb.appendLine("settings put system pointer_speed 0 2>/dev/null")
        sb.appendLine("settings put global window_animation_scale 1.0 2>/dev/null")
        sb.appendLine("settings put global transition_animation_scale 1.0 2>/dev/null")
        sb.appendLine("settings put global animator_duration_scale 1.0 2>/dev/null")
        sb.appendLine("settings put global updatable_driver_all_apps 0 2>/dev/null")

        val res = adb.executeCommand(sb.toString(), "/", 6_000L, 8_192)
        Log.i(TAG, "Restored System Defaults: ${res.isSuccess}")
        res.isSuccess
    }
}
