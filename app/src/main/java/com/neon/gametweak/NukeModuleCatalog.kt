package com.neon.gametweak

import android.content.Context
import android.content.SharedPreferences

/**
 * Real first-party floating tools only. No fake install progress, no remote scripts, no menu item
 * whose action is merely decorative.
 */
object NukeModuleCatalog {
    data class Module(
        val id: String,
        val category: String,
        val title: String,
        val description: String,
        val defaultEnabled: Boolean = true,
    )

    val modules: List<Module> = listOf(
        Module("max_fps", "Performance", "FPS Maximizer", "Uses verified display/game intervention paths and can sample rendered FPS."),
        Module("magic_touch", "Tactical", "Magic Touch Studio", "Realtime X/Y swipe sensitivity seekbars, pointer booster and touch latency reducer."),
        Module("gpu_tuner", "Performance", "GPU & Display Tuner", "SkiaGL/Vulkan renderer switch, 1080p/720p resolution scaler, and 120Hz lock."),
        Module("phone_health", "System", "Phone Health Diagnostics", "Real-time battery health, CPU core clock frequencies, RAM & storage telemetry."),
        Module("ai_sentinel", "Performance", "AI Game Sentinel", "Autonomous background RAM cache cleaning & rogue CPU zombie killer."),
        Module("vpn_boost", "System", "Net Turbo Accelerator", "Cloudflare DoT 1.1.1.1 low-latency gaming DNS and Wi-Fi jitter shield."),
        Module("footstep_boost", "Tactical", "Footstep Enhancer", "Native hardware equalizer boosting 1kHz-4kHz footstep auditory cues in FPS games."),
        Module("wiki_pip", "Tactical", "Tactical PiP Wiki", "Draggable in-game mini guide browser with transparency controls."),
        Module("fps_overlay", "Performance", "FPS HUD Chip", "Draggable hardware-accurate live FPS and thermal floating chip."),
        Module("deep_clean", "Performance", "Deep Clean", "Pressure-aware RAM reclaim and bounded cache trimming with measured results."),
        Module("crosshair", "Tactical", "Crosshair Studio", "Real non-touchable overlay with style, size, opacity, color and position controls."),
        Module("network", "System", "Network Core", "Persistent local core status, Wireless ADB reconnect and Android network panel access."),
        Module("focus", "Tactical", "Focus Shield", "Session-owned interruption control with safe restore."),
        Module("telemetry", "System", "Live Monitor", "Real CPU, RAM, refresh, thermal and rendered-frame sampling."),
    )

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("NukePrefs", Context.MODE_PRIVATE)
    fun key(id: String): String = "module_${sanitize(id)}"

    fun isEnabled(prefs: SharedPreferences, id: String): Boolean {
        val module = modules.firstOrNull { it.id == id } ?: return false
        return prefs.safeBoolean(key(module.id), module.defaultEnabled)
    }

    fun setEnabled(prefs: SharedPreferences, id: String, enabled: Boolean) {
        if (modules.none { it.id == id }) return
        prefs.edit().putBoolean(key(id), enabled).apply()
    }

    fun enabledCount(prefs: SharedPreferences): Int = modules.count { isEnabled(prefs, it.id) }

    private fun sanitize(id: String): String = id.lowercase().replace(Regex("[^a-z0-9_]+"), "_").take(48)
}
