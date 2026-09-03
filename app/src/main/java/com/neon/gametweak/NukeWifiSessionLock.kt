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

    data class Result(val held: Boolean, val detail: String)

    @Suppress("DEPRECATION")
    fun acquire(): Result {
        val manager = wifiManager ?: return Result(false, "Wi-Fi service unavailable")
        return runCatching {
            // Android Q+ low-latency lock is the preferred gaming mode. Do not hold two Wi-Fi
            // locks simultaneously; use high-performance only as a fallback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (lowLatency == null) {
                    lowLatency = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GameNuke:LowLatency").apply {
                        setReferenceCounted(false)
                    }
                }
                if (lowLatency?.isHeld != true) lowLatency?.acquire()
                if (lowLatency?.isHeld == true) return@runCatching Result(true, "Wi-Fi low-latency lock acquired")
            }
            if (highPerf == null) {
                highPerf = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GameNuke:HighPerf").apply {
                    setReferenceCounted(false)
                }
            }
            if (highPerf?.isHeld != true) highPerf?.acquire()
            Result(highPerf?.isHeld == true, if (highPerf?.isHeld == true) "Wi-Fi high-performance lock acquired" else "Wi-Fi lock rejected")
        }.getOrElse { Result(false, it.message ?: "Wi-Fi lock rejected") }
    }

    fun release() {
        runCatching { if (lowLatency?.isHeld == true) lowLatency?.release() }
        runCatching { if (highPerf?.isHeld == true) highPerf?.release() }
        lowLatency = null
        highPerf = null
    }

    fun isHeld(): Boolean = runCatching {
        highPerf?.isHeld == true || lowLatency?.isHeld == true
    }.getOrDefault(false)
}
