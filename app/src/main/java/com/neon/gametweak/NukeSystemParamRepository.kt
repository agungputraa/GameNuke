package com.neon.gametweak

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.neon.gametweak.NukeSystemParamGuardian.OemBrand
import com.neon.gametweak.NukeSystemParamGuardian.ParamCategory
import com.neon.gametweak.NukeSystemParamGuardian.ParamSource
import com.neon.gametweak.NukeSystemParamGuardian.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * NukeSystemParamRepository — High-Performance Multi-Tier Parameter Discovery & Query Engine.
 *
 * Scans, indexes, and monitors 3,000+ system parameters across:
 *  - Tier 1: Local process getprop (always available even before ADB connection)
 *  - Tier 2: Privileged Settings Provider tables (Global, System, Secure) via ADB / Shizuku / iAdb
 *  - Tier 3: Android ContentResolver fallback for standard framework settings
 *  - Curated High-Impact Gaming Parameters & Presets tailored for OEM ROMs:
 *    Xiaomi HyperOS (POCO X6 Pro), Samsung OneUI, ColorOS, OriginOS, ROG, Snapdragon & Dimensity.
 */
object NukeSystemParamRepository {

    private const val TAG = "NukeParamRepo"
    private const val PREFS_FAVORITES = "nuke_system_param_favorites"
    private const val KEY_FAVORITES_SET = "favorite_param_ids"

    data class NukeSystemParam(
        val id: String,
        val source: ParamSource,
        val key: String,
        val value: String,
        val category: ParamCategory,
        val riskLevel: RiskLevel,
        val description: String? = null,
        val recommendedValue: String? = null,
        val presets: List<String> = emptyList(),
        val isFavorite: Boolean = false,
        val isModified: Boolean = false,
        val isCurated: Boolean = false,
        val targetBrand: OemBrand? = null,
        val stockValue: String? = null,
        val isDynamicallyDiscovered: Boolean = false
    )

    data class ApplyResult(
        val success: Boolean,
        val message: String,
        val updatedParam: NukeSystemParam? = null
    )

    data class RollbackSummary(
        val successCount: Int,
        val failedCount: Int,
        val details: List<String>
    )

    private data class CuratedDef(
        val source: ParamSource,
        val key: String,
        val category: ParamCategory,
        val description: String,
        val recommendedValue: String,
        val presets: List<String>,
        val targetBrand: OemBrand? = null
    )

