package com.neon.gametweak

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

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
    private const val CORE_BOOTSTRAP_COOLDOWN_MS = 3_000L

    private val coreBootstrapInFlight = AtomicBoolean(false)
    @Volatile private var lastCoreBootstrapRequestMs = 0L

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
                Backend.ADB_NATIVE -> {
                    val adbOk = NukeApplication.instance?.let { runCatching { AdbManager.getInstance(it).isConnected() }.getOrDefault(false) } == true
                    if (adbOk) return Backend.ADB_NATIVE
                }
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

    /**
     * Ensures that a touch-capable backend is available before a macro starts.
     * This runs on macro/worker threads only; it must not be called from the UI thread.
     */
    fun ensureTouchBackend(timeoutMs: Long = 1_500L): Boolean {
        val binder = getShellService()
        if (binder != null && runCatching { binder.ping() }.getOrDefault(false)) return true
        if (NukeDaemonClient.ping()) return true

        val context = NukeApplication.instance ?: return false
        if (activeBackend() != Backend.ADB_NATIVE) return false

        if (coreBootstrapInFlight.compareAndSet(false, true)) {
            lastCoreBootstrapRequestMs = SystemClock.elapsedRealtime()
            return try {
                bootstrapPersistentCore(context) && NukeDaemonClient.ping(force = true)
            } catch (t: Throwable) {
                Log.w(TAG, "Touch backend bootstrap failed: ${t.message}")
                false
            } finally {
                coreBootstrapInFlight.set(false)
            }
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(200L, 3_500L)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (NukeDaemonClient.ping(force = true)) return true
            try { Thread.sleep(60L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return NukeDaemonClient.ping(force = true)
    }

    private fun requestCoreBootstrapIfNeeded() {
        if (activeBackend() != Backend.ADB_NATIVE) return
        val context = NukeApplication.instance ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCoreBootstrapRequestMs < CORE_BOOTSTRAP_COOLDOWN_MS) return
        if (!coreBootstrapInFlight.compareAndSet(false, true)) return
        lastCoreBootstrapRequestMs = now
        kotlin.concurrent.thread(name = "Nuke-AdbCore-Bootstrap", isDaemon = true) {
            try {
                bootstrapPersistentCore(context)
            } catch (t: Throwable) {
                Log.w(TAG, "Background core bootstrap failed: ${t.message}")
            } finally {
                coreBootstrapInFlight.set(false)
            }
        }
    }

    /** Returns true if Touch Listener is supported through current connections. */
    fun isTouchSupported(): Boolean = getShellService() != null || NukeDaemonClient.ping() || isConnected()

    /**
     * Direct high-speed tap injection across available privileged backends (Binder -> Local Core TCP -> Shell fallback).
     */
    fun injectTap(x: Float, y: Float, durationMs: Long = 15L): Boolean {
        // 1. Privileged Binder (Shizuku / iAdb) — kernel evdev multi-touch
        val service = getShellService()
        if (service != null) {
            val ok = runCatching { service.injectTap(x, y, durationMs) }.getOrDefault(false)
            if (ok) return true
        }

        // 2. Persistent Local Core Daemon (TCP 18294) — kernel evdev multi-touch
        if (NukeDaemonClient.touchTap(x, y, durationMs)) {
            return true
        }

        // Request a single rate-limited bootstrap; do not spawn one thread per failed tap.
        requestCoreBootstrapIfNeeded()

        // NO shell fallback — macro requires NukeTouchService for real multi-touch support.
        return false
    }

    /**
     * Direct high-speed hold injection across available privileged backends.
     */
    fun injectHold(x: Float, y: Float, durationMs: Long): Boolean {
        // 1. Privileged Binder (Shizuku / iAdb) — kernel evdev multi-touch
        val service = getShellService()
        if (service != null) {
            val ok = runCatching { service.injectHold(x, y, durationMs) }.getOrDefault(false)
            if (ok) return true
        }

        // 2. Persistent Local Core Daemon — kernel evdev multi-touch
        if (NukeDaemonClient.touchHold(x, y, durationMs)) {
            return true
        }

        // Request a single rate-limited bootstrap; the next macro event will use the ready core.
        requestCoreBootstrapIfNeeded()

        // NO shell fallback — macro requires NukeTouchService for real multi-touch support.
        return false
    }

    /**
     * Direct swipe / drag injection (SWIPE macro) across available privileged backends.
     */
    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 120L): Boolean {
        // 1. Privileged Binder (Shizuku / iAdb) — kernel evdev multi-touch
        val service = getShellService()
        if (service != null) {
            val ok = runCatching { service.injectSwipe(x1, y1, x2, y2, durationMs) }.getOrDefault(false)
            if (ok) return true
        }

        // 2. Persistent Local Core Daemon — kernel evdev multi-touch
        if (NukeDaemonClient.touchSwipe(x1, y1, x2, y2, durationMs)) {
            return true
        }

        // Request a single rate-limited bootstrap; the next macro event will use the ready core.
        requestCoreBootstrapIfNeeded()

        // NO shell fallback — macro requires NukeTouchService for real multi-touch support.
        return false
    }

    /**
     * Synchronizes active macro pins into the kernel Touch Listener in the privileged daemon/service.
     * Enables hardware-level multi-touch detection and zero-latency burst execution.
     */
    fun syncMacroPins(pinsConfig: String): Boolean {
        val ctx = NukeApplication.instance
        if (ctx != null && pinsConfig.isNotEmpty() && !nuke.wandev.touch.NukeTouchDeployer.isDeployed()) {
            nuke.wandev.touch.NukeTouchDeployer.ensureDeployed(ctx)
        }

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
            val ok = NukeDaemonClient.setMacroPins(pinsConfig)
            if (ok) return true
        }

        // 3. Auto-bootstrap daemon on Native ADB if offline and push pins
        if (activeBackend() == Backend.ADB_NATIVE) {
            if (ctx != null) {
                if (AdbManager.getInstance(ctx).ensurePersistentCore()) {
                    return NukeDaemonClient.setMacroPins(pinsConfig)
                }
            }
        }

        return false
    }

    /**
     * Starts Touch Listener across privileged backends (Shizuku, iAdb, or Native ADB Daemon).
     */
    fun touchStart(libPath: String): Int {
        val ctx = NukeApplication.instance
        if (ctx != null && !nuke.wandev.touch.NukeTouchDeployer.isDeployed()) {
            nuke.wandev.touch.NukeTouchDeployer.ensureDeployed(ctx)
        }
        val service = getShellService()
        if (service != null) {
            val count = runCatching { service.touchStart(libPath) }.getOrDefault(-1)
            if (count >= 0) return count
        }
        if (NukeDaemonClient.ping(force = true)) {
            val started = NukeDaemonClient.touchStart(libPath)
            if (started || NukeDaemonClient.touchStatus()) return 1
        }
        // Auto-bootstrap local core daemon if on ADB_NATIVE or if privileged daemon is not running yet
        if (ctx != null) {
            if (isConnected()) {
                val ok = bootstrapPersistentCore(ctx)
                if (ok && NukeDaemonClient.ping(force = true)) {
                    val started = NukeDaemonClient.touchStart(libPath)
                    if (started || NukeDaemonClient.touchStatus()) return 1
                }
            }
        }
        return -1
    }

    /**
     * Stops Touch Listener safely across privileged backends.
     */
    fun touchStop(): Boolean {
        val service = getShellService()
        if (service != null) {
            val ok = runCatching { service.touchStop(); true }.getOrDefault(false)
            if (ok) return true
        }
        if (NukeDaemonClient.ping()) {
            return NukeDaemonClient.touchStop()
        }
        return false
    }

    /**
     * Configures X & Y touch sensitivity multipliers across all privileged backends.
     */
    fun touchConfigure(
        sx: Float,
        sy: Float,
        area: Int = 0,
        curve: Int = 0,
        smooth: Boolean = false,
        minCutoff: Float = 8.0f,
        beta: Float = 0.08f,
        dragShot: Boolean = false
    ): Boolean {
        val service = getShellService()
        if (service != null) {
            val ok = runCatching {
                service.touchConfigure(sx, sy, area, curve, smooth, minCutoff, beta, dragShot)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        if (NukeDaemonClient.ping()) {
            return NukeDaemonClient.touchConfig(
                sx = sx, sy = sy, area = area, curve = curve,
                smooth = smooth, minCutoff = minCutoff, beta = beta, dragShot = dragShot
            )
        }
        if (activeBackend() == Backend.ADB_NATIVE) {
            val ctx = NukeApplication.instance
            if (ctx != null) {
                if (AdbManager.getInstance(ctx).ensurePersistentCore()) {
                    return NukeDaemonClient.touchConfig(
                        sx = sx, sy = sy, area = area, curve = curve,
                        smooth = smooth, minCutoff = minCutoff, beta = beta, dragShot = dragShot
                    )
                }
            }
        }
        return false
    }

    /**
     * Execute a shell command through the best available backend.
     * With automatic fallback resilience across all connected engines (iAdb, Shizuku, Daemon, ADB Native).
     * Returns null if no backend is available.
     * NEVER throws — all exceptions are caught internally.
     */
    fun executeCommand(
        command: String,
        timeoutMs: Long = 7_500L,
        maxOutputChars: Int = 131_072,
    ): NukeCommandResult? {
        if (IntegrityGuard.isCompromised()) return null
        val primary = activeBackend()
        if (primary != Backend.NONE) {
            val res = runBackendCommand(primary, command, timeoutMs, maxOutputChars)
            if (res != null) return res
        }

        // Automatic fallback across other active backends if primary encounters a transient error
        val fallbacks = listOf(Backend.IADB, Backend.SHIZUKU, Backend.DAEMON, Backend.ADB_NATIVE) - primary
        for (backend in fallbacks) {
            val res = runBackendCommand(backend, command, timeoutMs, maxOutputChars)
            if (res != null) {
                Log.i(TAG, "Command succeeded via fallback backend $backend")
                return res
            }
        }
        return null
    }

    private fun runBackendCommand(
        backend: Backend,
        command: String,
        timeoutMs: Long,
        maxOutputChars: Int
    ): NukeCommandResult? {
        return when (backend) {
            Backend.IADB -> runCatching {
                if (NukeIadbBridge.isConnected()) NukeIadbBridge.execute(command, timeoutMs, maxOutputChars) else null
            }.onFailure { Log.w(TAG, "iAdb execute failed: ${it.message}") }.getOrNull()

            Backend.SHIZUKU -> runCatching {
                if (NukeShizukuBridge.isConnected()) NukeShizukuBridge.execute(command, timeoutMs, maxOutputChars) else null
            }.onFailure { Log.w(TAG, "Shizuku execute failed: ${it.message}") }.getOrNull()

            Backend.DAEMON -> runCatching {
                if (NukeDaemonClient.ping()) NukeDaemonClient.execute(command, timeoutMs, maxOutputChars) else null
            }.onFailure { Log.w(TAG, "Daemon execute failed: ${it.message}") }.getOrNull()

            Backend.ADB_NATIVE -> runCatching {
                val ctx = NukeApplication.instance ?: return null
                val adb = AdbManager.getInstance(ctx)
                if (adb.isConnected()) adb.executeCommandDirect(command, "/", timeoutMs, maxOutputChars) else null
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
            executeCommand("kill -9 \$(pidof game-nuke-core 2>/dev/null) 2>/dev/null; pkill -9 -f game-nuke-core 2>/dev/null || true", timeoutMs = 1500L)
            Thread.sleep(200)
        }

        // Ensure dalvik-cache exists, ANDROID_DATA is set, token is written, and libwandev.so is available in /data/local/tmp
        val tok = NukeDaemonClient.getToken(context)
        executeCommand("mkdir -p /data/local/tmp/dalvik-cache 2>/dev/null; export ANDROID_DATA=/data/local/tmp; echo '$tok' > /data/local/tmp/.nuke_token 2>/dev/null; chmod 644 /data/local/tmp/.nuke_token 2>/dev/null", timeoutMs = 2000L)
        nuke.wandev.touch.NukeTouchDeployer.ensureDeployed(context)

        // Launch Shizuku-style daemon with detached stdio and proper DEX cache
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

                // Instant hardware touch library deployment (passive mode, no auto-grab/touchStart)
                kotlin.concurrent.thread(name = "Nuke-NativeTouchBoot", isDaemon = true) {
                    try {
                        nuke.wandev.touch.NukeTouchDeployer.ensureDeployed(context)
                        Log.i(TAG, "Native ADB persistent core online: libwandev.so deployed safely in passive mode")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Nuke-NativeTouchBoot error: ${t.message}")
                    }
                }
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
        val lower = cleanPkg.lowercase(java.util.Locale.US)
        val myPkg = context.packageName.lowercase(java.util.Locale.US)
        if (lower == myPkg ||
            lower == "com.neon.gametweak" ||
            lower.contains("gametweak") ||
            lower.contains("wandev") ||
            lower.contains("axeron") ||
            lower.contains("nuke") ||
            lower.contains("shell") ||
            lower.contains("shizuku") ||
            lower.contains("iadb") ||
            lower.contains("freefire") ||
            lower.contains("dts") ||
            lower.contains("garena") ||
            lower.contains("recorder") ||
            lower.contains("screencap") ||
            NukeProcessPurgeGuardian.isProtected(context, cleanPkg) ||
            NukeScreenRecordGuardian.isProtected(cleanPkg)) {
            Log.w(TAG, "Refusing to kill immune package: $cleanPkg")
            return false
        }
        var success = false

        // 1. Privileged shell command if connected
        if (isConnected()) {
            val cmd = "am force-stop $cleanPkg 2>/dev/null || am kill $cleanPkg 2>/dev/null\n"
            val res = executeCommand(cmd, timeoutMs = 2500L)
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

    /**
     * Unified lifecycle hook invoked whenever any privileged bridge (Shizuku, iAdb, or Native ADB)
     * connects and becomes ready. Checks whether libwandev.so already exists in /data/local/tmp;
     * if present, it reuses it immediately. If missing, it deploys it and initializes the touch driver
     * early so Sensi X, Sensi Y, detection zone, speed response curve, and macros are instantly ready.
     */
    fun notifyBridgeReady(context: android.content.Context) {
        kotlin.concurrent.thread(name = "Nuke-BridgeReadySync", isDaemon = true) {
            try {
                // 1. Check or deploy libwandev.so
                val isPresent = nuke.wandev.touch.NukeTouchDeployer.isDeployed()
                if (isPresent) {
                    Log.i(TAG, "notifyBridgeReady: libwandev.so already present in /data/local/tmp — reusing existing library")
                } else {
                    Log.i(TAG, "notifyBridgeReady: libwandev.so not found, deploying now...")
                    nuke.wandev.touch.NukeTouchDeployer.ensureDeployed(context)
                }

                // 2. Passive touch driver readiness verification (no aggressive grab on bridge connect)
                Log.i(TAG, "notifyBridgeReady: bridge connection established safely (backend=${connectionLabel()})")

                // 3. Synchronize touch sensitivity, detection zone & speed response curve
                NukeTouchTuningEngine.syncToDaemon(context)

                // 4. Synchronize active macro profile
                runCatching {
                    val prof = NukeMacroRepository.activeProfile(context)
                    if (prof.useMapping && prof.pins.isNotEmpty()) {
                        NukeMacroRepository.pushProfileToDaemon(context, prof)
                        Log.i(TAG, "notifyBridgeReady: macro profile '${prof.name}' (${prof.pins.size} pins) armed")
                    }
                }

                // 5. Silent overlay permission grant if needed
                runCatching { OverlayPermissionController.tryAutoGrantViaBridge(context, silent = true) }
            } catch (t: Throwable) {
                Log.w(TAG, "notifyBridgeReady warning: ${t.message}")
            }
        }
    }
}

