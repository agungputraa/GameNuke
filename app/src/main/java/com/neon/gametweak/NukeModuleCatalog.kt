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
        Module("magic_touch", "Tactical", "Magic Touch Studio", "Capability-probed OEM game-touch mode, Android pointer speed and calibrated X/Y assist for Game Nuke injected drags."),
        Module("gpu_tuner", "Performance", "GPU & Display Tuner", "SkiaGL/Vulkan renderer switch, 1080p/720p resolution scaler, and 120Hz lock."),
        Module("phone_health", "System", "Phone Health Diagnostics", "Real-time battery health, CPU core clock frequencies, RAM & storage telemetry."),
        Module("ai_sentinel", "Performance", "AI Game Sentinel", "Measured CPU/RAM hog detection with protected launcher, input method, dialer, Shizuku and active-game processes."),
        Module("app_switch", "System", "Recent Apps", "Up to four recent launchable apps with optional Android usage access."),
        Module("ping_monitor", "System", "TCP Latency", "Periodic TCP connection timing to a public DNS endpoint, not game-server latency."),
        Module("fps_lock", "Display", "Display Refresh Rate Tuner", "Configures peak display refresh rate preference (60Hz/90Hz/120Hz) via system settings provider."),
        Module("zombie_clean", "System", "Background Process Optimizer", "Trims background application caches and compacts system memory safely."),
        Module("vpn_boost", "System", "Local Network Tools", "Socket pacing and a bounded DNS cache for Game Nuke connections only."),
        Module("footstep_boost", "Tactical", "Footstep Enhancer", "Native hardware equalizer boosting 1kHz-4kHz footstep auditory cues in FPS games."),
        Module("wiki_pip", "Tactical", "Tactical PiP Wiki", "Draggable in-game mini guide browser with transparency controls."),
        Module("fps_overlay", "Performance", "FPS HUD Chip", "Draggable Choreographer cadence/thermal chip backed by SurfaceFlinger game-frame sampling where available."),
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
