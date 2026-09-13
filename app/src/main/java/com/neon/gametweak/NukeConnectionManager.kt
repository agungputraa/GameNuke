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

    /**
     * Guarantees active privileged IShellService Binder, attempting auto-reconnect if needed.
     * Blocks up to [timeoutMs] ms on background worker threads.
     */
    fun ensureShellService(timeoutMs: Long = 2000L): IShellService? {
        val direct = getShellService()
        if (direct != null && runCatching { direct.ping() }.getOrDefault(false)) {
            return direct
        }
        val perBridgeTimeout = (timeoutMs / 2).coerceIn(400L, 2000L)
        return NukeIadbBridge.ensureConnected(perBridgeTimeout)
            ?: NukeShizukuBridge.ensureConnected(perBridgeTimeout)
            ?: getShellService()
    }

    /** Returns true if Touch Listener is supported through current connections. */
    fun isTouchSupported(): Boolean = getShellService() != null || NukeDaemonClient.ping() || isConnected()

    /**
     * Direct high-speed tap injection across available privileged backends (Binder -> Local Core TCP -> Shell fallback).
     */
    fun injectTap(x: Float, y: Float, durationMs: Long = 15L): Boolean {
        // 1. Privileged Binder (Shizuku / iAdb)
        val service = getShellService()
        if (service != null) {
            val ok = runCatching { service.injectTap(x, y, durationMs) }.getOrDefault(false)
            if (ok) return true
        }

        // 2. Persistent Local Core Daemon (TCP 18294)
        if (NukeDaemonClient.ping()) {
            val ok = NukeDaemonClient.touchTap(x, y, durationMs)
            if (ok) return true
        }

        // 3. Fallback: Shell command
        val cmd = "input tap ${x.toInt()} ${y.toInt()}"
        val res = executeCommand(cmd, timeoutMs = 2000L)
        return res?.isSuccess == true
    }

    /**
     * Direct high-speed hold injection across available privileged backends.
     */
    fun injectHold(x: Float, y: Float, durationMs: Long): Boolean {
        // 1. Privileged Binder (Shizuku / iAdb)
        val service = getShellService()
        if (service != null) {
            val ok = runCatching { service.injectHold(x, y, durationMs) }.getOrDefault(false)
            if (ok) return true
        }

        // 2. Persistent Local Core Daemon
        if (NukeDaemonClient.ping()) {
            val ok = NukeDaemonClient.touchHold(x, y, durationMs)
            if (ok) return true
        }

        // 3. Fallback: Shell swipe in place
        val ipx = x.toInt()
        val ipy = y.toInt()
        val dur = durationMs.coerceIn(30L, 5000L)
        val res = executeCommand("input swipe $ipx $ipy $ipx $ipy $dur", timeoutMs = dur + 2000L)
        return res?.isSuccess == true
    }

    /**
     * Direct swipe / drag injection (SWIPE macro) across available privileged backends.
     */
    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 120L): Boolean {
        // 1. Privileged Binder (Shizuku / iAdb)
        val service = getShellService()
        if (service != null) {
            val ok = runCatching { service.injectSwipe(x1, y1, x2, y2, durationMs) }.getOrDefault(false)
            if (ok) return true
        }

        // 2. Persistent Local Core Daemon
        if (NukeDaemonClient.ping()) {
            val ok = NukeDaemonClient.touchSwipe(x1, y1, x2, y2, durationMs)
            if (ok) return true
        }

        // 3. Fallback: Shell input swipe
        val dur = durationMs.coerceIn(20L, 2000L)
        val res = executeCommand(
            "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $dur",
            timeoutMs = dur + 2000L
        )
        return res?.isSuccess == true
    }

    /**
     * Synchronizes active macro pins into the kernel Touch Listener in the privileged daemon/service.
     * Enables hardware-level multi-touch detection and zero-latency burst execution.
     */
    fun syncMacroPins(pinsConfig: String): Boolean {
        // 1. Privileged Binder
        val service = getShellService()
        if (service != null) {
            val ok = runCatching {
                service.setMacroPins(pinsConfig)
                true
            }.getOrDefault(false)
            if (ok) return true
        }

        // 2. Persistent Local Core Daemon (TCP)
        if (NukeDaemonClient.ping()) {
            return NukeDaemonClient.setMacroPins(pinsConfig)
        }

        return false
    }

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
        if (IntegrityGuard.isCompromised()) return null
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
        if (IntegrityGuard.isCompromised()) return false
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

        // Ensure dalvik-cache exists, ANDROID_DATA is set, and libwandev.so is available in /data/local/tmp
        val nativeDir = context.applicationInfo.nativeLibraryDir
        executeCommand("mkdir -p /data/local/tmp/dalvik-cache 2>/dev/null; export ANDROID_DATA=/data/local/tmp; unzip -o -j '$targetApk' lib/arm64-v8a/libwandev.so -d /data/local/tmp/ >/dev/null 2>&1 || cp '$nativeDir/libwandev.so' /data/local/tmp/libwandev.so 2>/dev/null; chmod 755 /data/local/tmp/libwandev.so 2>/dev/null", timeoutMs = 3000L)

        // Launch Shizuku-style daemon with detached stdio and proper DEX cache
        val tok = NukeDaemonClient.getToken(context)
        val tokenArg = if (tok.isNotEmpty()) "--token=$tok" else ""
        val launch = "mkdir -p /data/local/tmp/dalvik-cache 2>/dev/null; export ANDROID_DATA=/data/local/tmp; (export CLASSPATH='$targetApk'; exec /system/bin/app_process /system/bin --nice-name=game-nuke-core $className $myUid $tokenArg </dev/null >/dev/null 2>&1)&"
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

        // Wait up to 3.5s for daemon to become reachable
        for (i in 0 until 20) {
            if (NukeDaemonClient.ping(force = true)) {
                Log.i(TAG, "Daemon core successfully started and reachable")
                runCatching { AdbManager.getInstance(context).disablePhantomProcessKiller() }
                executeCommand("PID=\$(pidof game-nuke-core 2>/dev/null); if [ -n \"\$PID\" ]; then echo -1000 > /proc/\$PID/oom_score_adj 2>/dev/null; renice -n -20 -p \$PID 2>/dev/null; fi", timeoutMs = 1500L)
                return true
            }
            try { Thread.sleep(175L) } catch (_: InterruptedException) { return false }
        }

        // Final fallback: try native ADB manager
        runCatching { AdbManager.getInstance(context).ensurePersistentCore() }

        return NukeDaemonClient.ping(force = true)
    }

    /**
     * Safely terminates an individual process after verifying it is not protected.
     * Guaranteed never to kill current game, Game Nuke, screen recorders, or system core.
     */
    fun killProcessSafe(context: android.content.Context, packageName: String, pid: String?): Boolean {
        if (NukeProcessPurgeGuardian.isProtected(context, packageName)) {
            Log.w(TAG, "Refusing to kill protected package: $packageName")
            return false
        }
        val cleanPkg = packageName.trim().substringBefore(':')
        var success = false

        // 1. Privileged shell command if connected
        if (isConnected()) {
            val cmd = StringBuilder()
            cmd.append("am force-stop $cleanPkg 2>/dev/null || am kill $cleanPkg 2>/dev/null\n")
            if (!pid.isNullOrBlank() && pid.all { it.isDigit() }) {
                cmd.append("kill -9 $pid 2>/dev/null\n")
            }
            val res = executeCommand(cmd.toString(), timeoutMs = 2500L)
            if (res != null && res.isSuccess) {
                success = true
            }
        }

        // 2. Standard ActivityManager non-root fallback
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        runCatching {
            am?.killBackgroundProcesses(cleanPkg)
            success = true
        }

        return success
    }
}

