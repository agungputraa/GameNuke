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
        Module("max_fps", "Performance", "Display & FPS Controls", "Uses supported display/game configuration paths and rendered-frame sampling where available."),
        Module("magic_touch", "Tactical", "Touch Listener", "Kernel-level touch response controls, Android pointer speed, and calibrated in-game X/Y multipliers."),
        Module("gpu_tuner", "Performance", "GPU & Display Tuner", "Renderer preference, resolution profile and refresh target controls where supported by the device."),
        Module("phone_health", "System", "Device Health & Telemetry", "Battery, thermal, CPU frequency, RAM, zRAM and storage telemetry from available system sources."),
        Module("ai_sentinel", "Performance", "AI Sentinel", "Adaptive session monitoring with protected system, launcher, input method, dialer, Shizuku and active-game processes."),
        Module("app_switch", "System", "Recent Apps", "Up to four recent launchable apps with optional Android usage access."),
        Module("ping_monitor", "System", "TCP Latency", "Periodic TCP connection timing to a public DNS endpoint, not game-server latency."),
        Module("fps_lock", "Display", "Refresh Rate Target", "Requests supported display refresh-rate preferences through available system settings paths."),
        Module("zombie_clean", "System", "Background Maintenance", "Requests eligible background maintenance, cache trimming and memory compaction through available system paths."),
        Module("vpn_boost", "System", "Local Network Tools", "Socket pacing and a bounded DNS cache for Game Nuke connections only."),
        Module("footstep_boost", "Tactical", "Audio Focus Profile", "Equalizer profile emphasizing the 1kHz–4kHz range when the device audio path supports it."),
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
