package com.neon.gametweak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-level reconnect coordinator.
 *
 * It never bypasses Android's ADB trust model: an explicit authentication rejection stops retries
 * and tears down the local core. Normal endpoint/network loss uses bounded backoff and mDNS
 * self-healing so a still-trusted device reconnects with minimal friction.
 */
object NukeAdbOrchestrator {
    private const val TAG = "NukeAdbOrchestrator"
    private val started = AtomicBoolean(false)
    private val trustCheckRunning = AtomicBoolean(false)
    private val displayRecoveryRunning = AtomicBoolean(false)
    private val lastKick = AtomicLong(0L)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "Nuke-Adb-Orchestrator").apply { isDaemon = true } }
    private var appContext: Context? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeReceiver: BroadcastReceiver? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        if (!started.compareAndSet(false, true)) {
            kick("process-resume")
            return
        }
        registerSignals(context.applicationContext)
        kick("process-start")
        startDaemonWatchdog(context.applicationContext)
    }

    /**
     * Periodic heartbeat: just checks if the daemon is alive via Unix socket ping.
     * Does NOT attempt wireless ADB reconnection — the daemon operates independently
     * of WiFi/wireless ADB. If the daemon dies (OOM, reboot), the user needs to
     * re-pair to restart it, just like Shizuku.
     */
    private fun startDaemonWatchdog(context: Context) {
        val watchdogThread = Thread({
            Thread.sleep(15_000L) // initial grace period
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val alive = NukeDaemonClient.ping(force = true)
                    if (alive) {
                        Log.d(TAG, "Daemon watchdog: core alive")
                    } else {
                        Log.d(TAG, "Daemon watchdog: core not responding (may have been killed by system)")
                    }
                    Thread.sleep(60_000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "Daemon watchdog error", t)
                }
            }
        }, "Nuke-Daemon-Watchdog")
        watchdogThread.isDaemon = true
        watchdogThread.start()
    }


    private fun registerSignals(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { main.postDelayed({ kick("network-available") }, 250L) }
                override fun onLost(network: Network) = Unit
            }
            runCatching { cm.registerDefaultNetworkCallback(cb) }
                .onSuccess { networkCallback = cb }
                .onFailure { Log.w(TAG, "Network callback unavailable", it) }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> main.postDelayed({ kick("screen-on") }, 200L)
                    Intent.ACTION_USER_PRESENT -> main.postDelayed({ kick("user-present") }, 150L)
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> main.postDelayed({ kick("wifi-state") }, 450L)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        }.onSuccess { wakeReceiver = receiver }
            .onFailure { Log.w(TAG, "Reconnect signal receiver unavailable", it) }
    }

    fun kick(reason: String) {
        val context = appContext ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastKick.get()
        if (now - previous < 650L && previous != 0L) return
        lastKick.set(now)

        // CRITICAL: ALL blocking checks must run on io thread, NEVER on UI thread.
        io.execute {
            val adb = AdbManager.getInstance(context)

            // ── Shizuku model: if daemon is online, the app is connected. Period. ──
            // No wireless ADB trust check needed. The daemon operates via Unix abstract socket
            // which is completely independent of WiFi, wireless ADB, or network state.
            if (adb.persistentCoreOnline()) {
                Log.d(TAG, "kick($reason): daemon alive via Unix socket — connected")
                recoverDisplayJournalIfStale(context)
                return@execute
            }

            // Daemon is NOT online. Don't try reconnect if auth was revoked.
            if (adb.authorizationRevoked()) {
                Log.i(TAG, "kick($reason): reconnect paused — authorization requires user repair")
                return@execute
            }

            // Check if already connected via wireless ADB (e.g., during initial pairing flow)
            if (adb.isConnected()) return@execute

            // Daemon offline + not connected: try a single reconnect via wireless ADB.
            // This only works if WiFi + wireless debugging are on. If they're off, this
            // is a no-op and the user needs to re-pair.
            Log.d(TAG, "kick($reason): daemon offline, single reconnect attempt")
            io.execute {
                val live = appContext?.let(AdbManager::getInstance) ?: return@execute
                if (!live.authorizationRevoked() && !live.isConnected()) {
                    runCatching { live.initServer() }
                        .onFailure { Log.w(TAG, "Reconnect attempt failed", it) }
                    if (live.isConnected()) recoverDisplayJournalIfStale(context)
                }
            }
        }
    }


    private fun recoverDisplayJournalIfStale(context: Context) {
        if (!NukeDisplayProfileController.hasOwnedOverride(context)) return
        if (NukeRuntimeState.state.value.overlayRunning || NukeRuntimeState.isLaunchHandoffActive()) return
        if (!displayRecoveryRunning.compareAndSet(false, true)) return
        io.execute {
            try {
                val result = runCatching {
                    NukeDisplayProfileController.recoverStaleOverride(
                        context,
                        overlayRunningInProcess = NukeRuntimeState.state.value.overlayRunning || NukeRuntimeState.isLaunchHandoffActive(),
                    )
                }.getOrNull() ?: return@execute
                when (result.outcome) {
                    NukeDisplayProfileController.Outcome.SUCCESS -> Log.i(TAG, "Recovered stale display profile after ADB reconnect")
                    NukeDisplayProfileController.Outcome.ERROR -> Log.w(TAG, "Display profile recovery failed: ${result.message}")
                    else -> Unit
                }
            } finally {
                displayRecoveryRunning.set(false)
            }
        }
    }
}