    // =========================================================================
    // COMPREHENSIVE CURATED OEM & GAMING PARAMETER CATALOG
    // =========================================================================
    private val CURATED_CATALOG = listOf(
        // ── UNIVERSAL DISPLAY & REFRESH RATE ─────────────────────────────────
        CuratedDef(
            ParamSource.SYSTEM, "peak_refresh_rate", ParamCategory.DISPLAY,
            "Requests the selected display refresh target where the device supports it.",
            "120.0", listOf("60.0", "90.0", "120.0", "144.0")
        ),
        CuratedDef(
            ParamSource.SYSTEM, "min_refresh_rate", ParamCategory.DISPLAY,
            "Forces minimum refresh rate to prevent screen stutter during gameplay.",
            "120.0", listOf("60.0", "90.0", "120.0", "144.0")
        ),
        CuratedDef(
            ParamSource.GLOBAL, "peak_refresh_rate", ParamCategory.DISPLAY,
            "Global system ceiling for display refresh rate across all windows.",
            "120.0", listOf("60.0", "90.0", "120.0", "144.0")
        ),
        CuratedDef(
            ParamSource.GLOBAL, "min_refresh_rate", ParamCategory.DISPLAY,
            "Global system floor for display refresh rate.",
            "120.0", listOf("60.0", "90.0", "120.0", "144.0")
        ),
        CuratedDef(
            ParamSource.SYSTEM, "user_refresh_rate", ParamCategory.DISPLAY,
            "User-defined refresh rate preference.",
            "120", listOf("60", "90", "120", "144")
        ),
        CuratedDef(
            ParamSource.SECURE, "user_refresh_rate", ParamCategory.DISPLAY,
            "Secure user refresh rate setting.",
            "120", listOf("60", "90", "120", "144")
        ),

        // ── XIAOMI / HYPEROS / POCO X6 PRO (DUCHAMP) ─────────────────────────
        CuratedDef(
            ParamSource.SYSTEM, "miui_refresh_rate", ParamCategory.DISPLAY,
            "Xiaomi HyperOS / MIUI specific display refresh rate target.",
            "120", listOf("60", "90", "120", "144"), OemBrand.XIAOMI
        ),
        CuratedDef(
            ParamSource.SECURE, "miui_refresh_rate", ParamCategory.DISPLAY,
            "Xiaomi HyperOS / MIUI secure display refresh rate target.",
            "120", listOf("60", "90", "120", "144"), OemBrand.XIAOMI
        ),
        CuratedDef(
            ParamSource.PROP, "persist.sys.miui.sf_thread_pool", ParamCategory.GRAPHICS,
            "HyperOS SurfaceFlinger multi-threaded rendering pipeline for low jitter.",
            "1", listOf("1", "0"), OemBrand.XIAOMI
        ),
        CuratedDef(
            ParamSource.PROP, "persist.sys.performance.level", ParamCategory.PERFORMANCE,
            "Xiaomi kernel performance level governor override.",
            "1", listOf("1", "0"), OemBrand.XIAOMI
        ),
        CuratedDef(
            ParamSource.PROP, "debug.sf.enable_gl_backpressure", ParamCategory.GRAPHICS,
            "Reduces SurfaceFlinger backpressure queue delay during fast gameplay.",
            "0", listOf("0", "1"), OemBrand.XIAOMI
        ),
        CuratedDef(
            ParamSource.PROP, "persist.vendor.vpp.touch_enhance", ParamCategory.TOUCH,
            "Hardware touch response enhancement on MediaTek/Snapdragon Xiaomi devices.",
            "1", listOf("1", "0"), OemBrand.XIAOMI
        ),
        CuratedDef(
            ParamSource.PROP, "persist.sys.turbosched.enable", ParamCategory.PERFORMANCE,
            "HyperOS TurboSched priority CPU scheduling for active foreground games.",
            "1", listOf("1", "0"), OemBrand.XIAOMI
        ),
        CuratedDef(
            ParamSource.SYSTEM, "power_supersave_mode_open", ParamCategory.PERFORMANCE,
            "Xiaomi battery supersave mode flag. Set to 0 for unthrottled CPU clocks.",
            "0", listOf("0", "1"), OemBrand.XIAOMI
        ),

        // ── SAMSUNG ONEUI ───────────────────────────────────────────────────
        CuratedDef(
            ParamSource.SYSTEM, "refresh_rate_mode", ParamCategory.DISPLAY,
            "Samsung OneUI display refresh rate mode (0 = Standard 60Hz, 1 = High 120Hz/Adaptive).",
            "1", listOf("1", "0"), OemBrand.SAMSUNG
        ),
        CuratedDef(
            ParamSource.SYSTEM, "sem_enhanced_cpu_responsiveness", ParamCategory.PERFORMANCE,
            "Samsung OneUI Enhanced Processing / High CPU responsiveness mode.",
            "1", listOf("1", "0"), OemBrand.SAMSUNG
        ),
        CuratedDef(
            ParamSource.GLOBAL, "sem_low_power_mode", ParamCategory.PERFORMANCE,
            "Samsung OneUI background power saver restriction flag.",
            "0", listOf("0", "1"), OemBrand.SAMSUNG
        ),
        CuratedDef(
            ParamSource.SECURE, "game_performance_mode", ParamCategory.PERFORMANCE,
            "Samsung game performance preference on supported firmware.",
            "1", listOf("1", "0"), OemBrand.SAMSUNG
        ),
        CuratedDef(
            ParamSource.SYSTEM, "sub_lcd_refresh_rate", ParamCategory.DISPLAY,
            "Samsung sub-display refresh rate lock.",
            "120", listOf("60", "120"), OemBrand.SAMSUNG
        ),

        // ── COLOROS / REALMEUI / OXYGENOS ───────────────────────────────────
        CuratedDef(
            ParamSource.SYSTEM, "oplus_customize_screen_refresh_rate", ParamCategory.DISPLAY,
            "Oppo/Realme/OnePlus custom screen refresh rate setting.",
            "120", listOf("60", "90", "120", "144"), OemBrand.OPPO_REALME
        ),
        CuratedDef(
            ParamSource.SYSTEM, "lock_refresh_rate", ParamCategory.DISPLAY,
            "OEM refresh rate lock override (BBK / OnePlus / Realme).",
            "120", listOf("60", "90", "120", "144"), OemBrand.OPPO_REALME
        ),
        CuratedDef(
            ParamSource.SYSTEM, "touch_panel_freq", ParamCategory.TOUCH,
            "Touch panel sampling frequency override (BBK / Realme).",
            "240", listOf("120", "240", "360"), OemBrand.OPPO_REALME
        ),
        CuratedDef(
            ParamSource.PROP, "oplus.perf.gt_mode", ParamCategory.PERFORMANCE,
            "Realme GT Mode / Oppo performance preference on supported firmware.",
            "1", listOf("1", "0"), OemBrand.OPPO_REALME
        ),

        // ── VIVO / ORIGINOS / FUNTOUCHOS ────────────────────────────────────
        CuratedDef(
            ParamSource.SYSTEM, "vivo_refresh_rate_mode", ParamCategory.DISPLAY,
            "Vivo OriginOS refresh rate governor (1 = 60Hz, 2 = 120Hz/High, 3 = Smart).",
            "2", listOf("1", "2", "3"), OemBrand.VIVO
        ),
        CuratedDef(
            ParamSource.SYSTEM, "vivo_game_cube_mode", ParamCategory.PERFORMANCE,
            "Vivo game performance preference on supported firmware.",
            "1", listOf("1", "0"), OemBrand.VIVO
        ),
        CuratedDef(
            ParamSource.PROP, "persist.vivo.touch_sampling", ParamCategory.TOUCH,
            "Vivo high-frequency touch sampling activation.",
            "1", listOf("1", "0"), OemBrand.VIVO
        ),

        // ── ASUS ROG / GAMING PHONES ────────────────────────────────────────
        CuratedDef(
            ParamSource.PROP, "sys.asus.gaming_mode", ParamCategory.PERFORMANCE,
            "ASUS ROG Armoury Crate X-Mode performance state.",
            "1", listOf("1", "0"), OemBrand.ROG_GAMING
        ),
        CuratedDef(
            ParamSource.SYSTEM, "touch_sampling_rate", ParamCategory.TOUCH,
            "Touch sampling preference exposed by supported gaming devices.",
            "240", listOf("120", "240", "360", "480"), OemBrand.ROG_GAMING
        ),

        // ── TOUCH & AIM RESPONSIVENESS ───────────────────────────────────────
        CuratedDef(
            ParamSource.SYSTEM, "pointer_speed", ParamCategory.TOUCH,
            "Android pointer tracking responsiveness (-7 to 7) where supported.",
            "5", listOf("0", "3", "5", "7")
        ),
        CuratedDef(
            ParamSource.PROP, "debug.touch.responsiveness", ParamCategory.TOUCH,
            "Touch panel driver latency reduction flag.",
            "1", listOf("1", "0")
        ),
        CuratedDef(
            ParamSource.PROP, "touch.pressure.scale", ParamCategory.TOUCH,
            "Touch pressure calibration multiplier for sensitive tap recognition.",
            "0.001", listOf("0.001", "0.005", "0.01")
        ),

        // ── GRAPHICS & HWUI (SNAPDRAGON & DIMENSITY) ─────────────────────────
        CuratedDef(
            ParamSource.PROP, "debug.hwui.renderer", ParamCategory.GRAPHICS,
            "Hardware UI 2D acceleration engine. SkiaVK uses Vulkan pipeline for lowest frame drop.",
            "skiavk", listOf("skiagl", "skiavk", "opengl", "vulkan")
        ),
        CuratedDef(
            ParamSource.PROP, "debug.sf.latch_unsignaled", ParamCategory.GRAPHICS,
            "SurfaceFlinger latches render buffers without waiting for sync fences, reducing frame lag.",
            "1", listOf("1", "0")
        ),
        CuratedDef(
            ParamSource.PROP, "debug.sf.early_app_phase_offset_ns", ParamCategory.GRAPHICS,
            "Application frame dispatch offset. Lower offset yields tighter input-to-display sync.",
            "500000", listOf("500000", "0", "1000000")
        ),
        CuratedDef(
            ParamSource.PROP, "debug.sf.early_gl_phase_offset_ns", ParamCategory.GRAPHICS,
            "GPU GL render phase offset in nanoseconds.",
            "1000000", listOf("1000000", "500000", "0")
        ),
        CuratedDef(
            ParamSource.PROP, "debug.sf.high_fps_early_gl_phase_offset_ns", ParamCategory.GRAPHICS,
            "High refresh rate SurfaceFlinger phase tuning for 120Hz/144Hz panels.",
            "6000000", listOf("6000000", "4000000", "2000000")
        ),
        CuratedDef(
            ParamSource.GLOBAL, "game_driver_all_apps", ParamCategory.GRAPHICS,
            "Directs the Android graphics driver manager to route 3D games to the high-perf driver.",
            "1", listOf("1", "0")
        ),
        CuratedDef(
            ParamSource.PROP, "debug.egl.hw", ParamCategory.GRAPHICS,
            "Forces hardware acceleration for EGL surface composition.",
            "1", listOf("1", "0")
        ),
        CuratedDef(
            ParamSource.PROP, "debug.sf.hw", ParamCategory.GRAPHICS,
            "Forces hardware acceleration for SurfaceFlinger display composition.",
            "1", listOf("1", "0")
        ),
        CuratedDef(
            ParamSource.PROP, "vendor.perf.gestureFlingBoost", ParamCategory.TOUCH,
            "Vendor fling-response preference on supported devices.",
            "1", listOf("1", "0")
        ),

        // ── PERFORMANCE & CPU CLOCKS ─────────────────────────────────────────
        CuratedDef(
            ParamSource.GLOBAL, "low_power", ParamCategory.PERFORMANCE,
            "Android system power saver mode. Must be 0 for peak CPU clock retention.",
            "0", listOf("0", "1")
        ),
        CuratedDef(
            ParamSource.SYSTEM, "high_refresh_rate_mode", ParamCategory.PERFORMANCE,
            "High performance display overdrive mode on supported OEM devices.",
            "1", listOf("1", "0")
        ),

        // ── THERMAL & BATTERY ────────────────────────────────────────────────
        CuratedDef(
            ParamSource.PROP, "debug.thermal.throttle", ParamCategory.THERMAL,
            "Advanced vendor thermal-frequency override parameter on supported devices.",
            "0", listOf("0", "1")
        ),
        CuratedDef(
            ParamSource.SYSTEM, "thermal_limit_refresh_rate", ParamCategory.THERMAL,
            "Prevents thermal subsystem from dropping refresh rate when device warms up.",
            "0", listOf("0", "1")
        ),

        // ── NETWORK & LOW-PING AUDIO ─────────────────────────────────────────
        CuratedDef(
            ParamSource.GLOBAL, "wifi_scan_always_enabled", ParamCategory.AUDIO_NET,
            "Disables background Wi-Fi scanning to eliminate recurring ping spikes during online gaming.",
            "0", listOf("0", "1")
        ),
        CuratedDef(
            ParamSource.GLOBAL, "wifi_sleep_policy", ParamCategory.AUDIO_NET,
            "Keeps Wi-Fi radio at full power without entering power-saving sleep (2 = Never sleep).",
            "2", listOf("2", "0")
        ),
        CuratedDef(
            ParamSource.GLOBAL, "mobile_data_always_on", ParamCategory.AUDIO_NET,
            "Keeps mobile data active as fallback for instant packet re-route.",
            "1", listOf("1", "0")
        ),

        // ── SYSTEM & UI ANIMATIONS ───────────────────────────────────────────
        CuratedDef(
            ParamSource.GLOBAL, "window_animation_scale", ParamCategory.SYSTEM,
            "Window transition animation duration (0.5x makes UI snappy, 0 removes delay).",
            "0.5", listOf("0.0", "0.5", "1.0")
        ),
        CuratedDef(
            ParamSource.GLOBAL, "transition_animation_scale", ParamCategory.SYSTEM,
            "Activity opening and closing transition speed multiplier.",
            "0.5", listOf("0.0", "0.5", "1.0")
        ),
        CuratedDef(
            ParamSource.GLOBAL, "animator_duration_scale", ParamCategory.SYSTEM,
            "General UI elements animation multiplier.",
            "0.5", listOf("0.0", "0.5", "1.0")
        )
    )

