package com.neon.gametweak

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * App-owned network component. It does not change routers, DNS or TCP kernel parameters.
 * It asks Android's Wi-Fi stack for the documented gaming/real-time latency modes while the game
 * session is active. Hardware/OEM policy can still decide that a lock has no effect.
 */
class NukeWifiSessionLock(context: Context) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var highPerf: WifiManager.WifiLock? = null
    private var lowLatency: WifiManager.WifiLock? = null

    private var previousScanAlwaysEnabled: String? = null

    data class LockResult(val held: Boolean, val detail: String)

    @Suppress("DEPRECATION")
    fun acquire(): LockResult {
        val manager = wifiManager ?: return LockResult(false, "Wi-Fi service unavailable")
        return try {
            // 1. Privileged 802.11 Anti-Jitter: Disable periodic background Wi-Fi scan spikes (30-sec lag spike)
            if (NukeConnectionManager.isConnected()) {
                val currentScan = runCatching {
                    NukeConnectionManager.executeCommand("settings get global wifi_scan_always_enabled", 2000L, 512)?.output?.trim()
                }.getOrNull()
                if (!currentScan.isNullOrBlank() && currentScan != "null") {
                    previousScanAlwaysEnabled = currentScan
                    NukeConnectionManager.executeCommand("settings put global wifi_scan_always_enabled 0", 2000L, 512)
                }
            }

            // 2. Android Q+ low-latency lock is the preferred gaming mode. Do not hold two Wi-Fi
            // locks simultaneously; use high-performance only as a fallback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (lowLatency == null) {
                    lowLatency = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GameNuke:LowLatency").apply {
                        setReferenceCounted(false)
                    }
                }
                if (lowLatency?.isHeld != true) lowLatency?.acquire()
                if (lowLatency?.isHeld == true) return LockResult(true, "Wi-Fi 802.11 Zero-Jitter active")
            }
            if (highPerf == null) {
                highPerf = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GameNuke:HighPerf").apply {
                    setReferenceCounted(false)
                }
            }
            if (highPerf?.isHeld != true) highPerf?.acquire()
            LockResult(highPerf?.isHeld == true, if (highPerf?.isHeld == true) "Wi-Fi high-performance active" else "Wi-Fi lock rejected")
        } catch (e: Throwable) {
            LockResult(false, e.message ?: "Wi-Fi lock rejected")
        }
    }

    fun release() {
        runCatching { if (lowLatency?.isHeld == true) lowLatency?.release() }
        runCatching { if (highPerf?.isHeld == true) highPerf?.release() }
        lowLatency = null
        highPerf = null

        // Restore original Wi-Fi scan setting if changed
        previousScanAlwaysEnabled?.let { prev ->
            if (NukeConnectionManager.isConnected()) {
                NukeConnectionManager.executeCommand("settings put global wifi_scan_always_enabled $prev", 2000L, 512)
            }
            previousScanAlwaysEnabled = null
        }
    }

    fun isHeld(): Boolean = runCatching {
        highPerf?.isHeld == true || lowLatency?.isHeld == true
    }.getOrDefault(false)
}
