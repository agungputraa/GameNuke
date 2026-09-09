package com.neon.gametweak

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * NukeUniversalFpsLock — Multi-Target Display Refresh Rate & FPS Locker.
 *
 * Supports selectable target refresh rates (60, 90, 120, 144, 165 Hz)
 * across all major Android OEMs:
 * - Xiaomi / POCO / Redmi (HyperOS 1.0/2.0, MIUI 12-14)
 * - Samsung Galaxy (OneUI 4 - 7, GOS bypass)
 * - OPPO / Realme / OnePlus (ColorOS, RealmeUI, OxygenOS)
 * - Vivo / iQOO (FuntouchOS, OriginOS)
 * - Asus ROG Phone / Zenfone
 * - Transsion (Infinix, Tecno, Itel)
 * - Motorola, Nothing Phone, Google Pixel, Generic AOSP
 *
 * Dynamically queries physical resolution (never hardcoded) and supported hardware display modes.
 */
object NukeUniversalFpsLock {

    private const val TAG = "NukeUniversalFpsLock"
    private const val PREFS_NAME = "NukeFpsLockPrefs"
    private const val KEY_TARGET_FPS = "target_fps_lock"

    private val _targetFpsState = MutableStateFlow(0) // 0 = OFF / Auto
    val targetFpsState: StateFlow<Int> = _targetFpsState.asStateFlow()

    fun init(context: Context) {
        val prefs = prefs(context)
        _targetFpsState.value = prefs.getInt(KEY_TARGET_FPS, 0)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTargetFps(context: Context): Int =
        prefs(context).getInt(KEY_TARGET_FPS, 0)

    fun getSupportedRefreshRates(context: Context): List<Int> {
        val rates = mutableSetOf(60)
        runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.supportedModes?.forEach { mode ->
                val hz = Math.round(mode.refreshRate)
                if (hz in 30..240) rates.add(hz)
            }
        }
        return rates.sorted()
    }

    suspend fun setTargetFps(context: Context, targetHz: Int): Boolean = withContext(Dispatchers.IO) {
        prefs(context).edit().putInt(KEY_TARGET_FPS, targetHz).apply()
        _targetFpsState.value = targetHz

        if (targetHz <= 0) {
            restoreFpsLock(context)
        } else {
            applyFpsLock(context, targetHz)
        }
    }

    suspend fun cycleNextTarget(context: Context): Int = withContext(Dispatchers.IO) {
        val supported = getSupportedRefreshRates(context)
        // Options sequence: 0 (Auto), then supported rates (e.g. 60, 90, 120, 144)
        val cycle = listOf(0) + supported
        val current = getTargetFps(context)
        val nextIdx = (cycle.indexOf(current) + 1) % cycle.size
        val nextTarget = cycle[nextIdx]
        setTargetFps(context, nextTarget)
        nextTarget
    }