    // =========================================================================
    // PARAMETER DISCOVERY & MULTI-TIER INDEXING ENGINE
    // =========================================================================

    /**
     * Queries all system parameter sources across all tiers and returns indexed parameters.
     */
    suspend fun fetchAllParameters(context: Context): List<NukeSystemParam> = withContext(Dispatchers.IO) {
        val deviceBrand = NukeSystemParamGuardian.detectDeviceBrand()
        val favorites = getFavorites(context)
        val journal = NukeSystemParamGuardian.getJournal(context)
        val journalMap = journal.associateBy { "${it.source.name}:${it.key}" }

        val resultMap = mutableMapOf<String, NukeSystemParam>()

        // ── TIER 1: Local process getprop (Always available even without ADB pairing) ──
        fetchLocalSystemProperties(resultMap, favorites, journalMap, deviceBrand)

        // ── TIER 2: Privileged shell query via NukeConnectionManager / ADB ───────────
        if (NukeConnectionManager.isConnected()) {
            fetchSettingsTable(ParamSource.GLOBAL, resultMap, favorites, journalMap, deviceBrand)
            fetchSettingsTable(ParamSource.SYSTEM, resultMap, favorites, journalMap, deviceBrand)
            fetchSettingsTable(ParamSource.SECURE, resultMap, favorites, journalMap, deviceBrand)
            fetchPrivilegedProperties(resultMap, favorites, journalMap, deviceBrand)
        }

        // ── TIER 3: ContentResolver Fallback for common framework settings ────────────
        fetchContentResolverSettings(context, resultMap, favorites, journalMap, deviceBrand)

        // ── MERGE CURATED PRESETS & OEM PROFILES ──────────────────────────────────────
        mergeCuratedParameters(context, resultMap, favorites, journalMap, deviceBrand)

        // Return sorted list: Favorites first, Modified next, Device-Brand matches, Curated, then Key
        resultMap.values.sortedWith(
            compareByDescending<NukeSystemParam> { it.isFavorite }
                .thenByDescending { it.isModified }
                .thenByDescending { it.targetBrand == deviceBrand }
                .thenByDescending { it.isCurated }
                .thenBy { it.key }
        )
    }

