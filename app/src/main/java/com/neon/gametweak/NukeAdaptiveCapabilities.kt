package com.neon.gametweak

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * Resolves the UI surface from public Android APIs plus the engine's verified runtime probes.
 * Manufacturer names are used only for an honest compatibility label; no private OEM intent or
 * unverified OEM shell command is selected here.
 */
internal data class NukeCapabilitySnapshot(
    val available: Set<String>,
    val unavailableReasons: Map<String, String>,
    val deviceFamily: String,
    val compatibilityLabel: String,
) {
    fun has(id: String): Boolean = id in available
    fun supportsAll(ids: Set<String>): Boolean = available.containsAll(ids)
    fun supportsAny(ids: Set<String>): Boolean = ids.isEmpty() || ids.any(available::contains)
}

internal object NukeAdaptiveCapabilities {
    private val engineCapabilityToRemote = mapOf(
        "refresh_guard" to "shell.refresh_guard",
        "frame_scan" to "shell.frame_scan",
        "cpu_clocks" to "shell.cpu_clocks",
        "compact" to "shell.compaction",
        "cache_trim" to "shell.cache_trim",
        "art" to "shell.art_prime",
        "power_saver" to "shell.power_saver",
        "data_saver" to "shell.data_saver",
        "rotation_lock" to "shell.rotation",
        "background_sweep" to "shell.background_release",
        "gpu_relief" to "shell.gpu_relief",
        "game_mode" to "shell.game_mode",
        "android15_frame_guard" to "shell.android15_frame_guard",
    )

    fun resolve(context: Context, state: NukePerformanceEngine.State?): NukeCapabilitySnapshot = runCatching {
        val manufacturer = (Build.MANUFACTURER.orEmpty() + " " + Build.BRAND.orEmpty()).lowercase(Locale.US)
        val family = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") || manufacturer.contains("blackshark") -> "xiaomi-family"
            manufacturer.contains("samsung") -> "samsung-family"
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> "oppo-family"
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> "vivo-family"
            manufacturer.contains("transsion") || manufacturer.contains("infinix") || manufacturer.contains("tecno") || manufacturer.contains("itel") -> "transsion-family"
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> "huawei-family"
            manufacturer.contains("asus") || manufacturer.contains("rog") -> "asus-family"
            manufacturer.contains("motorola") || manufacturer.contains("moto") || manufacturer.contains("lenovo") -> "motorola-family"
            manufacturer.contains("google") || manufacturer.contains("pixel") -> "pixel-family"
            manufacturer.contains("sony") -> "sony-family"
            manufacturer.contains("zte") || manufacturer.contains("nubia") || manufacturer.contains("redmagic") -> "redmagic-family"
            manufacturer.contains("nothing") -> "nothing-family"
            manufacturer.contains("meizu") -> "meizu-family"
            else -> "android-standard"
        }
        val familyLabel = when (family) {
            "xiaomi-family" -> "XIAOMI / HYPEROS / JOYUI"
            "samsung-family" -> "SAMSUNG / ONE UI"
            "oppo-family" -> "OPPO / COLOROS / OXYGENOS"
            "vivo-family" -> "VIVO / ORIGINOS / FUNTOUCH"
            "transsion-family" -> "TRANSSION / XOS / HIOS"
            "huawei-family" -> "HUAWEI / HONOR / MAGICOS"
            "asus-family" -> "ASUS / ROG GAMING UI"
            "motorola-family" -> "MOTOROLA / MY UX"
            "pixel-family" -> "GOOGLE PIXEL / AOSP"
            "sony-family" -> "SONY XPERIA"
            "redmagic-family" -> "REDMAGIC / NUBIA GAMING"
            "nothing-family" -> "NOTHING OS"
            "meizu-family" -> "MEIZU / FLYME"
            else -> "${Build.MANUFACTURER.uppercase(Locale.US).ifBlank { "ANDROID" }} • AOSP"
        }

        val available = linkedSetOf<String>()
        val reasons = linkedMapOf<String, String>()
        fun expose(id: String, supported: Boolean, failure: String) {
            if (supported) available += id else reasons[id] = failure
        }

        val packageManager = context.packageManager
        val overlayGranted = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        val hasWifi = runCatching { packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI) }.getOrDefault(true)
        expose("overlay", overlayGranted, "Display-over-other-apps permission is unavailable or not granted")
        expose("local.telemetry", state?.ramTotalMb?.let { it > 0L } == true, "Local RAM telemetry is unavailable")
        expose("local.display", state?.let { it.currentHz > 0 || it.maxHz > 0 } == true, "Display refresh data is unavailable")
        expose("local.wifi", hasWifi, "This device does not expose Wi-Fi hardware")
        expose("local.keep_awake", overlayGranted, "The overlay is not available")
        expose("local.dnd", runCatching { context.getSystemService(Context.NOTIFICATION_SERVICE) is NotificationManager }.getOrDefault(false), "Android notification service is unavailable")
        expose("local.thermal", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q, "Thermal API is unavailable")
        expose("local.battery", true, "")
        expose("local.storage", state?.storageTotalGb?.let { it > 0f } == true, "Storage telemetry is unavailable")

        expose("extended.control", state?.adbConnected == true, "Extended Device Control is not connected")
        val verified = state?.capabilities.orEmpty().associateBy { it.id }
        engineCapabilityToRemote.forEach { (engineId, remoteId) ->
            val capability = verified[engineId]
            expose(
                remoteId,
                state?.adbConnected == true && capability?.supported == true,
                capability?.detail ?: "Runtime probe did not verify this operation",
            )
        }

        NukeCapabilitySnapshot(
            available = available,
            unavailableReasons = reasons,
            deviceFamily = family,
            compatibilityLabel = "$familyLabel • RUNTIME ADAPTIVE",
        )
    }.getOrElse { error ->
        android.util.Log.e("NukeAdaptiveCap", "Fallback capability snapshot due to error", error)
        NukeCapabilitySnapshot(
            available = setOf("local.battery"),
            unavailableReasons = mapOf("error" to (error.message ?: "Unknown error")),
            deviceFamily = "android-standard",
            compatibilityLabel = "ANDROID • SAFE FALLBACK",
        )
    }
}
