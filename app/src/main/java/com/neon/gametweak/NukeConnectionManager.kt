package com.neon.gametweak

import android.util.Log

/**
 * Central connection abstraction for all privileged shell backends.
 *
 * Priority order (highest → lowest):
 *  1. IADB    – iAdb privileged service (lightweight, zero overhead, highly stable).
 *  2. SHIZUKU – Shizuku privileged service (survives WiFi-off, app cleared from recents).
 *  3. DAEMON  – Local Unix socket daemon (NukeShellDaemon).
 *  4. NONE    – No backend available (monitoring-only mode).
 *
 * executeCommand() always goes through this manager so all app components (CPU sampler,
 * display settings, booster, deep cleaner, game space) function seamlessly under any active backend.
 */
object NukeConnectionManager {
    private const val TAG = "NukeConnectionManager"

    enum class Backend { IADB, SHIZUKU, DAEMON, ADB_NATIVE, NONE }

    @Volatile
    var preferredBackend: Backend? = null

    /** Returns the currently active (connected) backend according to priority. */
    fun activeBackend(): Backend {
        val pref = preferredBackend
        if (pref != null) {
            when (pref) {
                Backend.IADB -> if (NukeIadbBridge.isConnected()) return Backend.IADB
                Backend.SHIZUKU -> if (NukeShizukuBridge.isConnected()) return Backend.SHIZUKU
                Backend.DAEMON -> if (NukeDaemonClient.ping()) return Backend.DAEMON
                Backend.ADB_NATIVE -> return Backend.ADB_NATIVE
                Backend.NONE -> return Backend.NONE
            }
        }
        // Default priority: IADB (most stable) -> SHIZUKU -> DAEMON -> NONE
        return when {
            NukeIadbBridge.isConnected() -> Backend.IADB
            NukeShizukuBridge.isConnected() -> Backend.SHIZUKU
            NukeDaemonClient.ping() -> Backend.DAEMON
            else -> Backend.NONE
        }
    }

    /** Returns true if ANY backend is connected and ready. */
    fun isConnected(): Boolean = activeBackend() != Backend.NONE

    /**
     * Execute a shell command through the best available backend.
     * Returns null if no backend is available.
     * NEVER throws — all exceptions are caught internally.
     */
    fun executeCommand(
        command: String,
        timeoutMs: Long = 7_500L,
        maxOutputChars: Int = 131_072,
    ): NukeCommandResult? {
        return when (activeBackend()) {
            Backend.IADB -> runCatching {
                NukeIadbBridge.execute(command, timeoutMs, maxOutputChars)
            }.onFailure { Log.w(TAG, "iAdb execute failed: ${it.message}") }.getOrNull()

            Backend.SHIZUKU -> runCatching {
                NukeShizukuBridge.execute(command, timeoutMs, maxOutputChars)
            }.onFailure { Log.w(TAG, "Shizuku execute failed: ${it.message}") }.getOrNull()

            Backend.DAEMON -> runCatching {
                NukeDaemonClient.execute(command, timeoutMs, maxOutputChars)
            }.onFailure { Log.w(TAG, "Daemon execute failed: ${it.message}") }.getOrNull()

            Backend.ADB_NATIVE, Backend.NONE -> null
        }
    }

    /** Human-readable connection mode label for UI. */
    fun connectionLabel(): String = when (activeBackend()) {
        Backend.IADB        -> "IADB"
        Backend.SHIZUKU     -> "SHIZUKU"
        Backend.DAEMON      -> "LOCAL CORE"
        Backend.ADB_NATIVE  -> "WIRELESS ADB"
        Backend.NONE        -> "OFFLINE"
    }

    /** Returns true if the active backend is one that persists across WiFi-off / app restart. */
    fun isPersistent(): Boolean = when (activeBackend()) {
        Backend.IADB, Backend.SHIZUKU, Backend.DAEMON -> true
        Backend.ADB_NATIVE, Backend.NONE -> false
    }
}