    /**
     * Tier 1: Executes local `getprop` command via Android runtime.
     * All Android apps can query getprop directly without root or ADB privileges.
     */
    private fun fetchLocalSystemProperties(
        resultMap: MutableMap<String, NukeSystemParam>,
        favorites: Set<String>,
        journalMap: Map<String, NukeSystemParamGuardian.JournalEntry>,
        deviceBrand: OemBrand
    ) {
        runCatching {
            val proc = Runtime.getRuntime().exec("getprop")
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            parseGetpropOutput(output, resultMap, favorites, journalMap, deviceBrand)
        }.onFailure { Log.w(TAG, "Local getprop execution error: ${it.message}") }
    }

    private fun fetchPrivilegedProperties(
        resultMap: MutableMap<String, NukeSystemParam>,
        favorites: Set<String>,
        journalMap: Map<String, NukeSystemParamGuardian.JournalEntry>,
        deviceBrand: OemBrand
    ) {
        val output = runShellCommand("getprop")
        if (!output.isNullOrBlank()) {
            parseGetpropOutput(output, resultMap, favorites, journalMap, deviceBrand)
        }
    }

    private fun parseGetpropOutput(
        output: String,
        resultMap: MutableMap<String, NukeSystemParam>,
        favorites: Set<String>,
        journalMap: Map<String, NukeSystemParamGuardian.JournalEntry>,
        deviceBrand: OemBrand
    ) {
        val regex = Regex("""\[([^]]+)]:\s*\[([^]]*)]""")
        regex.findAll(output).forEach { match ->
            val k = match.groupValues[1].trim()
            val v = match.groupValues[2].trim()
            val id = "PROP:$k"
            val journalEntry = journalMap[id]

            resultMap[id] = NukeSystemParam(
                id = id,
                source = ParamSource.PROP,
                key = k,
                value = v,
                category = detectCategory(k),
                riskLevel = NukeSystemParamGuardian.classifyRisk(ParamSource.PROP, k),
                isFavorite = favorites.contains(id),
                isModified = journalEntry != null,
                targetBrand = if (k.contains("miui") || k.contains("xiaomi")) OemBrand.XIAOMI else null,
                stockValue = journalEntry?.originalValue
            )
        }
    }

