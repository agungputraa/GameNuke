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
        // Default priority: IADB (most stable) -> SHIZUKU -> DAEMON -> ADB_NATIVE -> NONE
        return when {
            NukeIadbBridge.isConnected() -> Backend.IADB
            NukeShizukuBridge.isConnected() -> Backend.SHIZUKU
            NukeDaemonClient.ping() -> Backend.DAEMON
            NukeApplication.instance?.let { runCatching { AdbManager.getInstance(it).isConnected() }.getOrDefault(false) } == true -> Backend.ADB_NATIVE
            else -> Backend.NONE
        }
    }

    /** Returns true if ANY backend is connected and ready. */
    fun isConnected(): Boolean = activeBackend() != Backend.NONE

    /** Returns active privileged IShellService Binder via iAdb or Shizuku if available. */
    fun getShellService(): IShellService? {
        return NukeIadbBridge.getShellService() ?: NukeShizukuBridge.getShellService()
    }

    /** Returns true if Touch Listener is supported through current connections. */
    fun isTouchSupported(): Boolean = getShellService() != null || NukeDaemonClient.ping() || isConnected()

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

            Backend.ADB_NATIVE -> runCatching {
                val ctx = NukeApplication.instance ?: return null
                val adb = AdbManager.getInstance(ctx)
                adb.executeCommandDirect(command, "/", timeoutMs, maxOutputChars)
            }.onFailure { Log.w(TAG, "AdbManager execute failed: ${it.message}") }.getOrNull()

            Backend.NONE -> null
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

    /**
     * Bootstraps the local daemon (NukeShellDaemon) using whichever backend is currently connected
     * (Shizuku, iAdb, or Wireless ADB). Returns true when the daemon is responding to PING.
     */
    fun bootstrapPersistentCore(context: android.content.Context): Boolean {
        if (NukeDaemonClient.ping(force = true)) return true

        // Try getting APK path via sourceDir first (fastest) or pm path fallback
        var targetApk = context.applicationInfo.sourceDir
        if (!java.io.File(targetApk).exists()) {
            val pmResult = executeCommand("pm path ${context.packageName}", timeoutMs = 2500L)
            val apkPath = pmResult?.output?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")
                ?.trim()
                .orEmpty()
            if (apkPath.isNotBlank()) targetApk = apkPath
        }

        val myUid = android.os.Process.myUid()
        val className = NukeShellDaemon::class.java.name

        // Kill any stale zombie daemon first
        runCatching {
            executeCommand("pkill -f game-nuke-core 2>/dev/null || true", timeoutMs = 1500L)
            Thread.sleep(200)
        }

        // Launch Shizuku-style daemon with detached stdio and proper DEX cache
        val launch = "mkdir -p /data/local/tmp/dalvik-cache 2>/dev/null; export ANDROID_DATA=/data/local/tmp; (export CLASSPATH='$targetApk'; exec /system/bin/app_process /system/bin --nice-name=game-nuke-core $className $myUid </dev/null >/dev/null 2>&1)&"
        Log.i(TAG, "Bootstrapping daemon via ${connectionLabel()}: $launch")
        
        var execResult = executeCommand(launch, timeoutMs = 4500L)
        if (execResult == null) {
            // Direct execution attempts across all available privileged bridges
            if (NukeShizukuBridge.isRunning() && NukeShizukuBridge.hasPermission()) {
                Log.i(TAG, "Trying direct Shizuku execution for core bootstrap")
                NukeShizukuBridge.execute(launch, timeoutMs = 4500L)
            } else if (NukeIadbBridge.isRunning() && NukeIadbBridge.hasPermission()) {
                Log.i(TAG, "Trying direct iAdb execution for core bootstrap")
                NukeIadbBridge.execute(launch, timeoutMs = 4500L)
            }
            runCatching { AdbManager.getInstance(context).ensurePersistentCore() }
        }

        // Wait up to 3s for daemon to become reachable
        for (i in 0 until 20) {
            if (NukeDaemonClient.ping(force = true)) {
                Log.i(TAG, "Daemon core successfully started and reachable")
                return true
            }
            try { Thread.sleep(150L) } catch (_: InterruptedException) { return false }
        }

        // Final fallback: try native ADB manager
        runCatching { AdbManager.getInstance(context).ensurePersistentCore() }

        return NukeDaemonClient.ping(force = true)
    }
}