    private fun getPhysicalDisplayMetrics(context: Context): Pair<Int, Int> {
        return runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm?.currentWindowMetrics?.bounds
                if (bounds != null) {
                    val w = minOf(bounds.width(), bounds.height())
                    val h = maxOf(bounds.width(), bounds.height())
                    return@runCatching Pair(w, h)
                }
            }
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(dm)
            Pair(minOf(dm.widthPixels, dm.heightPixels), maxOf(dm.widthPixels, dm.heightPixels))
        }.getOrDefault(Pair(1080, 2400))
    }

    private suspend fun applyFpsLock(context: Context, targetHz: Int): Boolean {
        val adb = AdbManager.getInstance(context)
        if (!adb.isConnected()) {
            Log.w(TAG, "ADB not connected, cannot apply system FPS lock commands")
            return false
        }

        val (width, height) = getPhysicalDisplayMetrics(context)
        val mfr = Build.MANUFACTURER.lowercase()
        val hzFloat = "$targetHz.0"
        val hzStr = "$targetHz"

        val sb = StringBuilder()

        // 1. Universal AOSP Display Settings (Supported on all Android 10+ devices)
        sb.appendLine("settings put system peak_refresh_rate $hzFloat 2>/dev/null")
        sb.appendLine("settings put system min_refresh_rate $hzFloat 2>/dev/null")
        sb.appendLine("settings put global peak_refresh_rate $hzFloat 2>/dev/null")
        sb.appendLine("settings put global min_refresh_rate $hzFloat 2>/dev/null")
        sb.appendLine("settings put system user_refresh_rate $hzStr 2>/dev/null")
        sb.appendLine("settings put secure user_refresh_rate $hzStr 2>/dev/null")

        // 2. OEM-Specific Configurations
        when {
            mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco") -> {
                // Xiaomi HyperOS 1/2 and MIUI 12-14
                sb.appendLine("settings put secure miui_refresh_rate $hzStr 2>/dev/null")
                sb.appendLine("settings put system miui_refresh_rate $hzStr 2>/dev/null")
                sb.appendLine("settings put system lock_refresh_rate $hzStr 2>/dev/null")
                sb.appendLine("settings put system max_refresh_rate $hzStr 2>/dev/null")
                sb.appendLine("settings put system customize_refresh_rate $hzStr 2>/dev/null")
                sb.appendLine("settings put system display_min_refresh_rate $hzStr 2>/dev/null")
                sb.appendLine("settings put system display.refresh_rate $hzStr 2>/dev/null")
                sb.appendLine("settings put system display.disable_dynamic_fps 1 2>/dev/null")
                sb.appendLine("settings put system display.disable_mitigated_fps 1 2>/dev/null")
                sb.appendLine("settings put system display.refresh_rate_changeable 0 2>/dev/null")
                sb.appendLine("settings put system disable_idle_fps true 2>/dev/null")
                sb.appendLine("settings put system vendor.disable_idle_fps true 2>/dev/null")
                sb.appendLine("settings put system thermal_limit_refresh_rate 0 2>/dev/null")
                sb.appendLine("settings put system power_save_refresh_rate 1 2>/dev/null")
                sb.appendLine("settings put system is_smart_fps 0 2>/dev/null")
                sb.appendLine("settings put system NV_FPSLIMIT $hzStr 2>/dev/null")
                // Bypass Joyose thermal throttling limiter
                sb.appendLine("cmd appops set com.xiaomi.joyose GET_USAGE_STATS ignore 2>/dev/null")
                sb.appendLine("cmd appops set com.xiaomi.joyose SYSTEM_ALERT_WINDOW ignore 2>/dev/null")
                sb.appendLine("cmd appops set com.miui.powerkeeper GET_USAGE_STATS ignore 2>/dev/null")
            }
            mfr.contains("samsung") -> {
                // Samsung OneUI: 0 = Standard 60Hz, 1 = Adaptive 120Hz, 2 = High 120Hz Lock
                val oneUiMode = if (targetHz <= 60) 0 else 2
                sb.appendLine("settings put secure refresh_rate_mode $oneUiMode 2>/dev/null")
                sb.appendLine("settings put system refresh_rate_mode $oneUiMode 2>/dev/null")
                sb.appendLine("settings put system high_refresh_rate_mode 1 2>/dev/null")
                sb.appendLine("settings put global sem_low_power_mode 0 2>/dev/null")
                sb.appendLine("cmd appops set com.samsung.android.game.gos GET_USAGE_STATS ignore 2>/dev/null")
            }
            mfr.contains("oppo") || mfr.contains("realme") || mfr.contains("oneplus") -> {
                // ColorOS / OxygenOS / RealmeUI: 1 = 60Hz, 2 = 120Hz, 3 = 144Hz
                val colorOsVal = if (targetHz <= 60) 1 else if (targetHz >= 144) 3 else 2
                sb.appendLine("settings put system oplus_customize_refresh_rate $colorOsVal 2>/dev/null")
                sb.appendLine("settings put system oneplus_screen_refresh_rate $colorOsVal 2>/dev/null")
                sb.appendLine("settings put system refresh_rate_setting $colorOsVal 2>/dev/null")
                sb.appendLine("settings put system lock_refresh_rate $hzStr 2>/dev/null")
                sb.appendLine("settings put system customize_refresh_rate $hzStr 2>/dev/null")
            }
            mfr.contains("vivo") || mfr.contains("iqoo") -> {
                // FuntouchOS / OriginOS
                val vivoVal = if (targetHz <= 60) 1 else 2
                sb.appendLine("settings put system vivoscreen_refresh_rate $vivoVal 2>/dev/null")
                sb.appendLine("settings put system smart_rate_switch 0 2>/dev/null")
            }
            mfr.contains("asus") || mfr.contains("rog") -> {
                // ROG Phone FPS modes
                sb.appendLine("settings put system fps_mode 2 2>/dev/null")
                sb.appendLine("settings put system refresh_rate $hzStr 2>/dev/null")
            }
            mfr.contains("infinix") || mfr.contains("tecno") || mfr.contains("itel") -> {
                // Transsion XOS / HiOS
                sb.appendLine("settings put system tran_refresh_mode $hzStr 2>/dev/null")
                sb.appendLine("settings put system tran_need_recovery_refresh_mode $hzStr 2>/dev/null")
                sb.appendLine("settings put system last_tran_refresh_mode_in_refresh_setting $hzStr 2>/dev/null")
            }
        }

        // 3. Android Display Manager Dynamic Mode Override
        sb.appendLine("cmd display set-match-content-frame-rate-pref 0 2>/dev/null")
        sb.appendLine("cmd display set-user-preferred-display-mode $width $height $hzFloat 0 2>/dev/null")

        // 4. SurfaceFlinger low-level frame rate hints
        sb.appendLine("setprop debug.sf.fps $hzStr 2>/dev/null")
        sb.appendLine("setprop debug.sf.max_fps $hzStr 2>/dev/null")
        sb.appendLine("setprop persist.sys.fps $hzStr 2>/dev/null")
        sb.appendLine("service call SurfaceFlinger 1035 i32 1 2>/dev/null")

        val res = adb.executeCommand(sb.toString(), "/", 6_000L, 8_192)
        Log.i(TAG, "Applied Universal FPS Lock ($targetHz Hz, ${width}x$height): ${res.isSuccess}")
        return res.isSuccess
    }

    private suspend fun restoreFpsLock(context: Context): Boolean {
        val adb = AdbManager.getInstance(context)
        if (!adb.isConnected()) return false

        val mfr = Build.MANUFACTURER.lowercase()
        val sb = StringBuilder()

        // 1. Universal AOSP Restore
        sb.appendLine("settings put system min_refresh_rate 60.0 2>/dev/null")
        sb.appendLine("settings put system peak_refresh_rate 120.0 2>/dev/null")
        sb.appendLine("settings put global min_refresh_rate 60.0 2>/dev/null")
        sb.appendLine("settings put global peak_refresh_rate 120.0 2>/dev/null")
        sb.appendLine("settings put system user_refresh_rate 0 2>/dev/null")
        sb.appendLine("settings put secure user_refresh_rate 0 2>/dev/null")

        // 2. OEM-Specific Restore
        when {
            mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco") -> {
                sb.appendLine("settings put secure miui_refresh_rate 0 2>/dev/null")
                sb.appendLine("settings put system miui_refresh_rate 0 2>/dev/null")
                sb.appendLine("settings delete system thermal_limit_refresh_rate 2>/dev/null")
                sb.appendLine("settings put system display.disable_dynamic_fps 0 2>/dev/null")
                sb.appendLine("settings put system disable_idle_fps false 2>/dev/null")
            }
            mfr.contains("samsung") -> {
                sb.appendLine("settings put secure refresh_rate_mode 1 2>/dev/null")
                sb.appendLine("settings put system refresh_rate_mode 1 2>/dev/null")
            }
            mfr.contains("oppo") || mfr.contains("realme") || mfr.contains("oneplus") -> {
                sb.appendLine("settings put system oplus_customize_refresh_rate 0 2>/dev/null")
                sb.appendLine("settings put system oneplus_screen_refresh_rate 0 2>/dev/null")
            }
            mfr.contains("vivo") || mfr.contains("iqoo") -> {
                sb.appendLine("settings put system smart_rate_switch 1 2>/dev/null")
            }
        }

        // 3. Clear preferred display mode
        sb.appendLine("cmd display clear-user-preferred-display-mode 0 2>/dev/null")
        sb.appendLine("cmd display set-match-content-frame-rate-pref 2 2>/dev/null")

        val res = adb.executeCommand(sb.toString(), "/", 6_000L, 8_192)
        Log.i(TAG, "Restored Display Refresh Rate to Auto: ${res.isSuccess}")
        return res.isSuccess
    }
}
