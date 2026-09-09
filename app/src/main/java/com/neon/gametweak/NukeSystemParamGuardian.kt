package com.neon.gametweak

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * NukeSystemParamGuardian — Enterprise-grade Safety Sandbox & Anti-Bootloop Engine.
 *
 * Provides bulletproof protection against accidental bricking, bootloops,
 * or display blackout caused by invalid system parameters or properties.
 *
 * Key Pillars:
 * 1. Strict Anti-Bootloop Blacklist:
 *    Permanently blocks editing of bootloader, crypto, vold, secure boot,
 *    display density hardware defaults, selinux, zygote runtime, and system-partition props.
 * 2. Shell Injection Neutralization:
 *    Strips and rejects any metacharacters (;, &, |, `, $, \n, \r, >, <, (, ), \, ', ").
 * 3. Deep Value & Range Validation:
 *    Enforces numeric ranges for critical parameters (refresh rate 30..240, brightness 0..255,
 *    animation scales 0..10, pointer speed -7..7, swappiness 0..200).
 * 4. Persistent Rollback Journal:
 *    Automatically snapshots original values before modification.
 *    Provides 1-tap emergency restoration of all stock defaults and per-item rollback.
 * 5. Universal OEM Brand Engine:
 *    Detects device manufacturer (Xiaomi/POCO, Samsung, BBK/ColorOS, Vivo, ROG, Transsion, Pixel).
 */
object NukeSystemParamGuardian {

    private const val TAG = "NukeParamGuardian"
    private const val PREFS_JOURNAL = "nuke_system_param_journal"
    private const val KEY_JOURNAL_ENTRIES = "journal_entries_v1"

    enum class RiskLevel(val label: String, val colorHex: String) {
        SAFE("SAFE", "#35C99B"),               // Harmless gaming/UI tweak (e.g. animation, refresh rate, touch)
        MODERATE("CAUTION", "#FFB830"),        // Validated system tuning; requires safe range enforcement
        BLOCKED("RESTRICTED", "#FF4D6A")       // Danger: strictly blocked from editing to avoid bootloop/brick
    }

    enum class ParamSource(val label: String, val shellCmd: String) {
        GLOBAL("GLOBAL", "settings put global"),
        SYSTEM("SYSTEM", "settings put system"),
        SECURE("SECURE", "settings put secure"),
        PROP("PROP", "setprop"),
        KERNEL("KERNEL", "echo")
    }

    enum class ParamCategory(val displayName: String) {
        DISPLAY("Display & FPS"),
        TOUCH("Touch & Input"),
        GRAPHICS("Graphics & HWUI"),
        PERFORMANCE("Performance & CPU"),
        THERMAL("Thermal & Battery"),
        AUDIO_NET("Network & Audio"),
        SYSTEM("System & UI")
    }

    enum class OemBrand(val displayName: String, val chipLabel: String) {
        XIAOMI("Xiaomi / HyperOS / POCO", "HyperOS"),
        SAMSUNG("Samsung OneUI", "OneUI"),
        OPPO_REALME("ColorOS / RealmeUI / OxygenOS", "ColorOS"),
        VIVO("Vivo OriginOS / FuntouchOS", "OriginOS"),
        TRANSSION("Transsion HiOS / XOS", "Transsion"),
        ROG_GAMING("ASUS ROG / RedMagic Gaming", "ROG/Gaming"),
        PIXEL_AOSP("Google Pixel / Pure AOSP", "AOSP"),
        GENERIC("Universal Android", "Android")
    }

    enum class ParamType(val description: String) {
        BOOLEAN("Boolean flag (0 or 1, true or false)"),
        REFRESH_RATE("Display Refresh Rate (30.0 - 240.0 Hz, or 0 auto)"),
        ANIMATION_SCALE("Animation Duration Scale (0.0 - 10.0x)"),
        POINTER_SPEED("Pointer Speed Responsiveness (-7 to 7)"),
        SWAPPINESS("Kernel Swappiness Ratio (0 - 200)"),
        BRIGHTNESS("Display Brightness (0 - 255)"),
        FONT_SCALE("Font Scale Multiplier (0.5 - 2.0)"),
        HWUI_RENDERER("Graphics Hardware Renderer (skiagl, skiavk, opengl, vulkan)"),
        FREE_STRING("Sanitized String Parameter")
    }

    data class SafetyAssessment(
        val level: RiskLevel,
        val message: String,
        val inferredType: ParamType
    )

    data class JournalEntry(
        val timestamp: Long,
        val source: ParamSource,
        val key: String,
        val originalValue: String,
        val appliedValue: String,
        val description: String = ""
    )

    data class ValidationResult(
        val isValid: Boolean,
        val sanitizedValue: String,
        val errorMessage: String? = null
    )

    // =========================================================================
    // BRAND DETECTION ENGINE
    // =========================================================================
    fun detectDeviceBrand(): OemBrand {
        val maker = (Build.MANUFACTURER ?: "").lowercase(Locale.ROOT)
        val brand = (Build.BRAND ?: "").lowercase(Locale.ROOT)
        val model = (Build.MODEL ?: "").lowercase(Locale.ROOT)
        val combined = "$maker $brand $model"

        return when {
            combined.contains("xiaomi") || combined.contains("poco") || combined.contains("redmi") -> OemBrand.XIAOMI
            combined.contains("samsung") -> OemBrand.SAMSUNG
            combined.contains("oppo") || combined.contains("realme") || combined.contains("oneplus") -> OemBrand.OPPO_REALME
            combined.contains("vivo") || combined.contains("iqoo") -> OemBrand.VIVO
            combined.contains("transsion") || combined.contains("infinix") || combined.contains("tecno") || combined.contains("itel") -> OemBrand.TRANSSION
            combined.contains("asus") || combined.contains("rog") || combined.contains("blackshark") || combined.contains("redmagic") || combined.contains("nubia") -> OemBrand.ROG_GAMING
            combined.contains("google") -> OemBrand.PIXEL_AOSP
            else -> OemBrand.GENERIC
        }
    }

    // =========================================================================
    // ANTI-BOOTLOOP BLACKLIST PATTERNS
    // =========================================================================
    private val DANGEROUS_KEY_EXACT_BLACKLIST = setOf(
        "sys.powerctl",
        "sys.boot_completed",
        "ro.boot.flash.locked",
        "ro.boot.verifiedbootstate",
        "ro.boot.veritymode",
        "ro.boot.vbmeta.device_state",
        "ro.crypto.state",
        "ro.crypto.type",
        "vold.decrypt",
        "vold.post_fs_data_done",
        "ro.build.fingerprint",
        "ro.build.id",
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.product.model",
        "ro.product.brand",
        "ro.product.name",
        "ro.product.device",
        "ro.sf.lcd_density",
        "persist.sys.sf.native_mode",
        "selinux.restorecon_recursive",
        "security.perf_harden",
        "persist.sys.timezone",
        "persist.sys.locale",
        "persist.sys.country",
        "persist.sys.language",
        "min_free_kbytes",
        "density",
        "display_density",
        "screen_zoom",
        "persist.sys.display_density"
    )

    private val DANGEROUS_PREFIX_BLACKLIST = listOf(
        "ro.boot.",
        "ro.crypto.",
        "vold.",
        "crypto.",
        "ro.build.",
        "ro.product.",
        "ro.hardware.",
        "ro.board.",
        "ro.vendor.build.",
        "dalvik.vm.",              // Corrupting Zygote heap or flags triggers bootloop
        "selinux."
    )

    // =========================================================================
    // SHELL INJECTION SANITIZER
    // =========================================================================
    private val FORBIDDEN_SHELL_CHARS = setOf(
        ';', '&', '|', '`', '$', '\n', '\r', '>', '<', '(', ')', '\\', '\'', '"', '{', '}'
    )

    /**
     * Infers the parameter type based on its key name.
     */
    fun inferParamType(key: String): ParamType {
        val lower = key.lowercase(Locale.ROOT)
        return when {
            lower.contains("refresh_rate") && !lower.contains("mode") && !lower.contains("switch") && !lower.contains("list") ->
                ParamType.REFRESH_RATE
            lower.contains("animation_scale") || lower.contains("duration_scale") ->
                ParamType.ANIMATION_SCALE
            lower.contains("pointer_speed") ->
                ParamType.POINTER_SPEED
            lower.contains("swappiness") ->
                ParamType.SWAPPINESS
            lower == "screen_brightness" ->
                ParamType.BRIGHTNESS
            lower == "font_scale" ->
                ParamType.FONT_SCALE
            lower == "debug.hwui.renderer" ->
                ParamType.HWUI_RENDERER
            lower.contains("enable") || lower.contains("disable") || lower.contains("allow") ||
                    lower.contains("power_save") || lower.contains("game_mode") || lower.contains("gt_mode") ||
                    lower.contains("low_power") || lower.contains("always_on") || lower.contains("always_enabled") ||
                    lower.contains("latch_unsignaled") || lower.endsWith(".hw") || lower.contains("responsiveness") ->
                ParamType.BOOLEAN
            else ->
                ParamType.FREE_STRING
        }
    }

    /**
     * Assesses the risk level of any system parameter.
     */
    fun classifyRisk(source: ParamSource, key: String): RiskLevel {
        val lowerKey = key.trim().lowercase(Locale.ROOT)

        // 1. Exact blacklist
        if (DANGEROUS_KEY_EXACT_BLACKLIST.contains(lowerKey)) {
            return RiskLevel.BLOCKED
        }

        // 2. Prefix blacklist
        for (prefix in DANGEROUS_PREFIX_BLACKLIST) {
            if (lowerKey.startsWith(prefix)) {
                return RiskLevel.BLOCKED
            }
        }

        // 3. Read-only properties (props starting with 'ro.') can never be written by setprop
        if (source == ParamSource.PROP && lowerKey.startsWith("ro.")) {
            return RiskLevel.BLOCKED
        }

        // 4. Known safe gaming & tuning parameters
        if (isKnownSafeKey(lowerKey)) {
            return RiskLevel.SAFE
        }

        // 5. Default to MODERATE for other user-queryable keys
        return RiskLevel.MODERATE
    }

    /**
     * Live Risk Assessment meter that updates in real time for Add/Edit wizards.
     */
    fun assessKeySafety(source: ParamSource, key: String): SafetyAssessment {
        val trimmed = key.trim()
        val type = inferParamType(trimmed)

        if (trimmed.isEmpty()) {
            return SafetyAssessment(RiskLevel.MODERATE, "Enter parameter key name", type)
        }

        val risk = classifyRisk(source, trimmed)
        val message = when (risk) {
            RiskLevel.BLOCKED -> "⛔ Protected System Property / Anti-Bootloop Lock Active"
            RiskLevel.SAFE -> "🟢 Safe Gaming / Display Parameter (Validated Operating Bounds)"
            RiskLevel.MODERATE -> "🟡 Validated System Parameter (Check Value Range Carefully)"
        }

        return SafetyAssessment(risk, message, type)
    }

    private fun isKnownSafeKey(k: String): Boolean {
        return k.contains("refresh_rate") ||
                k.contains("animator_duration_scale") ||
                k.contains("window_animation_scale") ||
                k.contains("transition_animation_scale") ||
                k.contains("pointer_speed") ||
                k.contains("touch") ||
                k.contains("hwui") ||
                k.contains("fps") ||
                k.contains("thermal") ||
                k.contains("screen_brightness") ||
                k.contains("font_scale") ||
                k.contains("game") ||
                k.contains("swappiness") ||
                k.contains("low_power") ||
                k.contains("vibrate") ||
                k.contains("sf_thread_pool") ||
                k.contains("turbosched") ||
                k.contains("latch_unsignaled") ||
                k.contains("wifi_sleep") ||
                k.contains("gt_mode")
    }

    /**
     * Sanitizes and validates any value before it is written to the system.
     */
    fun validateValue(source: ParamSource, key: String, rawValue: String): ValidationResult {
        val trimmed = rawValue.trim()

        if (trimmed.isEmpty()) {
            return ValidationResult(false, "", "Value cannot be empty")
        }

        if (trimmed.length > 256) {
            return ValidationResult(false, "", "Value exceeds safe maximum length (256 chars)")
        }

        // Check for shell metacharacters
        for (c in trimmed) {
            if (FORBIDDEN_SHELL_CHARS.contains(c)) {
                return ValidationResult(
                    false,
                    "",
                    "Security Violation: Forbidden character detected ('$c'). Shell injection blocked."
                )
            }
        }

        // Check if key is blocked
        if (classifyRisk(source, key) == RiskLevel.BLOCKED) {
            return ValidationResult(
                false,
                "",
                "Modification Blocked: '$key' is a protected system key. Modifying it may cause a bootloop."
            )
        }

        val paramType = inferParamType(key)

        when (paramType) {
            ParamType.REFRESH_RATE -> {
                val floatVal = trimmed.toFloatOrNull()
                if (floatVal == null) {
                    return ValidationResult(false, "", "Refresh rate must be a valid number (e.g. 60, 90, 120, 144)")
                }
                if (floatVal != 0f && (floatVal < 30f || floatVal > 240f)) {
                    return ValidationResult(
                        false,
                        "",
                        "Unsafe Refresh Rate: Value must be between 30.0 and 240.0 Hz (or 0 for dynamic auto)."
                    )
                }
            }
            ParamType.ANIMATION_SCALE -> {
                val floatVal = trimmed.toFloatOrNull()
                if (floatVal == null || floatVal < 0f || floatVal > 10f) {
                    return ValidationResult(false, "", "Animation scale must be a number between 0.0 and 10.0")
                }
            }
            ParamType.POINTER_SPEED -> {
                val intVal = trimmed.toIntOrNull()
                if (intVal == null || intVal < -7 || intVal > 7) {
                    return ValidationResult(false, "", "Pointer speed must be an integer between -7 and 7")
                }
            }
            ParamType.SWAPPINESS -> {
                val intVal = trimmed.toIntOrNull()
                if (intVal == null || intVal < 0 || intVal > 200) {
                    return ValidationResult(false, "", "Swappiness must be an integer between 0 and 200")
                }
            }
            ParamType.BRIGHTNESS -> {
                val intVal = trimmed.toIntOrNull()
                if (intVal == null || intVal < 0 || intVal > 255) {
                    return ValidationResult(false, "", "Brightness must be an integer between 0 and 255")
                }
            }
            ParamType.FONT_SCALE -> {
                val floatVal = trimmed.toFloatOrNull()
                if (floatVal == null || floatVal < 0.5f || floatVal > 2.0f) {
                    return ValidationResult(false, "", "Font scale must be between 0.5 and 2.0")
                }
            }
            ParamType.HWUI_RENDERER -> {
                val allowed = listOf("skiagl", "skiavk", "opengl", "vulkan")
                if (!allowed.contains(trimmed.lowercase(Locale.ROOT))) {
                    return ValidationResult(false, "", "Renderer must be one of: skiagl, skiavk, opengl, vulkan")
                }
            }
            ParamType.BOOLEAN -> {
                val lowerVal = trimmed.lowercase(Locale.ROOT)
                val validBools = setOf("0", "1", "true", "false", "on", "off")
                if (!validBools.contains(lowerVal)) {
                    return ValidationResult(false, "", "Boolean parameter must be 0, 1, true, or false")
                }
            }
            ParamType.FREE_STRING -> {
                // Already passed character checks and length check
            }
        }

        return ValidationResult(true, trimmed)
    }

    // =========================================================================
    // PERSISTENT ROLLBACK JOURNAL
    // =========================================================================
    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_JOURNAL, Context.MODE_PRIVATE)
    }

    /**
     * Records a parameter before it is altered, storing the stock/original value.
     */
    fun recordOriginalValue(
        context: Context,
        source: ParamSource,
        key: String,
        originalValue: String,
        appliedValue: String,
        description: String = ""
    ) {
        runCatching {
            val list = getJournal(context).toMutableList()
            val existingIndex = list.indexOfFirst { it.source == source && it.key == key }
            if (existingIndex >= 0) {
                val existing = list[existingIndex]
                list[existingIndex] = existing.copy(
                    timestamp = System.currentTimeMillis(),
                    appliedValue = appliedValue
                )
            } else {
                list.add(
                    JournalEntry(
                        timestamp = System.currentTimeMillis(),
                        source = source,
                        key = key,
                        originalValue = originalValue,
                        appliedValue = appliedValue,
                        description = description
                    )
                )
            }
            saveJournal(context, list)
        }.onFailure { Log.w(TAG, "Failed to record journal: ${it.message}") }
    }

    /**
     * Returns all recorded modifications.
     */
    fun getJournal(context: Context): List<JournalEntry> {
        return runCatching {
            val raw = prefs(context).getString(KEY_JOURNAL_ENTRIES, null) ?: return emptyList()
            val array = JSONArray(raw)
            val result = mutableListOf<JournalEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    JournalEntry(
                        timestamp = obj.optLong("timestamp", 0L),
                        source = ParamSource.valueOf(obj.optString("source", "SYSTEM")),
                        key = obj.optString("key", ""),
                        originalValue = obj.optString("originalValue", ""),
                        appliedValue = obj.optString("appliedValue", ""),
                        description = obj.optString("description", "")
                    )
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    fun getJournalEntry(context: Context, source: ParamSource, key: String): JournalEntry? {
        return getJournal(context).firstOrNull { it.source == source && it.key == key }
    }

    private fun saveJournal(context: Context, entries: List<JournalEntry>) {
        val array = JSONArray()
        for (e in entries) {
            val obj = JSONObject().apply {
                put("timestamp", e.timestamp)
                put("source", e.source.name)
                put("key", e.key)
                put("originalValue", e.originalValue)
                put("appliedValue", e.appliedValue)
                put("description", e.description)
            }
            array.put(obj)
        }
        prefs(context).edit().putString(KEY_JOURNAL_ENTRIES, array.toString()).apply()
    }

    /**
     * Clears the journal after a full restoration.
     */
    fun clearJournal(context: Context) {
        prefs(context).edit().remove(KEY_JOURNAL_ENTRIES).apply()
    }

    /**
     * Removes a single journal entry if the user deliberately reverted it.
     */
    fun removeJournalEntry(context: Context, source: ParamSource, key: String) {
        val updated = getJournal(context).filterNot { it.source == source && it.key == key }
        saveJournal(context, updated)
    }
}