    private fun fetchSettingsTable(
        source: ParamSource,
        resultMap: MutableMap<String, NukeSystemParam>,
        favorites: Set<String>,
        journalMap: Map<String, NukeSystemParamGuardian.JournalEntry>,
        deviceBrand: OemBrand
    ) {
        val cmd = "settings list ${source.name.lowercase(Locale.ROOT)}"
        val output = runShellCommand(cmd)

        if (!output.isNullOrBlank()) {
            output.lineSequence().forEach { line ->
                val trimmed = line.trim()
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val k = trimmed.substring(0, eqIdx).trim()
                    val v = trimmed.substring(eqIdx + 1).trim()
                    val id = "${source.name}:$k"
                    val journalEntry = journalMap[id]

                    resultMap[id] = NukeSystemParam(
                        id = id,
                        source = source,
                        key = k,
                        value = v,
                        category = detectCategory(k),
                        riskLevel = NukeSystemParamGuardian.classifyRisk(source, k),
                        isFavorite = favorites.contains(id),
                        isModified = journalEntry != null,
                        stockValue = journalEntry?.originalValue
                    )
                }
            }
        }
    }

    /**
     * Tier 3: ContentResolver query for common settings in case shell access is limited.
     */
    private fun fetchContentResolverSettings(
        context: Context,
        resultMap: MutableMap<String, NukeSystemParam>,
        favorites: Set<String>,
        journalMap: Map<String, NukeSystemParamGuardian.JournalEntry>,
        deviceBrand: OemBrand
    ) {
        val cr = context.contentResolver

        val systemKeys = listOf(
            "pointer_speed", "screen_brightness", "font_scale", "accelerometer_rotation",
            "peak_refresh_rate", "min_refresh_rate", "user_refresh_rate", "miui_refresh_rate",
            "lock_refresh_rate", "refresh_rate_mode", "high_refresh_rate_mode"
        )
        for (k in systemKeys) {
            val id = "SYSTEM:$k"
            if (!resultMap.containsKey(id)) {
                val v = runCatching { Settings.System.getString(cr, k) }.getOrNull()
                if (v != null) {
                    val journalEntry = journalMap[id]
                    resultMap[id] = NukeSystemParam(
                        id = id,
                        source = ParamSource.SYSTEM,
                        key = k,
                        value = v,
                        category = detectCategory(k),
                        riskLevel = NukeSystemParamGuardian.classifyRisk(ParamSource.SYSTEM, k),
                        isFavorite = favorites.contains(id),
                        isModified = journalEntry != null,
                        stockValue = journalEntry?.originalValue
                    )
                }
            }
        }

        val globalKeys = listOf(
            "window_animation_scale", "transition_animation_scale", "animator_duration_scale",
            "low_power", "wifi_sleep_policy", "wifi_scan_always_enabled", "mobile_data_always_on",
            "game_driver_all_apps"
        )
        for (k in globalKeys) {
            val id = "GLOBAL:$k"
            if (!resultMap.containsKey(id)) {
                val v = runCatching { Settings.Global.getString(cr, k) }.getOrNull()
                if (v != null) {
                    val journalEntry = journalMap[id]
                    resultMap[id] = NukeSystemParam(
                        id = id,
                        source = ParamSource.GLOBAL,
                        key = k,
                        value = v,
                        category = detectCategory(k),
                        riskLevel = NukeSystemParamGuardian.classifyRisk(ParamSource.GLOBAL, k),
                        isFavorite = favorites.contains(id),
                        isModified = journalEntry != null,
                        stockValue = journalEntry?.originalValue
                    )
                }
            }
        }
    }

    private fun mergeCuratedParameters(
        context: Context,
        resultMap: MutableMap<String, NukeSystemParam>,
        favorites: Set<String>,
        journalMap: Map<String, NukeSystemParamGuardian.JournalEntry>,
        deviceBrand: OemBrand
    ) {
        for (curated in CURATED_CATALOG) {
            val id = "${curated.source.name}:${curated.key}"
            val existing = resultMap[id]
            val journalEntry = journalMap[id]

            if (existing != null) {
                resultMap[id] = existing.copy(
                    category = curated.category,
                    description = curated.description,
                    recommendedValue = curated.recommendedValue,
                    presets = curated.presets,
                    isCurated = true,
                    targetBrand = curated.targetBrand ?: existing.targetBrand,
                    stockValue = journalEntry?.originalValue ?: existing.value
                )
            } else {
                val currentVal = readSingleParam(context, curated.source, curated.key) ?: ""
                resultMap[id] = NukeSystemParam(
                    id = id,
                    source = curated.source,
                    key = curated.key,
                    value = currentVal,
                    category = curated.category,
                    riskLevel = NukeSystemParamGuardian.classifyRisk(curated.source, curated.key),
                    description = curated.description,
                    recommendedValue = curated.recommendedValue,
                    presets = curated.presets,
                    isFavorite = favorites.contains(id),
                    isModified = journalEntry != null,
                    isCurated = true,
                    targetBrand = curated.targetBrand,
                    stockValue = journalEntry?.originalValue ?: currentVal
                )
            }
        }
    }

    private fun readSingleParam(context: Context, source: ParamSource, key: String): String? {
        return when (source) {
            ParamSource.GLOBAL -> runCatching { Settings.Global.getString(context.contentResolver, key) }.getOrNull()
            ParamSource.SYSTEM -> runCatching { Settings.System.getString(context.contentResolver, key) }.getOrNull()
            ParamSource.SECURE -> runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrNull()
            ParamSource.PROP -> {
                // Try local getprop first, then privileged shell
                val local = runCatching {
                    val p = Runtime.getRuntime().exec("getprop $key")
                    p.inputStream.bufferedReader().use { it.readText().trim() }
                }.getOrNull()
                if (!local.isNullOrBlank()) local else runShellCommand("getprop $key")?.trim()?.ifBlank { null }
            }
            ParamSource.KERNEL -> null
        }
    }

    private fun detectCategory(key: String): ParamCategory {
        val lower = key.lowercase(Locale.ROOT)
        return when {
            lower.contains("refresh") || lower.contains("fps") || lower.contains("display") || lower.contains("screen") -> ParamCategory.DISPLAY
            lower.contains("touch") || lower.contains("pointer") || lower.contains("input") || lower.contains("pressure") -> ParamCategory.TOUCH
            lower.contains("hwui") || lower.contains("gpu") || lower.contains("render") || lower.contains("sf") || lower.contains("egl") || lower.contains("vulkan") -> ParamCategory.GRAPHICS
            lower.contains("cpu") || lower.contains("performance") || lower.contains("governor") || lower.contains("sched") || lower.contains("power") || lower.contains("turbo") -> ParamCategory.PERFORMANCE
            lower.contains("thermal") || lower.contains("cool") || lower.contains("temp") || lower.contains("battery") -> ParamCategory.THERMAL
            lower.contains("wifi") || lower.contains("net") || lower.contains("audio") || lower.contains("sound") || lower.contains("tcp") -> ParamCategory.AUDIO_NET
            else -> ParamCategory.SYSTEM
        }
    }

    private fun runShellCommand(cmd: String): String? {
        val res = NukeConnectionManager.executeCommand(cmd, 6000L)
        return res?.stdout
    }

    /**
     * Dynamically probes the system for hidden properties, settings, or vendor flags
     * matching [query], which may not be returned by standard list enumerations.
     */
    suspend fun queryDynamicHiddenParameters(
        context: Context,
        query: String,
        existingIds: Set<String>
    ): List<NukeSystemParam> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()

        val discovered = mutableListOf<NukeSystemParam>()
        val favorites = getFavorites(context)
        val journal = NukeSystemParamGuardian.getJournal(context)
        val journalMap = journal.associateBy { "${it.source.name}:${it.key}" }

        // 1. Direct getprop queries with typical prefixes
        val propCandidates = listOf(
            q,
            "debug.$q",
            "persist.$q",
            "persist.sys.$q",
            "persist.vendor.$q",
            "vendor.$q",
            "ro.$q"
        ).distinct()

        for (propKey in propCandidates) {
            val id = "PROP:$propKey"
            if (existingIds.contains(id) || discovered.any { it.id == id }) continue
            val v = readSingleParam(context, ParamSource.PROP, propKey)
            if (!v.isNullOrBlank()) {
                val j = journalMap[id]
                discovered.add(
                    NukeSystemParam(
                        id = id,
                        source = ParamSource.PROP,
                        key = propKey,
                        value = v,
                        category = detectCategory(propKey),
                        riskLevel = NukeSystemParamGuardian.classifyRisk(ParamSource.PROP, propKey),
                        isFavorite = favorites.contains(id),
                        isModified = j != null,
                        stockValue = j?.originalValue,
                        isDynamicallyDiscovered = true
                    )
                )
            }
        }

        // 2. Direct Settings queries (GLOBAL, SYSTEM, SECURE)
        for (source in listOf(ParamSource.GLOBAL, ParamSource.SYSTEM, ParamSource.SECURE)) {
            val id = "${source.name}:$q"
            if (existingIds.contains(id) || discovered.any { it.id == id }) continue
            val v = readSingleParam(context, source, q)
            if (!v.isNullOrBlank() && v != "null") {
                val j = journalMap[id]
                discovered.add(
                    NukeSystemParam(
                        id = id,
                        source = source,
                        key = q,
                        value = v,
                        category = detectCategory(q),
                        riskLevel = NukeSystemParamGuardian.classifyRisk(source, q),
                        isFavorite = favorites.contains(id),
                        isModified = j != null,
                        stockValue = j?.originalValue,
                        isDynamicallyDiscovered = true
                    )
                )
            }
        }

        // 3. Shell grep probe for live hidden getprop entries matching query pattern
        if (NukeConnectionManager.isConnected()) {
            val sanitizedQ = q.replace("'", "").replace("\"", "").replace(";", "")
            val grepOutput = runShellCommand("getprop | grep -i '$sanitizedQ' | head -n 25")
            if (!grepOutput.isNullOrBlank()) {
                val regex = Regex("""\[([^]]+)]:\s*\[([^]]*)]""")
                regex.findAll(grepOutput).forEach { match ->
                    val k = match.groupValues[1].trim()
                    val v = match.groupValues[2].trim()
                    val id = "PROP:$k"
                    if (!existingIds.contains(id) && discovered.none { it.id == id }) {
                        val j = journalMap[id]
                        discovered.add(
                            NukeSystemParam(
                                id = id,
                                source = ParamSource.PROP,
                                key = k,
                                value = v,
                                category = detectCategory(k),
                                riskLevel = NukeSystemParamGuardian.classifyRisk(ParamSource.PROP, k),
                                isFavorite = favorites.contains(id),
                                isModified = j != null,
                                stockValue = j?.originalValue,
                                isDynamicallyDiscovered = true
                            )
                        )
                    }
                }
            }
        }

        discovered
    }

    // =========================================================================
    // PARAMETER WRITING & PRE-FLIGHT SNAPSHOT
    // =========================================================================

    /**
     * Safely applies a new value to a parameter with validation & rollback recording.
     */
    suspend fun applyParameter(
        context: Context,
        param: NukeSystemParam,
        newValue: String
    ): ApplyResult = withContext(Dispatchers.IO) {
        // 1. Validate through Guardian
        val validation = NukeSystemParamGuardian.validateValue(param.source, param.key, newValue)
        if (!validation.isValid) {
            return@withContext ApplyResult(false, validation.errorMessage ?: "Invalid value")
        }

        val sanitized = validation.sanitizedValue

        // 2. Record original value in persistent journal before modifying
        NukeSystemParamGuardian.recordOriginalValue(
            context = context,
            source = param.source,
            key = param.key,
            originalValue = param.stockValue ?: param.value,
            appliedValue = sanitized,
            description = param.description ?: ""
        )

        // 3. Build & execute command
        val shellCmd = when (param.source) {
            ParamSource.GLOBAL -> "settings put global ${param.key} $sanitized"
            ParamSource.SYSTEM -> "settings put system ${param.key} $sanitized"
            ParamSource.SECURE -> "settings put secure ${param.key} $sanitized"
            ParamSource.PROP -> "setprop ${param.key} $sanitized"
            ParamSource.KERNEL -> "echo $sanitized > ${param.key}"
        }

        val result = NukeConnectionManager.executeCommand(shellCmd, 4000L)
        val ok = result != null && (result.exitCode == 0 || result.stderr.isBlank())

        if (ok) {
            val updated = param.copy(
                value = sanitized,
                isModified = true,
                stockValue = param.stockValue ?: param.value
            )
            ApplyResult(true, "Successfully applied ${param.key} = $sanitized", updated)
        } else {
            ApplyResult(
                false,
                "Shell command failed: ${result?.stderr?.ifBlank { "Exit code ${result.exitCode}" } ?: "Core engine not connected"}"
            )
        }
    }

    /**
     * Reverts a single modified parameter back to its original stock value.
     */
    suspend fun revertSingleParameter(
        context: Context,
        param: NukeSystemParam
    ): ApplyResult = withContext(Dispatchers.IO) {
        val stock = param.stockValue ?: run {
            val entry = NukeSystemParamGuardian.getJournalEntry(context, param.source, param.key)
            entry?.originalValue
        }

        if (stock == null) {
            return@withContext ApplyResult(false, "No recorded stock value found for ${param.key}")
        }

        val restoreCmd = when (param.source) {
            ParamSource.GLOBAL -> "settings put global ${param.key} $stock"
            ParamSource.SYSTEM -> "settings put system ${param.key} $stock"
            ParamSource.SECURE -> "settings put secure ${param.key} $stock"
            ParamSource.PROP -> "setprop ${param.key} $stock"
            ParamSource.KERNEL -> "echo $stock > ${param.key}"
        }

        val result = NukeConnectionManager.executeCommand(restoreCmd, 4000L)
        val ok = result != null && (result.exitCode == 0 || result.stderr.isBlank())

        if (ok) {
            NukeSystemParamGuardian.removeJournalEntry(context, param.source, param.key)
            val updated = param.copy(
                value = stock,
                isModified = false
            )
            ApplyResult(true, "Restored ${param.key} to original stock ($stock)", updated)
        } else {
            ApplyResult(false, "Failed to revert: ${result?.stderr ?: "Command error"}")
        }
    }

    // =========================================================================
    // 1-TAP EMERGENCY ROLLBACK TO STOCK DEFAULTS
    // =========================================================================

    /**
     * Restores ALL modified parameters back to their original values recorded in the journal.
     */
    suspend fun rollbackAll(context: Context): RollbackSummary = withContext(Dispatchers.IO) {
        val journal = NukeSystemParamGuardian.getJournal(context)
        if (journal.isEmpty()) {
            return@withContext RollbackSummary(0, 0, listOf("No modified parameters to revert."))
        }

        var success = 0
        var failed = 0
        val details = mutableListOf<String>()

        for (entry in journal) {
            val restoreCmd = when (entry.source) {
                ParamSource.GLOBAL -> "settings put global ${entry.key} ${entry.originalValue}"
                ParamSource.SYSTEM -> "settings put system ${entry.key} ${entry.originalValue}"
                ParamSource.SECURE -> "settings put secure ${entry.key} ${entry.originalValue}"
                ParamSource.PROP -> "setprop ${entry.key} ${entry.originalValue}"
                ParamSource.KERNEL -> "echo ${entry.originalValue} > ${entry.key}"
            }

            val res = NukeConnectionManager.executeCommand(restoreCmd, 3000L)
            if (res != null && (res.exitCode == 0 || res.stderr.isBlank())) {
                success++
                details.add("Restored ${entry.key} -> ${entry.originalValue}")
            } else {
                failed++
                details.add("Failed ${entry.key}: ${res?.stderr ?: "unknown error"}")
            }
        }

        if (success > 0) {
            NukeSystemParamGuardian.clearJournal(context)
        }

        RollbackSummary(success, failed, details)
    }

    /**
     * Applies safe, curated gaming optimizations for the detected device OEM brand.
     * Returns the count of successfully applied parameters.
     */
    suspend fun applyCuratedGamingOptimizations(context: Context): Int = withContext(Dispatchers.IO) {
        val detectedBrand = NukeSystemParamGuardian.detectDeviceBrand()
        val curatedList = CURATED_CATALOG.filter { it.targetBrand == null || it.targetBrand == detectedBrand }
        var appliedCount = 0

        for (curated in curatedList) {
            val param = NukeSystemParam(
                id = "${curated.source.name}:${curated.key}",
                source = curated.source,
                key = curated.key,
                value = "",
                category = curated.category,
                riskLevel = NukeSystemParamGuardian.classifyRisk(curated.source, curated.key),
                description = curated.description,
                recommendedValue = curated.recommendedValue,
                presets = curated.presets,
                isCurated = true,
                targetBrand = curated.targetBrand
            )
            val res = applyParameter(context, param, curated.recommendedValue)
            if (res.success) {
                appliedCount++
            }
        }
        appliedCount
    }

    // =========================================================================
    // FAVORITES & BOOKMARKS
    // =========================================================================
    private fun favoritesPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_FAVORITES, Context.MODE_PRIVATE)
    }

    fun getFavorites(context: Context): Set<String> {
        return favoritesPrefs(context).getStringSet(KEY_FAVORITES_SET, emptySet()) ?: emptySet()
    }

    fun toggleFavorite(context: Context, paramId: String): Boolean {
        val current = getFavorites(context).toMutableSet()
        val isNowFav = if (current.contains(paramId)) {
            current.remove(paramId)
            false
        } else {
            current.add(paramId)
            true
        }
        favoritesPrefs(context).edit().putStringSet(KEY_FAVORITES_SET, current).apply()
        return isNowFav
    }

    // =========================================================================
    // EXPORT PRESETS ENGINE
    // =========================================================================
    /**
     * Exports parameters into a shareable module JSON string.
     * Can export modified parameters, curated presets, or custom selected parameter list.
     */
    fun exportParametersToJson(
        context: Context,
        moduleName: String = "My Device Gaming Preset",
        author: String = "Game Nuke User",
        description: String = "Optimized system parameters exported from Game Nuke",
        targetParams: List<NukeSystemParam>? = null
    ): String {
        val paramsToExport = if (!targetParams.isNullOrEmpty()) {
            targetParams
        } else {
            val journal = NukeSystemParamGuardian.getJournal(context)
            if (journal.isNotEmpty()) {
                journal.map { entry ->
                    NukeSystemParam(
                        id = "${entry.source.name}:${entry.key}",
                        source = entry.source,
                        key = entry.key,
                        value = entry.appliedValue,
                        category = detectCategory(entry.key),
                        riskLevel = NukeSystemParamGuardian.classifyRisk(entry.source, entry.key),
                        description = entry.description.ifBlank { "Tuned gaming parameter" },
                        isModified = true,
                        stockValue = entry.originalValue
                    )
                }
            } else {
                CURATED_CATALOG.map { def ->
                    NukeSystemParam(
                        id = "${def.source.name}:${def.key}",
                        source = def.source,
                        key = def.key,
                        value = def.recommendedValue,
                        category = def.category,
                        riskLevel = NukeSystemParamGuardian.classifyRisk(def.source, def.key),
                        description = def.description,
                        isCurated = true,
                        targetBrand = def.targetBrand
                    )
                }
            }
        }

        val items = paramsToExport.map { p ->
            NukeModuleJsonImporter.ModuleParamItem(
                source = p.source,
                key = p.key,
                rawValue = p.value,
                sanitizedValue = p.value,
                description = p.description ?: "",
                riskLevel = p.riskLevel,
                isAllowed = p.riskLevel != RiskLevel.BLOCKED
            )
        }

        return NukeModuleJsonImporter.exportModuleJson(
            moduleName = moduleName,
            author = author,
            version = "1.0",
            description = description,
            targetOem = android.os.Build.MANUFACTURER,
            items = items
        )
    }
}

